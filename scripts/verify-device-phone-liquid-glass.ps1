param(
    [string]$DeviceId = "",
    [int]$WaitSeconds = 4
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb.exe was not found at $adb"
}

$deviceArgs = if ($DeviceId) { @("-s", $DeviceId) } else { @() }
& $adb @deviceArgs logcat -c | Out-Null
& $adb @deviceArgs shell am force-stop com.apple.android.music | Out-Null
& $adb @deviceArgs shell am start -W -n com.apple.android.music/.onboarding.activities.SplashActivity | Out-Null
& $adb @deviceArgs shell cmd statusbar collapse | Out-Null
Start-Sleep -Seconds $WaitSeconds

$processId = (@(& $adb @deviceArgs shell pidof com.apple.android.music) -join "").Trim()
$logs = & $adb @deviceArgs logcat -d -v brief
$logText = $logs -join "`n"
$hardwareBitmapCrash = $logs | Select-String -SimpleMatch "Software rendering doesn't support hardware bitmaps"
$blurStack = $logs | Select-String -SimpleMatch "eightbitlab.com.blurview.PreDrawBlurController"
$fatalMusicCrash = $logText -match "(?s)FATAL EXCEPTION:.{0,600}Process: com\.apple\.android\.music"
$focus = (& $adb @deviceArgs shell dumpsys window | Select-String "mCurrentFocus=" | Select-Object -First 1).Line

if ($hardwareBitmapCrash -and $blurStack) {
    Write-Output "RED: Apple Music crashed in BlurView software snapshot capture"
    exit 1
}
if ($fatalMusicCrash) {
    Write-Output "RED: Apple Music emitted a fatal exception during liquid-glass cold start"
    exit 1
}
if (-not $processId) {
    Write-Output "RED: Apple Music process is not alive after cold start"
    exit 1
}
if (-not $focus -or $focus -notmatch "com\.apple\.android\.music") {
    Write-Output "RED: Apple Music is not the focused app after cold start (focus=$focus)"
    exit 1
}

Write-Output "GREEN: Apple Music remained alive and focused without a fatal exception (pid=$processId)"
exit 0
