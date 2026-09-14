<#
.SYNOPSIS
    Static consistency checks for the no-build frontend.

.DESCRIPTION
    With no bundler and no compiler, a typo in an import name or an element id is
    only discovered by opening the page. This script catches that class of error
    without a browser:

      1. every named import resolves to a real export in the target module;
      2. every module referenced by an import or a <script src> exists on disk;
      3. every element id an entry script looks up exists in the page that loads it;
      4. every page provides the #site-header / #site-footer that nav.js fills;
      5. local .html links point at files that exist (reported as INFO while the
         remaining pages are still being built).

    It is regex-based, not a parser, so it is a smoke test rather than a type
    checker - but it covers the mistakes that actually happen when editing several
    files that reference each other by string.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\check-frontend-refs.ps1
#>
[CmdletBinding()]
param(
    [string]$FrontendPath
)

$ErrorActionPreference = 'Stop'

# Resolved here rather than as a param default: under [CmdletBinding()], defaults are
# evaluated before $PSScriptRoot is populated (PowerShell 5.1), so a default of
# (Join-Path $PSScriptRoot ...) fails with "argument is an empty string".
if (-not $FrontendPath) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot }
    elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path }
    else { (Get-Location).Path }

    $candidate = Join-Path $scriptDir '..\Frontend'
    $FrontendPath = if (Test-Path $candidate) { $candidate } else { Join-Path (Get-Location) 'Frontend' }
}

$root = (Resolve-Path $FrontendPath).Path

# Explicitly script-scoped so the counters the helper functions increment are the
# same variables the summary reads, however the script is invoked.
$script:problems = 0
$script:notes = 0

function Write-Problem {
    param([string]$Message)
    $script:problems++
    Write-Host "  FAIL  $Message" -ForegroundColor Red
}

function Write-Ok {
    param([string]$Message)
    Write-Host "  ok    $Message" -ForegroundColor DarkGray
}

function Write-Note {
    param([string]$Message)
    $script:notes++
    Write-Host "  note  $Message" -ForegroundColor Yellow
}

Write-Host "Checking frontend at $root" -ForegroundColor White

$jsFiles = Get-ChildItem -Path $root -Recurse -Filter *.js -File
$htmlFiles = Get-ChildItem -Path $root -Recurse -Filter *.html -File

