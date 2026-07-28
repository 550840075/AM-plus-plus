# Device QA scripts

These scripts are optional device regressions used while adapting Apple Music 6.5.0. They are not part of the Gradle build.

## Requirements

- PowerShell 7 and ADB
- An unlocked device with Apple Music and the module installed
- `ANDROID_SERIAL` set, or the corresponding `-Serial` / `-Device` argument supplied
- Root access for scripts that record through `/data/local/tmp`
- Python with OpenCV (`cv2`) and NumPy for the image/video analyzers

Several liquid-glass checks use fixed coordinates or 1080 × 2376 regions from the reference phone. Review and adjust those values before running on another resolution. The tablet checks expect Apple Music to be open in landscape.

Example:

```powershell
$env:ANDROID_SERIAL = "your-device-serial"
.\scripts\verify-device-dual-pane.ps1
```
