<#
.SYNOPSIS
    End-to-end check of the whole booking flow against a running server.
    Doubles as the demo script.

.DESCRIPTION
    Walks: health -> register -> login -> create venue -> generate seats ->
    create event -> seat map -> hold -> confirm -> history -> cancel ->
    re-hold the freed seat -> admin listing.

    The re-hold at the end is the important one: it is the concrete proof that
    replacing UNIQUE(event_seat_id) with the partial index uq_booking_seats_active
    made cancel-then-rebook possible (see README section 4.2).

    Creates real rows in the database. Point it at a development project, not
    production. Everything it creates is named "Smoke ..." so it is easy to spot.

.PARAMETER BaseUrl
    API root. Defaults to http://localhost:8080/api

.PARAMETER AdminEmail / AdminPassword
    An ADMIN account. Defaults to $env:ADMIN_BOOTSTRAP_EMAIL / _PASSWORD, i.e. the
    account the server creates on boot. Venues and events need ADMIN.

.EXAMPLE
    .\smoke.ps1
    .\smoke.ps1 -BaseUrl http://localhost:8080/api -AdminEmail admin@example.com -AdminPassword secret123
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080/api',
    [string]$AdminEmail = $env:ADMIN_BOOTSTRAP_EMAIL,
    [string]$AdminPassword = $env:ADMIN_BOOTSTRAP_PASSWORD
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\api-helpers.ps1"
$script:BaseUrl = $BaseUrl

if (-not $AdminEmail -or -not $AdminPassword) {
    Write-Host "Admin credentials are required: venues and events are admin-only." -ForegroundColor Yellow
    Write-Host "Set ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD before starting the" -ForegroundColor Yellow
    Write-Host "server, or pass -AdminEmail / -AdminPassword." -ForegroundColor Yellow
    exit 2
}

Write-Host "Smoke test against $BaseUrl" -ForegroundColor White

# ---------------------------------------------------------------- 1. health
Write-Step "Health"
$health = Invoke-Api GET '/health'
Assert-Status 'health responds' $health 200
Assert-That 'database is connected' ($health.Body.database -eq 'connected') `
    "database reported '$($health.Body.database)' - check DB_URL and credentials"
if ($health.Body.database -ne 'connected') {
    Write-Host "Stopping: without a database nothing below can work." -ForegroundColor Red
    exit (Write-Summary 'Smoke test')
}

# ------------------------------------------------------- 2. register + login
Write-Step "Registration and login"
$userEmail = New-UniqueEmail 'smoke.user'
$register = Invoke-Api POST '/auth/register' -Body @{
    name = 'Smoke User'; email = $userEmail; password = 'smokepass123'
}
Assert-Status 'a new account is created' $register 201
Assert-That 'registration never returns a password field' `
    ($null -eq $register.Body.password -and $null -eq $register.Body.passwordHash)
Assert-That 'public registration only makes USERs' ($register.Body.role -eq 'USER') `
    "role was '$($register.Body.role)'"

$duplicate = Invoke-Api POST '/auth/register' -Body @{
    name = 'Smoke User'; email = $userEmail; password = 'smokepass123'
}
Assert-Status 'the same email cannot register twice' $duplicate 409
Assert-That 'and reports EMAIL_ALREADY_REGISTERED' ($duplicate.Body.error -eq 'EMAIL_ALREADY_REGISTERED')

$badLogin = Invoke-Api POST '/auth/login' -Body @{ email = $userEmail; password = 'wrong-password' }
Assert-Status 'a wrong password is rejected' $badLogin 401

$login = Invoke-Api POST '/auth/login' -Body @{ email = $userEmail; password = 'smokepass123' }
Assert-Status 'login returns a token' $login 200
$userToken = $login.Body.token
Assert-That 'the token is non-empty' ([bool]$userToken)

$me = Invoke-Api GET '/auth/me' -Token $userToken
Assert-Status 'the token identifies the account' $me 200
Assert-That 'and it is the right account' ($me.Body.email -eq $userEmail)

$adminLogin = Invoke-Api POST '/auth/login' -Body @{ email = $AdminEmail; password = $AdminPassword }
Assert-Status 'the admin can log in' $adminLogin 200
$adminToken = $adminLogin.Body.token
Assert-That 'the admin has role ADMIN' ($adminLogin.Body.user.role -eq 'ADMIN') `
    "role was '$($adminLogin.Body.user.role)'"

Write-Step "Authorisation boundaries"
$noToken = Invoke-Api GET '/admin/venues'
Assert-Status 'admin routes reject an anonymous caller' $noToken 401
$userOnAdmin = Invoke-Api GET '/admin/venues' -Token $userToken
Assert-Status 'admin routes reject a valid NON-admin token' $userOnAdmin 403

# ------------------------------------------------------- 3. venue + seats
Write-Step "Venue and seat layout"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$venue = Invoke-Api POST '/admin/venues' -Token $adminToken -Body @{
    name = "Smoke Arena $stamp"; address = '1 Test Road'; city = 'Testville'; totalCapacity = 12
}
Assert-Status 'a venue is created' $venue 201
$venueId = $venue.Body.id

