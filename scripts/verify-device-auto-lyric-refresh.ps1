param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [ValidateSet("Auto", "Manual")][string]$TransitionMode = "Auto",
    [double]$SeekFraction = 0.985,
    [int]$TransitionTimeoutSeconds = 25,
    [int]$SettleMilliseconds = 3500,
    [int]$OverlapThreshold = 2
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
    $remotePath = "/sdcard/amenhancer-auto-lyric-refresh.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", $remotePath) | Out-Null
    return ((Invoke-Adb @("exec-out", "cat", $remotePath)) -join [Environment]::NewLine)
}

function Get-Snapshot {
    [xml]$xml = Get-UiHierarchy
    $nodes = $xml.SelectNodes("//node")
    $seek = $nodes |
        Where-Object { $_.GetAttribute("resource-id") -eq "com.apple.android.music:id/progress" } |
        Select-Object -First 1
    $title = $nodes |
        Where-Object {
            $_.GetAttribute("resource-id") -eq "com.apple.android.music:id/title" -and
            $_.GetAttribute("selected") -eq "true"
        } |
        Select-Object -First 1
    $lyrics = @(
        $nodes |
            Where-Object {
                $_.GetAttribute("resource-id") -eq "com.apple.android.music:id/song_lyrics_line" -and
                $_.GetAttribute("text")
            } |
            ForEach-Object { $_.GetAttribute("text") }
    )

    return [pscustomobject]@{
        Title = if ($null -ne $title) { $title.GetAttribute("text") } else { "" }
        SeekBounds = if ($null -ne $seek) { $seek.GetAttribute("bounds") } else { "" }
        SeekDescription = if ($null -ne $seek) { $seek.GetAttribute("content-desc") } else { "" }
        Lyrics = $lyrics
    }
}

function Get-MediaDescription {
    $line = Invoke-Adb @("shell", "dumpsys", "media_session") |
        Where-Object { $_ -match "metadata: size=.*description=" } |
        Select-Object -Last 1
    if (-not $line) {
        return ""
    }
    return ($line -replace "^.*description=", "").Trim()
}

function Get-OverlapCount {
    param(
        [Parameter(Mandatory = $true)][string[]]$Left,
        [Parameter(Mandatory = $true)][string[]]$Right
    )

    $set = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($line in $Left) {
        [void]$set.Add($line)
    }
    $count = 0
    foreach ($line in $Right) {
        if ($set.Contains($line)) {
            $count += 1
        }
    }
    return $count
}

function Wait-ForMediaChange {
    param([Parameter(Mandatory = $true)][string]$PreviousDescription)

    $deadline = [DateTime]::UtcNow.AddSeconds($TransitionTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $candidate = Get-MediaDescription
        if ($candidate -and $candidate -ne $PreviousDescription) {
            return $candidate
        }
    }
    return $PreviousDescription
}

$playbackStarted = $false
try {
    Invoke-Adb @("shell", "cmd", "statusbar", "collapse") | Out-Null
    Start-Sleep -Milliseconds 500

    $before = Get-Snapshot
    $beforeMedia = Get-MediaDescription
    if (-not $beforeMedia) {
        throw "RED: Apple Music has no active media metadata."
    }
    if (-not $before.SeekBounds -or $before.Lyrics.Count -lt $OverlapThreshold) {
        throw "RED: expand the dual-pane player on a song with visible lyrics, then rerun."
    }

    Write-Output "BASE_MEDIA $beforeMedia"
    Write-Output "BASE_TITLE $($before.Title)"
    Write-Output "BASE_SEEK $($before.SeekDescription)"
    Write-Output "BASE_LYRICS $($before.Lyrics -join ' | ')"

    $nearEnd = $before
    if ($TransitionMode -eq "Auto") {
        if ($SeekFraction -le 0.0 -or $SeekFraction -ge 1.0) {
            throw "SeekFraction must be greater than 0 and less than 1."
        }
        if ($before.SeekBounds -notmatch "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") {
            throw "RED: unexpected seek bounds '$($before.SeekBounds)'."
        }
        $left = [int]$Matches[1]
        $top = [int]$Matches[2]
        $right = [int]$Matches[3]
        $bottom = [int]$Matches[4]
        $tapX = [int][Math]::Round($left + (($right - $left) * $SeekFraction))
        $tapY = [int][Math]::Round(($top + $bottom) / 2)
        Invoke-Adb @("shell", "input", "tap", "$tapX", "$tapY") | Out-Null
        Start-Sleep -Milliseconds 1200
        $nearEnd = Get-Snapshot
        Write-Output "SEEKED $($nearEnd.SeekDescription)"
        Write-Output "SEEKED_LYRICS $($nearEnd.Lyrics -join ' | ')"
        Invoke-Adb @("shell", "cmd", "media_session", "dispatch", "play") | Out-Null
        $playbackStarted = $true
    } else {
        Invoke-Adb @("shell", "cmd", "media_session", "dispatch", "next") | Out-Null
    }

    $afterMedia = Wait-ForMediaChange $beforeMedia
    if ($afterMedia -eq $beforeMedia) {
        throw "RED: $TransitionMode transition did not occur within $TransitionTimeoutSeconds seconds."
    }

    Start-Sleep -Milliseconds $SettleMilliseconds
    Invoke-Adb @("shell", "cmd", "media_session", "dispatch", "pause") | Out-Null
    $playbackStarted = $false
    Start-Sleep -Milliseconds 500
    $after = Get-Snapshot
    $baseOverlap = Get-OverlapCount $before.Lyrics $after.Lyrics
    $nearEndOverlap = Get-OverlapCount $nearEnd.Lyrics $after.Lyrics
    $maximumOverlap = [Math]::Max($baseOverlap, $nearEndOverlap)

    Write-Output "AFTER_MEDIA $afterMedia"
    Write-Output "AFTER_TITLE $($after.Title)"
    Write-Output "AFTER_SEEK $($after.SeekDescription)"
    Write-Output "AFTER_LYRICS $($after.Lyrics -join ' | ')"
    Write-Output "OVERLAP base=$baseOverlap nearEnd=$nearEndOverlap max=$maximumOverlap"

    if ($maximumOverlap -ge $OverlapThreshold) {
        throw "RED: $TransitionMode media changed, but the right lyrics still belong to the previous track."
    }

    Write-Output "GREEN: $TransitionMode media and right lyrics both changed."
}
finally {
    if ($playbackStarted) {
        Invoke-Adb @("shell", "cmd", "media_session", "dispatch", "pause") | Out-Null
    }
}
