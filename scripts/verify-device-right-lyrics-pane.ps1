param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [switch]$KeepExpanded
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
    $remotePath = "/sdcard/amenhancer-right-lyrics-check.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", $remotePath) | Out-Null
    return ((Invoke-Adb @("exec-out", "cat", $remotePath)) -join [Environment]::NewLine)
}

function Get-NodesById {
    param(
        [Parameter(Mandatory = $true)][string]$Xml,
        [Parameter(Mandatory = $true)][string]$ResourceId
    )

    $escapedId = [regex]::Escape($ResourceId)
    $pattern = '<node\b[^>]*resource-id="' + $escapedId + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>'
    foreach ($match in [regex]::Matches($Xml, $pattern)) {
        [pscustomobject]@{
            X1 = [int]$match.Groups[1].Value
            Y1 = [int]$match.Groups[2].Value
            X2 = [int]$match.Groups[3].Value
            Y2 = [int]$match.Groups[4].Value
        }
    }
}

function Get-BroadMiniPlayer {
    param([Parameter(Mandatory = $true)][string]$Xml)

    $matches = Get-NodesById $Xml "com.apple.android.music:id/mini_player_title"
    return $matches |
        Sort-Object { ($_.X2 - $_.X1) * ($_.Y2 - $_.Y1) } -Descending |
        Select-Object -First 1
}

$expanded = $false
try {
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

    $miniPlayer = Get-BroadMiniPlayer $collapsedUi
    if ($null -eq $miniPlayer) {
        throw "RED: no broad Apple Music mini-player title was found. Start a song, then rerun this check."
    }
    $tapX = [int](($miniPlayer.X1 + $miniPlayer.X2) / 2)
    $tapY = [int](($miniPlayer.Y1 + $miniPlayer.Y2) / 2)
    Invoke-Adb @("shell", "input", "tap", "$tapX", "$tapY") | Out-Null
    Start-Sleep -Seconds 3
    $expanded = $true

    $expandedUi = Get-UiHierarchy
    $lyricsHostPresent = $expandedUi -match 'content-desc="AM\+\+ lyrics pane"'
    if (-not $lyricsHostPresent) {
        throw "RED: full two-pane lyrics host was not present after expansion."
    }

    $rightPaneStart = [int]($displayWidth / 2)
    $headerIds = @(
        "com.apple.android.music:id/current_player_item",
        "com.apple.android.music:id/lyrics_thumbnail_container",
        "com.apple.android.music:id/text_metadata_container"
    )
    $rightHeaderNodes = foreach ($id in $headerIds) {
        Get-NodesById $expandedUi $id | Where-Object { $_.X1 -ge $rightPaneStart }
    }
    $rightHeaderPresent = @($rightHeaderNodes).Count -gt 0

    $lyricsContent = Get-NodesById $expandedUi "com.apple.android.music:id/lyrics_main_content" |
        Where-Object { $_.X1 -ge $rightPaneStart } |
        Select-Object -First 1
    if ($null -eq $lyricsContent) {
        throw "RED: right lyrics RecyclerView was not found."
    }
    # The modified layout removes the 158px current-player header, leaving
    # RecyclerView directly below the 84px system/top inset on this device.
    $maximumTop = [int]($displayHeight * 0.05)
    $lyricsStartIsHighEnough = $lyricsContent.Y1 -le $maximumTop

    if ($rightHeaderPresent -or -not $lyricsStartIsHighEnough) {
        throw "RED: right lyrics pane still differs from modified layout (rightHeaderPresent=$rightHeaderPresent; lyricsTop=$($lyricsContent.Y1); maximumTop=$maximumTop)."
    }

    Write-Output "GREEN: right lyrics pane has no duplicate song header and starts at the modified top region."
}
finally {
    if ($expanded -and -not $KeepExpanded) {
        Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
    }
}