$layout = Invoke-Api POST "/admin/venues/$venueId/seats" -Token $adminToken -Body @{
    rows = @('A', 'B'); seatsPerRow = 4; seatType = 'PREMIUM'
}
Assert-Status 'a seat layout is generated' $layout 201
Assert-That '2 rows x 4 seats = 8 seats created' ($layout.Body.seatsCreated -eq 8) `
    "seatsCreated was $($layout.Body.seatsCreated)"

$collision = Invoke-Api POST "/admin/venues/$venueId/seats" -Token $adminToken -Body @{
    rows = @('A'); seatsPerRow = 4; seatType = 'PREMIUM'
}
Assert-Status 'the same seat labels cannot be created twice' $collision 409
Assert-That 'and it reports SEATS_ALREADY_EXIST' ($collision.Body.error -eq 'SEATS_ALREADY_EXIST')

# ------------------------------------------------------- 4. event
Write-Step "Event with auto-generated seat rows"
$startTime = (Get-Date).ToUniversalTime().AddDays(30).ToString('yyyy-MM-ddTHH:mm:00')
$endTime = (Get-Date).ToUniversalTime().AddDays(30).AddHours(3).ToString('yyyy-MM-ddTHH:mm:00')
$event = Invoke-Api POST '/admin/events' -Token $adminToken -Body @{
    venueId = $venueId; title = "Smoke Concert $stamp"; category = 'Concert'
    startTime = $startTime; endTime = $endTime; basePrice = '1000.00'; status = 'PUBLISHED'
}
Assert-Status 'an event is created' $event 201
$eventId = $event.Body.event.id
Assert-That 'one event_seats row per venue seat was generated' ($event.Body.seatsGenerated -eq 8) `
    "seatsGenerated was $($event.Body.seatsGenerated)"

$listed = Invoke-Api GET '/events?city=Testville'
Assert-Status 'the event appears in the public listing' $listed 200
Assert-That 'and it is findable by city' `
    ([bool]($listed.Body | Where-Object { $_.id -eq $eventId }))

# ------------------------------------------------------- 5. seat map
Write-Step "Seat map"
$seatMap = Invoke-Api GET "/events/$eventId/seats"
Assert-Status 'the seat map loads' $seatMap 200
Assert-That 'it has 8 seats, all available' `
    ($seatMap.Body.summary.total -eq 8 -and $seatMap.Body.summary.available -eq 8) `
    "summary was $($seatMap.Body.summary | ConvertTo-Json -Compress)"
$firstSeat = $seatMap.Body.seats[0]
$secondSeat = $seatMap.Body.seats[1]
Assert-That 'PREMIUM seats are priced at 1000 x 1.50' ($firstSeat.price -eq '1500.00') `
    "price was $($firstSeat.price)"
Assert-That 'prices are strings, not JSON numbers' ($firstSeat.price -is [string])

# ------------------------------------------------------- 6. hold
Write-Step "Hold"
$hold = Invoke-Api POST "/events/$eventId/hold" -Token $userToken -Body @{
    eventSeatIds = @($firstSeat.eventSeatId, $secondSeat.eventSeatId)
}
Assert-Status 'two seats are held' $hold 201
$bookingId = $hold.Body.id
Assert-That 'the hold is a PENDING booking' ($hold.Body.status -eq 'PENDING')
Assert-That 'it carries a booking reference already' ($hold.Body.bookingReference -like 'TB-*') `
    "reference was '$($hold.Body.bookingReference)'"
Assert-That 'it carries a hold deadline' ([bool]$hold.Body.expiresAt)
Assert-That 'the total is the sum of both seats' ($hold.Body.totalAmount -eq '3000.00') `
    "total was $($hold.Body.totalAmount)"

$afterHold = Invoke-Api GET "/events/$eventId/seats"
Assert-That 'the seat map now shows 2 locked, 6 available' `
    ($afterHold.Body.summary.locked -eq 2 -and $afterHold.Body.summary.available -eq 6) `
    "summary was $($afterHold.Body.summary | ConvertTo-Json -Compress)"

$duplicateSeats = Invoke-Api POST "/events/$eventId/hold" -Token $userToken -Body @{
    eventSeatIds = @($seatMap.Body.seats[2].eventSeatId, $seatMap.Body.seats[2].eventSeatId)
}
Assert-Status 'the same seat twice in one request is rejected' $duplicateSeats 400

# ------------------------------------------------------- 7. confirm
Write-Step "Confirmation"
$confirm = Invoke-Api POST "/bookings/$bookingId/confirm" -Token $userToken
Assert-Status 'the hold is confirmed' $confirm 200
Assert-That 'the booking is CONFIRMED' ($confirm.Body.status -eq 'CONFIRMED')
Assert-That 'the reference issued at hold time is unchanged' `
    ($confirm.Body.bookingReference -eq $hold.Body.bookingReference)
Assert-That 'a confirmed booking has no hold deadline' ($null -eq $confirm.Body.expiresAt)

$confirmAgain = Invoke-Api POST "/bookings/$bookingId/confirm" -Token $userToken
Assert-Status 'confirming twice is idempotent' $confirmAgain 200

$afterConfirm = Invoke-Api GET "/events/$eventId/seats"
Assert-That 'the seat map now shows 2 booked' ($afterConfirm.Body.summary.booked -eq 2) `
    "summary was $($afterConfirm.Body.summary | ConvertTo-Json -Compress)"

