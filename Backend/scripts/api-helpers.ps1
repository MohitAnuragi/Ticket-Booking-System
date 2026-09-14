# =============================================================================
# Shared helpers for the verification scripts in this folder.
# Dot-source it:  . "$PSScriptRoot\api-helpers.ps1"
#
# Invoke-Api returns the status code AND the parsed body for BOTH success and
# failure, because half of what these scripts check is that failures come back
# with the right status and error code - a helper that threw on a 409 would make
# the interesting cases unassertable.
# =============================================================================

$script:Passed = 0
$script:Failed = 0

function Invoke-Api {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$BaseUrl = $script:BaseUrl,
        $Body,
        [string]$Token
    )

    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }

    $params = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ContentType = 'application/json'
    }
    if ($null -ne $Body) { $params['Body'] = ($Body | ConvertTo-Json -Depth 8 -Compress) }

    try {
        $response = Invoke-WebRequest @params -UseBasicParsing -ErrorAction Stop
        $status = [int]$response.StatusCode
        $raw = $response.Content
    }
    catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) {
            # No HTTP response at all: refused connection, DNS, TLS. Almost always
            # "the server is not running", so say that rather than surfacing a raw
            # WebException.
            throw "Could not reach $($params.Uri) - is the server running? ($($_.Exception.Message))"
        }

        $status = [int]$webResponse.StatusCode
        # PowerShell 7 puts the error body here; 5.1 needs the stream read.
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $raw = $_.ErrorDetails.Message
        }
        else {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            $raw = $reader.ReadToEnd()
            $reader.Dispose()
        }
    }

    $parsed = $null
    if ($raw) { try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $raw } }

    return [pscustomobject]@{
        Status = $status
        Body   = $parsed
        Raw    = $raw
    }
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "== $Message" -ForegroundColor Cyan
}

function Assert-That {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][bool]$Condition,
        [string]$Detail
    )
    if ($Condition) {
        $script:Passed++
        Write-Host "   PASS  $Description" -ForegroundColor Green
    }
    else {
        $script:Failed++
        Write-Host "   FAIL  $Description" -ForegroundColor Red
        if ($Detail) { Write-Host "         $Detail" -ForegroundColor DarkGray }
    }
}

function Assert-Status {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][int]$Expected
    )
    Assert-That -Description "$Description (HTTP $Expected)" `
        -Condition ($Response.Status -eq $Expected) `
        -Detail "got HTTP $($Response.Status): $($Response.Raw)"
}

function New-UniqueEmail {
    param([string]$Prefix = 'smoke')
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    return "$Prefix+$stamp@example.com"
}

function Write-Summary {
    param([string]$Title)
    Write-Host ""
    Write-Host "----------------------------------------------------------------"
    if ($script:Failed -eq 0) {
        Write-Host "${Title}: $($script:Passed) checks passed." -ForegroundColor Green
    }
    else {
        Write-Host "${Title}: $($script:Passed) passed, $($script:Failed) FAILED." -ForegroundColor Red
    }
    Write-Host "----------------------------------------------------------------"
    return $script:Failed
}
