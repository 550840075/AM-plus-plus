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
$capture = Join-Path $env:TEMP "am-phone-glass-underlap.png"

& $adb -s $Device shell am force-stop com.apple.android.music
& $adb -s $Device shell monkey -p com.apple.android.music -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 5
& $adb -s $Device shell input tap 739 2250
Start-Sleep -Seconds 2
& $adb -s $Device shell input tap 280 1650
Start-Sleep -Seconds 3
& $adb -s $Device exec-out screencap -p > $capture

$analyzer = Join-Path $PSScriptRoot "analyze-phone-glass-underlap.py"
python $analyzer $capture
exit $LASTEXITCODE
