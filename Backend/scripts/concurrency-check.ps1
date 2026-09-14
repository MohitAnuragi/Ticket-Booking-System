<#
.SYNOPSIS
    Fires two simultaneous holds at the SAME seat and checks that exactly one wins.

.DESCRIPTION
    This is the one guarantee unit tests cannot prove. Row locking
    (SELECT ... FOR UPDATE) and the partial unique index uq_booking_seats_active are
    PostgreSQL behaviours, so they need two real clients hitting one real database.

    Expected outcome: one request gets 201, the other 409 SEATS_UNAVAILABLE. Two
    201s would mean the same seat was sold twice; that is the failure this whole
    design exists to prevent.

    Both requests are dispatched from background jobs that wait for a common
    wall-clock instant before firing, so they arrive together rather than one after
    the other - a sequential pair would pass trivially and prove nothing.

    Repeat with -Rounds to make a flaky race more likely to show itself.

.PARAMETER Rounds
    How many contended seats to test. Each round uses a fresh seat.

.EXAMPLE
    .\concurrency-check.ps1 -Rounds 5
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080/api',
    [string]$AdminEmail = $env:ADMIN_BOOTSTRAP_EMAIL,
    [string]$AdminPassword = $env:ADMIN_BOOTSTRAP_PASSWORD,
    [int]$Rounds = 3
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\api-helpers.ps1"
$script:BaseUrl = $BaseUrl

if (-not $AdminEmail -or -not $AdminPassword) {
    Write-Host "Admin credentials are required to create the venue and event." -ForegroundColor Yellow
    Write-Host "Set ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD, or pass -AdminEmail / -AdminPassword." -ForegroundColor Yellow
    exit 2
}

