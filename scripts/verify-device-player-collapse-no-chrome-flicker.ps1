param(
    [string]$Device = $env:ANDROID_SERIAL
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at $adb"
}
if ([string]::IsNullOrWhiteSpace($Device)) {
    throw "Pass -Device or set ANDROID_SERIAL."
}

$remote = "/data/local/tmp/am-player-collapse.mp4"
$local = Join-Path $env:TEMP "am-player-collapse.mp4"

& $adb -s $Device shell am force-stop com.apple.android.music
& $adb -s $Device shell monkey -p com.apple.android.music -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 5
$collapsedBefore = Join-Path $env:TEMP "am-player-collapsed-before.png"
$expanded = Join-Path $env:TEMP "am-player-expanded.png"
$collapsedAfter = Join-Path $env:TEMP "am-player-collapsed-after.png"
& $adb -s $Device exec-out screencap -p > $collapsedBefore
& $adb -s $Device shell input tap 540 2050
Start-Sleep -Seconds 3
& $adb -s $Device exec-out screencap -p > $expanded

& $adb -s $Device shell su -c "rm -f $remote"
$recording = Start-Job -ScriptBlock {
    param($Adb, $Serial, $Path)
    & $Adb -s $Serial shell su -c "screenrecord --bit-rate 12000000 --time-limit 5 $Path"
} -ArgumentList $adb, $Device, $remote

Start-Sleep -Milliseconds 600
& $adb -s $Device shell input swipe 540 360 540 2200 500
Wait-Job $recording -Timeout 8 | Out-Null
Receive-Job $recording | Out-Null
Remove-Job $recording -Force
& $adb -s $Device shell su -c "chmod 644 $remote"
& $adb -s $Device pull $remote $local | Out-Null
& $adb -s $Device exec-out screencap -p > $collapsedAfter

$stateAnalyzer = Join-Path $PSScriptRoot "analyze-player-transition-state.py"
python $stateAnalyzer $collapsedBefore $expanded $collapsedAfter
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$analyzer = Join-Path $PSScriptRoot "analyze-bottom-chrome-flicker.py"
python $analyzer $local
exit $LASTEXITCODE