if ($jsFiles.Count -eq 0 -and $htmlFiles.Count -eq 0) {
    Write-Host "Nothing to check - no .js or .html files found." -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------- exports
$exports = @{}
foreach ($file in $jsFiles) {
    $text = Get-Content $file.FullName -Raw
    $names = New-Object System.Collections.Generic.HashSet[string]

    foreach ($m in [regex]::Matches($text, '(?m)^\s*export\s+(?:async\s+)?function\s+([A-Za-z0-9_$]+)')) {
        [void]$names.Add($m.Groups[1].Value)
    }
    foreach ($m in [regex]::Matches($text, '(?m)^\s*export\s+(?:const|let|var)\s+([A-Za-z0-9_$]+)')) {
        [void]$names.Add($m.Groups[1].Value)
    }
    foreach ($m in [regex]::Matches($text, '(?m)^\s*export\s+class\s+([A-Za-z0-9_$]+)')) {
        [void]$names.Add($m.Groups[1].Value)
    }
    # Export lists and re-exports: `export { a, b as c };`
    foreach ($m in [regex]::Matches($text, '(?m)^\s*export\s*\{([^}]*)\}')) {
        foreach ($piece in $m.Groups[1].Value -split ',') {
            $parts = $piece -split '\s+as\s+'
            # `a as b` exports the name b; a bare `a` exports a.
            $exported = ($parts[$parts.Count - 1]).Trim()
            if ($exported) { [void]$names.Add($exported) }
        }
    }
    $exports[$file.FullName] = $names
}

# ---------------------------------------------------------------- imports
Write-Host ""
Write-Host "1-2. Imports resolve to real exports" -ForegroundColor Cyan
foreach ($file in $jsFiles) {
    $text = Get-Content $file.FullName -Raw
    $matches = [regex]::Matches($text, 'import\s*\{([^}]*)\}\s*from\s*[''"]([^''"]+)[''"]')

    foreach ($m in $matches) {
        $names = $m.Groups[1].Value -split ',' |
            ForEach-Object { ($_ -split '\s+as\s+')[0].Trim() } |
            Where-Object { $_ }
        $relative = $m.Groups[2].Value

        $targetPath = Join-Path $file.DirectoryName $relative
        if (-not (Test-Path $targetPath)) {
            Write-Problem "$($file.Name) imports '$relative', which does not exist"
            continue
        }
        $targetFull = (Resolve-Path $targetPath).Path

        foreach ($name in $names) {
            if ($exports[$targetFull] -and $exports[$targetFull].Contains($name)) {
                Write-Ok "$($file.Name): $name <- $relative"
            }
            else {
                Write-Problem "$($file.Name) imports '$name' from '$relative', which does not export it"
            }
        }
    }
}

# ------------------------------------------------------- element ids per page
Write-Host ""
Write-Host "3-4. Entry scripts only look up ids their page defines" -ForegroundColor Cyan
foreach ($html in $htmlFiles) {
    $htmlText = Get-Content $html.FullName -Raw

    $ids = New-Object System.Collections.Generic.HashSet[string]
    foreach ($m in [regex]::Matches($htmlText, 'id\s*=\s*"([^"]+)"')) {
        [void]$ids.Add($m.Groups[1].Value)
    }

    # nav.js fills these on every page.
    foreach ($required in @('site-header', 'site-footer')) {
        if ($ids.Contains($required)) { Write-Ok "$($html.Name) provides #$required" }
        else { Write-Problem "$($html.Name) is missing #$required, so the shared shell cannot render" }
    }

    foreach ($scriptMatch in [regex]::Matches($htmlText, '<script[^>]*src\s*=\s*"([^"]+)"')) {
        $src = $scriptMatch.Groups[1].Value
        $scriptPath = Join-Path $html.DirectoryName $src
        if (-not (Test-Path $scriptPath)) {
            Write-Problem "$($html.Name) loads '$src', which does not exist"
            continue
        }

        $scriptText = Get-Content $scriptPath -Raw

        # Ids the script renders itself (inside template literals) are just as valid as
        # ids in the HTML - a page that builds its own controls is normal. The check
        # that matters is "looked up but defined nowhere".
        $definedIds = New-Object System.Collections.Generic.HashSet[string]
        foreach ($id in $ids) { [void]$definedIds.Add($id) }
        foreach ($m in [regex]::Matches($scriptText, 'id="([A-Za-z0-9_-]+)"')) {
            [void]$definedIds.Add($m.Groups[1].Value)
        }

        $referenced = New-Object System.Collections.Generic.HashSet[string]

        foreach ($m in [regex]::Matches($scriptText, "getElementById\(\s*'([^']+)'")) {
            [void]$referenced.Add($m.Groups[1].Value)
        }
        foreach ($m in [regex]::Matches($scriptText, "querySelector\(\s*'#([A-Za-z0-9_-]+)'")) {
            [void]$referenced.Add($m.Groups[1].Value)
        }
        # showStatus('some-id', ...) targets an element by id too.
        foreach ($m in [regex]::Matches($scriptText, "showStatus\(\s*'([^']+)'")) {
            [void]$referenced.Add($m.Groups[1].Value)
        }
        # for (const id of ['a', 'b']) { ... querySelector(`#${id}`) } - literal lists.
        foreach ($m in [regex]::Matches($scriptText, "of\s*\[\s*((?:'[^']+'\s*,?\s*)+)\]")) {
            foreach ($lit in [regex]::Matches($m.Groups[1].Value, "'([^']+)'")) {
                $candidate = $lit.Groups[1].Value
                # Only treat it as an id if the page actually defines something like it.
                if ($candidate -match '^[A-Za-z0-9_-]+$' -and $ids.Contains($candidate)) {
                    [void]$referenced.Add($candidate)
                }
            }
        }

        foreach ($id in $referenced) {
            if ($ids.Contains($id)) {
                Write-Ok "$($html.Name) defines #$id used by $(Split-Path $src -Leaf)"
            }
            elseif ($definedIds.Contains($id)) {
                Write-Ok "$(Split-Path $src -Leaf) renders #$id itself"
            }
            else {
                Write-Problem "$(Split-Path $src -Leaf) looks up #$id, which $($html.Name) does not define"
            }
        }
    }
}

# ---------------------------------------------------------------- page links
Write-Host ""
Write-Host "5. Local page links" -ForegroundColor Cyan
$seenLinks = @{}
foreach ($html in $htmlFiles) {
    $htmlText = Get-Content $html.FullName -Raw
    foreach ($m in [regex]::Matches($htmlText, 'href\s*=\s*"([^"#?]+\.html)[^"]*"')) {
        $target = Join-Path $html.DirectoryName $m.Groups[1].Value
        $key = "$($html.Name) -> $($m.Groups[1].Value)"
        if ($seenLinks.ContainsKey($key)) { continue }
        $seenLinks[$key] = $true
        if (Test-Path $target) { Write-Ok $key }
        else { Write-Note "$key does not exist yet" }
    }
}

# nav.js builds links in JS, so check those separately.
$navPath = Join-Path $root 'js\nav.js'
if (Test-Path $navPath) {
    $navText = Get-Content $navPath -Raw
    foreach ($m in [regex]::Matches($navText, "href:\s*'([^']+\.html)'")) {
        $target = Join-Path $root $m.Groups[1].Value
        if (Test-Path $target) { Write-Ok "nav.js -> $($m.Groups[1].Value)" }
        else { Write-Note "nav.js links to $($m.Groups[1].Value), which does not exist yet" }
    }
    foreach ($m in [regex]::Matches($navText, "resolve\('([^']+\.html)'\)")) {
        $target = Join-Path $root $m.Groups[1].Value
        if (Test-Path $target) { Write-Ok "nav.js -> $($m.Groups[1].Value)" }
        else { Write-Note "nav.js links to $($m.Groups[1].Value), which does not exist yet" }
    }
}

Write-Host ""
Write-Host "----------------------------------------------------------------"
if ($script:problems -eq 0) {
    Write-Host "Frontend reference check passed. $($script:notes) note(s) about pages not built yet." -ForegroundColor Green
}
else {
    Write-Host "Frontend reference check FAILED: $($script:problems) problem(s), $($script:notes) note(s)." -ForegroundColor Red
}
Write-Host "----------------------------------------------------------------"
exit $script:problems