# The body of each racing client. Self-contained because a background job does not
# inherit the functions defined in this file.
$holdJob = {
    param($Uri, $Token, $BodyJson, $FireAtTicks)

    # Spin until the agreed instant so both jobs dispatch together.
    $fireAt = [DateTime]::new($FireAtTicks, [DateTimeKind]::Utc)
    while ([DateTime]::UtcNow -lt $fireAt) { Start-Sleep -Milliseconds 2 }

    $sent = [DateTime]::UtcNow
    try {
        $response = Invoke-WebRequest -Method POST -Uri $Uri -Body $BodyJson `
            -ContentType 'application/json' `
            -Headers @{ Authorization = "Bearer $Token" } `
            -UseBasicParsing -ErrorAction Stop
        $status = [int]$response.StatusCode
        $raw = $response.Content
    }
    catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw }
        $status = [int]$webResponse.StatusCode
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $raw = $_.ErrorDetails.Message
        }
        else {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            $raw = $reader.ReadToEnd()
            $reader.Dispose()
        }
    }

    [pscustomobject]@{ Status = $status; Raw = $raw; SentAt = $sent }
}

function New-SmokeUser {
    param([string]$Prefix)
    $email = New-UniqueEmail $Prefix
    Invoke-Api POST '/auth/register' -Body @{
        name = 'Race Client'; email = $email; password = 'racepass123'
    } | Out-Null
    $login = Invoke-Api POST '/auth/login' -Body @{ email = $email; password = 'racepass123' }
    if ($login.Status -ne 200) { throw "could not log in as $email : $($login.Raw)" }
    return $login.Body.token
}

Write-Host "Concurrency check against $BaseUrl ($Rounds round(s))" -ForegroundColor White

Write-Step "Setup"
$health = Invoke-Api GET '/health'
Assert-That 'the database is connected' ($health.Body.database -eq 'connected') `
    "health said '$($health.Body.database)'"
if ($health.Body.database -ne 'connected') { exit (Write-Summary 'Concurrency check') }

$adminLogin = Invoke-Api POST '/auth/login' -Body @{ email = $AdminEmail; password = $AdminPassword }
Assert-Status 'admin login' $adminLogin 200
$adminToken = $adminLogin.Body.token

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$venue = Invoke-Api POST '/admin/venues' -Token $adminToken -Body @{
    name = "Race Arena $stamp"; address = '1 Race Road'; city = 'Testville'; totalCapacity = $Rounds
}
Assert-Status 'venue created' $venue 201
$venueId = $venue.Body.id

# One seat per round, so every round contends over a seat nobody has touched.
$layout = Invoke-Api POST "/admin/venues/$venueId/seats" -Token $adminToken -Body @{
    rows = @('A'); seatsPerRow = $Rounds; seatType = 'REGULAR'
}
Assert-Status 'seat layout created' $layout 201

$startTime = (Get-Date).ToUniversalTime().AddDays(30).ToString('yyyy-MM-ddTHH:mm:00')
$endTime = (Get-Date).ToUniversalTime().AddDays(30).AddHours(3).ToString('yyyy-MM-ddTHH:mm:00')
$event = Invoke-Api POST '/admin/events' -Token $adminToken -Body @{
    venueId = $venueId; title = "Race Event $stamp"; category = 'Concert'
    startTime = $startTime; endTime = $endTime; basePrice = '500.00'; status = 'PUBLISHED'
}
Assert-Status 'event created' $event 201
$eventId = $event.Body.event.id

$tokenA = New-SmokeUser 'race.a'
$tokenB = New-SmokeUser 'race.b'
Assert-That 'two client accounts are ready' ([bool]$tokenA -and [bool]$tokenB)

$seatMap = Invoke-Api GET "/events/$eventId/seats"
Assert-Status 'seat map loaded' $seatMap 200
$seats = @($seatMap.Body.seats)
Assert-That "$Rounds seat(s) available to contend over" ($seats.Count -ge $Rounds) `
    "found $($seats.Count)"

$holdUri = "$BaseUrl/events/$eventId/hold"

for ($round = 1; $round -le $Rounds; $round++) {
    $seat = $seats[$round - 1]
    Write-Step "Round $round - two clients racing for seat $($seat.row)$($seat.number)"

    $bodyJson = @{ eventSeatIds = @($seat.eventSeatId) } | ConvertTo-Json -Compress
    # Far enough ahead that both jobs are spun up and waiting before it arrives.
    $fireAt = [DateTime]::UtcNow.AddSeconds(3)

    $jobA = Start-Job -ScriptBlock $holdJob -ArgumentList $holdUri, $tokenA, $bodyJson, $fireAt.Ticks
    $jobB = Start-Job -ScriptBlock $holdJob -ArgumentList $holdUri, $tokenB, $bodyJson, $fireAt.Ticks

    $null = Wait-Job -Job $jobA, $jobB -Timeout 60
    $resultA = Receive-Job -Job $jobA
    $resultB = Receive-Job -Job $jobB
    Remove-Job -Job $jobA, $jobB -Force

    $statuses = @($resultA.Status, $resultB.Status)
    $gap = [Math]::Abs(($resultA.SentAt - $resultB.SentAt).TotalMilliseconds)

    Write-Host "   client A -> HTTP $($resultA.Status)" -ForegroundColor DarkGray
    Write-Host "   client B -> HTTP $($resultB.Status)" -ForegroundColor DarkGray
    Write-Host "   dispatched $([Math]::Round($gap, 1)) ms apart" -ForegroundColor DarkGray

    $created = @($statuses | Where-Object { $_ -eq 201 }).Count
    $conflicted = @($statuses | Where-Object { $_ -eq 409 }).Count

    Assert-That 'exactly one client got 201' ($created -eq 1) `
        "statuses were $($statuses -join ', ') - TWO 201s would mean the seat was sold twice"
    Assert-That 'the other got 409' ($conflicted -eq 1) `
        "statuses were $($statuses -join ', ')"

    $loser = if ($resultA.Status -eq 409) { $resultA } elseif ($resultB.Status -eq 409) { $resultB } else { $null }
    if ($loser) {
        $parsed = try { $loser.Raw | ConvertFrom-Json } catch { $null }
        Assert-That 'the loser is told SEATS_UNAVAILABLE' ($parsed.error -eq 'SEATS_UNAVAILABLE') `
            "body was $($loser.Raw)"
        Assert-That 'and which seat it lost' ([bool]$parsed.unavailableSeats)
    }

    # The database's own view is the real check: exactly one LOCKED seat.
    $after = Invoke-Api GET "/events/$eventId/seats"
    $seatAfter = @($after.Body.seats | Where-Object { $_.eventSeatId -eq $seat.eventSeatId })[0]
    Assert-That 'the seat ended up LOCKED exactly once' ($seatAfter.status -eq 'LOCKED') `
        "seat status was '$($seatAfter.status)'"
}

exit (Write-Summary 'Concurrency check')
