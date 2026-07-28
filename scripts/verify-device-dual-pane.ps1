param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $AdbPath)) {
    $AdbPath = (Get-Command adb -ErrorAction Stop).Source
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Pass -Serial or set ANDROID_SERIAL."
}

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-UiHierarchy {
    $remotePath = "/sdcard/amenhancer-dual-pane-check.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", $remotePath) | Out-Null
    return ((Invoke-Adb @("exec-out", "cat", $remotePath)) -join [Environment]::NewLine)
}

Invoke-Adb @("shell", "am", "force-stop", "com.apple.android.music") | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.apple.android.music/.onboarding.activities.SplashActivity") | Out-Null
Start-Sleep -Seconds 3

$collapsedUi = Get-UiHierarchy
if ($collapsedUi -notmatch 'rotation="1"') {
    throw "RED: Apple Music is not in landscape. Rotate/unlock the tablet, then rerun this check."
}

$display = [regex]::Match($collapsedUi, 'bounds="\[0,0\]\[(\d+),(\d+)\]"')
if (-not $display.Success) {
    throw "RED: could not determine display bounds from the Apple Music hierarchy."
}
$displayWidth = [int]$display.Groups[1].Value
$displayHeight = [int]$display.Groups[2].Value

$miniPlayer = [regex]::Match(
    $collapsedUi,
    'resource-id="com\.apple\.android\.music:id/player_sheet_container"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)
if (-not $miniPlayer.Success) {
    throw "RED: no Apple Music mini-player was found. Start a song, then rerun this check."
}

$left = [int]$miniPlayer.Groups[1].Value
$top = [int]$miniPlayer.Groups[2].Value
$right = [int]$miniPlayer.Groups[3].Value
$bottom = [int]$miniPlayer.Groups[4].Value
$x = [int](($left + $right) / 2)
$y = [int](($top + $bottom) / 2)
Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
Start-Sleep -Seconds 3

$expandedUi = Get-UiHierarchy
$lyricsHostPresent = $expandedUi -match 'content-desc="AM\+\+ lyrics pane"'
$bottomTabsPresent = $expandedUi -match 'resource-id="com\.apple\.android\.music:id/bottom_navigation_tabs_frame"'
$playerContainer = [regex]::Match(
    $expandedUi,
    'resource-id="com\.apple\.android\.music:id/player_container"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)
$playerHost = [regex]::Match(
    $expandedUi,
    'resource-id="com\.apple\.android\.music:id/player_fragments_host"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)
$lyricsHost = [regex]::Match(
    $expandedUi,
    'content-desc="AM\+\+ lyrics pane"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)

if (-not $lyricsHostPresent -or $bottomTabsPresent -or -not $playerContainer.Success -or -not $playerHost.Success -or -not $lyricsHost.Success) {
    $details = @(
        "lyricsHostPresent=$lyricsHostPresent",
        "bottomTabsPresent=$bottomTabsPresent",
        "playerContainerPresent=$($playerContainer.Success)",
        "playerHostPresent=$($playerHost.Success)"
    ) -join "; "
    throw "RED: dual-pane regression detected ($details)."
}

$containerMatchesScreen =
    [int]$playerContainer.Groups[1].Value -eq 0 -and
    [int]$playerContainer.Groups[2].Value -eq 0 -and
    [int]$playerContainer.Groups[3].Value -eq $displayWidth -and
    [int]$playerContainer.Groups[4].Value -eq $displayHeight
$minimumPaneWidth = [int]($displayWidth * 0.35)
$leftPaneWidth = [int]$playerHost.Groups[3].Value - [int]$playerHost.Groups[1].Value
$rightPaneWidth = [int]$lyricsHost.Groups[3].Value - [int]$lyricsHost.Groups[1].Value
$splitUsesBothSides =
    $leftPaneWidth -ge $minimumPaneWidth -and
    $rightPaneWidth -ge $minimumPaneWidth -and
    [int]$playerHost.Groups[1].Value -lt [int]($displayWidth / 2) -and
    [int]$lyricsHost.Groups[1].Value -gt [int]($displayWidth / 2)

if (-not $containerMatchesScreen -or -not $splitUsesBothSides) {
    throw "RED: player did not occupy a full-width two-pane layout (containerMatchesScreen=$containerMatchesScreen; splitUsesBothSides=$splitUsesBothSides)."
}

Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
Start-Sleep -Seconds 2
$restoredUi = Get-UiHierarchy
$bottomTabsRestored = $restoredUi -match 'resource-id="com\.apple\.android\.music:id/bottom_navigation_tabs_frame"'
$miniPlayerRestored = $restoredUi -match 'resource-id="com\.apple\.android\.music:id/player_sheet_container"'
if (-not $bottomTabsRestored -or -not $miniPlayerRestored) {
    throw "RED: bottom navigation did not restore after collapse (bottomTabsRestored=$bottomTabsRestored; miniPlayerRestored=$miniPlayerRestored)."
}

Write-Output "GREEN: full-screen two-pane player expanded and normal bottom navigation restored after collapse."
