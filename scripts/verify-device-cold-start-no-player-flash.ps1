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

$remote = "/data/local/tmp/am-cold-start-flash.mp4"
$local = Join-Path $env:TEMP "am-cold-start-flash.mp4"

& $adb -s $Device shell am force-stop com.apple.android.music
& $adb -s $Device shell su -c "rm -f $remote"
$recording = Start-Job -ScriptBlock {
    param($Adb, $Serial, $Path)
    & $Adb -s $Serial shell su -c "screenrecord --bit-rate 12000000 --time-limit 6 $Path"
} -ArgumentList $adb, $Device, $remote

Start-Sleep -Milliseconds 600
& $adb -s $Device shell monkey -p com.apple.android.music -c android.intent.category.LAUNCHER 1 | Out-Null
Wait-Job $recording -Timeout 9 | Out-Null
Receive-Job $recording | Out-Null
Remove-Job $recording -Force
& $adb -s $Device shell su -c "chmod 644 $remote"
& $adb -s $Device pull $remote $local | Out-Null

$analyzer = Join-Path $PSScriptRoot "analyze-cold-start-player-flash.py"
python $analyzer $local
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$bottomAnalyzer = Join-Path $PSScriptRoot "analyze-bottom-chrome-flicker.py"
python $bottomAnalyzer $local
exit $LASTEXITCODE