# ------------------------------------------------------- 8. history
Write-Step "History and ownership"
$history = Invoke-Api GET '/bookings/me' -Token $userToken
Assert-Status 'history loads' $history 200
Assert-That 'it contains the booking' ([bool]($history.Body | Where-Object { $_.id -eq $bookingId }))

$otherEmail = New-UniqueEmail 'smoke.other'
Invoke-Api POST '/auth/register' -Body @{
    name = 'Other User'; email = $otherEmail; password = 'smokepass123'
} | Out-Null
$otherToken = (Invoke-Api POST '/auth/login' -Body @{
    email = $otherEmail; password = 'smokepass123'
}).Body.token

$foreignRead = Invoke-Api GET "/bookings/$bookingId" -Token $otherToken
Assert-Status "another user cannot read someone else's booking" $foreignRead 403
$foreignCancel = Invoke-Api DELETE "/bookings/$bookingId" -Token $otherToken
Assert-Status "another user cannot cancel someone else's booking" $foreignCancel 403

# ------------------------------------------------------- 9. contention
Write-Step "A booked seat cannot be taken by anyone else"
$stealBooked = Invoke-Api POST "/events/$eventId/hold" -Token $otherToken -Body @{
    eventSeatIds = @($firstSeat.eventSeatId)
}
Assert-Status 'holding a sold seat is refused' $stealBooked 409
Assert-That 'and it reports SEATS_UNAVAILABLE' ($stealBooked.Body.error -eq 'SEATS_UNAVAILABLE')
Assert-That 'naming the exact seat that was lost' ([bool]$stealBooked.Body.unavailableSeats)

# ------------------------------------------------------- 10. cancel + re-hold
Write-Step "Cancel, then re-book the freed seat (the schema-fix proof)"
$cancel = Invoke-Api DELETE "/bookings/$bookingId" -Token $userToken
Assert-Status 'the booking is cancelled' $cancel 200
Assert-That 'its status is CANCELLED' ($cancel.Body.status -eq 'CANCELLED')
Assert-That 'and it still lists the seats it had, as history' ($cancel.Body.seatLabels.Count -eq 2)

$afterCancel = Invoke-Api GET "/events/$eventId/seats"
Assert-That 'all 8 seats are available again' ($afterCancel.Body.summary.available -eq 8) `
    "summary was $($afterCancel.Body.summary | ConvertTo-Json -Compress)"

# With the original UNIQUE(event_seat_id) this is the request that failed.
$reHold = Invoke-Api POST "/events/$eventId/hold" -Token $otherToken -Body @{
    eventSeatIds = @($firstSeat.eventSeatId)
}
Assert-Status 'a different user can now book the freed seat' $reHold 201
$reConfirm = Invoke-Api POST "/bookings/$($reHold.Body.id)/confirm" -Token $otherToken
Assert-Status 'and confirm it' $reConfirm 200

# ------------------------------------------------------- 11. admin oversight
Write-Step "Admin oversight"
$adminList = Invoke-Api GET "/admin/bookings?eventId=$eventId" -Token $adminToken
Assert-Status 'the admin sees bookings for the event' $adminList 200
Assert-That 'both the cancelled and the new booking are listed' ($adminList.Body.total -ge 2) `
    "total was $($adminList.Body.total)"
Assert-That 'admin rows say who booked' ([bool]$adminList.Body.bookings[0].user.email) `
    'the user block was missing'

$paged = Invoke-Api GET "/admin/bookings?eventId=$eventId&limit=1" -Token $adminToken
Assert-That 'limit caps the page' ($paged.Body.bookings.Count -eq 1)
Assert-That 'while total still counts every match' ($paged.Body.total -ge 2)

$badLimit = Invoke-Api GET '/admin/bookings?limit=5000' -Token $adminToken
Assert-Status 'an out-of-range limit is refused, not clamped' $badLimit 400

$adminCancel = Invoke-Api DELETE "/bookings/$($reHold.Body.id)" -Token $otherToken
Assert-Status 'the owner can cancel their own booking' $adminCancel 200

Write-Step "Error envelope"
$missing = Invoke-Api GET "/events/$([guid]::NewGuid())"
Assert-Status 'an unknown event is a 404' $missing 404
Assert-That 'errors use the standard envelope' `
    ($missing.Body.error -eq 'NOT_FOUND' -and [bool]$missing.Body.message)
$badUuid = Invoke-Api GET '/events/not-a-uuid'
Assert-Status 'a malformed id is a 400, not a 500' $badUuid 400
$noRoute = Invoke-Api GET '/does-not-exist'
Assert-That 'unmatched routes also use the envelope' ($noRoute.Body.error -eq 'NOT_FOUND')

exit (Write-Summary 'Smoke test')
