<#
.SYNOPSIS
    Checks that the frontend and the Kotlin DTOs agree on field names.

.DESCRIPTION
    The frontend and backend are separate languages with no shared schema, so a
    renamed DTO field is only discovered when a request fails at runtime. Worse, every
    request DTO field is nullable with a default, so a misspelled key does not error —
    it arrives as null and surfaces as a confusing "x is required" 400.

    Checked here:
      1. every key in a frontend request payload is a field on some DTO;
      2. every query parameter the frontend sends is read by some controller;
      3. every property the frontend reads off an API object exists on some DTO
         (reported as a note, since local objects share those receiver names).

    Regex-based, so it is a smoke test rather than a type checker. It cannot tell
    which DTO a given call site returns — that pairing still needs review by hand.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\check-api-contract.ps1
#>
[CmdletBinding()]
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'

# Resolved in the body: under [CmdletBinding()], param defaults are evaluated before
# $PSScriptRoot is populated in PowerShell 5.1.
if (-not $Root) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
    $candidate = Join-Path $scriptDir '..'
    $Root = if (Test-Path (Join-Path $candidate 'Frontend')) { $candidate } else { (Get-Location).Path }
}
$Root = (Resolve-Path $Root).Path

$frontend = Join-Path $Root 'Frontend'
$dtoDir = Join-Path $Root 'Backend\src\main\kotlin\com\ticketbooking\dto'
$controllerDir = Join-Path $Root 'Backend\src\main\kotlin\com\ticketbooking\controller'

$script:problems = 0
$script:notes = 0

function Write-Ok { param($m) Write-Host "  ok    $m" -ForegroundColor DarkGray }
function Write-Problem { param($m) $script:problems++; Write-Host "  FAIL  $m" -ForegroundColor Red }
function Write-Note { param($m) $script:notes++; Write-Host "  note  $m" -ForegroundColor Yellow }

# ---------------------------------------------------------------- DTO fields
$dtoFields = New-Object System.Collections.Generic.HashSet[string]
Get-ChildItem $dtoDir -Filter *.kt | ForEach-Object {
    foreach ($m in [regex]::Matches((Get-Content $_.FullName -Raw), '(?m)^\s*val\s+([A-Za-z0-9_]+)\s*:')) {
        [void]$dtoFields.Add($m.Groups[1].Value)
    }
}
Write-Host "Serializable DTO fields: $($dtoFields.Count)" -ForegroundColor White

# ------------------------------------------------------- controller query params
$queryParams = New-Object System.Collections.Generic.HashSet[string]
Get-ChildItem $controllerDir -Filter *.kt | ForEach-Object {
    foreach ($m in [regex]::Matches((Get-Content $_.FullName -Raw), 'query(?:Parameters)?\["([A-Za-z0-9_]+)"\]')) {
        [void]$queryParams.Add($m.Groups[1].Value)
    }
}

# ---------------------------------------------------------------- 1. payloads
Write-Host ""
Write-Host "1. Request payload keys exist on a DTO" -ForegroundColor Cyan
Get-ChildItem $frontend -Recurse -Filter *.js | ForEach-Object {
    $text = Get-Content $_.FullName -Raw
    foreach ($m in [regex]::Matches($text, '(?s)(?:const payload|const body)\s*=\s*\{(.*?)\n\s*\};')) {
        # Both `key: value` and shorthand `key,` forms.
        $keys = @()
        foreach ($k in [regex]::Matches($m.Groups[1].Value, '(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[:,]')) {
            $keys += $k.Groups[1].Value
        }
        foreach ($key in ($keys | Select-Object -Unique)) {
            if ($dtoFields.Contains($key)) { Write-Ok "$($_.Name): $key" }
            else { Write-Problem "$($_.Name): payload key '$key' is not a field on any DTO" }
        }
    }
}

# ------------------------------------------------------------ 2. query params
Write-Host ""
Write-Host "2. Query parameters are read by a controller" -ForegroundColor Cyan
Write-Host "     backend accepts: $((($queryParams | Sort-Object) -join ', '))" -ForegroundColor DarkGray
$apiPath = Join-Path $frontend 'js\api.js'
foreach ($m in [regex]::Matches((Get-Content $apiPath -Raw), '(?s)query:\s*\{(.*?)\n\s*\}')) {
    # Line-leading only: otherwise `eventId: fields.eventId.value,` would also yield
    # "value" as though it were a key.
    foreach ($k in [regex]::Matches($m.Groups[1].Value, '(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[:,]')) {
        $key = $k.Groups[1].Value
        if ($queryParams.Contains($key)) { Write-Ok "api.js sends '$key'" }
        else { Write-Problem "api.js sends query parameter '$key', which no controller reads" }
    }
}
Get-ChildItem (Join-Path $frontend 'admin') -Filter *.js | ForEach-Object {
    $t = Get-Content $_.FullName -Raw
    foreach ($m in [regex]::Matches($t, '(?s)list(?:Bookings|Events)\(\{(.*?)\}\)')) {
        foreach ($k in [regex]::Matches($m.Groups[1].Value, '(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[:,]')) {
            $key = $k.Groups[1].Value
            if ($queryParams.Contains($key)) { Write-Ok "$($_.Name): filter '$key'" }
            else { Write-Problem "$($_.Name): filter '$key' is not read by any controller" }
        }
    }
}

# -------------------------------------------------------- 3. property reads
Write-Host ""
Write-Host "3. Properties read off API objects exist on a DTO" -ForegroundColor Cyan
# Receiver names that hold API responses in this codebase.
$receivers = 'booking|venue|result|created|updated|summary|auth|detail|currentEvent|lastPage'
# Shared with DOM/JS members, so a match on these proves nothing either way.
$ignore = @('length','value','id','name','status','type','row','number','price','title','city','total','limit','offset','textContent','innerHTML','classList','dataset','disabled','hidden','elements','style','children','key','target','preventDefault','html')

Get-ChildItem $frontend -Recurse -Filter *.js | ForEach-Object {
    $text = Get-Content $_.FullName -Raw
    foreach ($m in [regex]::Matches($text, "\b($receivers)\.([A-Za-z_][A-Za-z0-9_]*)")) {
        $prop = $m.Groups[2].Value
        if ($ignore -contains $prop -or $dtoFields.Contains($prop)) { continue }
        Write-Note "$($_.Name): $($m.Groups[1].Value).$prop is not a DTO field (local object?)"
    }
}

Write-Host ""
Write-Host "----------------------------------------------------------------"
if ($script:problems -eq 0) {
    Write-Host "API contract check passed. $($script:notes) note(s) to eyeball." -ForegroundColor Green
}
else {
    Write-Host "API contract check FAILED: $($script:problems) problem(s)." -ForegroundColor Red
}
Write-Host "----------------------------------------------------------------"
exit $script:problems
