param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [int]$MinimumTopGapDp = 8
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
    $remotePath = "/sdcard/amenhancer-stacked-bottom-nav-check.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", $remotePath) | Out-Null
    $xml = (Invoke-Adb @("exec-out", "cat", $remotePath)) -join [Environment]::NewLine
    $document = [xml]$xml
    return $document
}

function Get-DeviceDensity {
    $densityOutput = (Invoke-Adb @("shell", "wm", "density")) -join " "
    $match = [regex]::Match($densityOutput, '(?:Override density|Physical density):\s*(\d+)')
    if (-not $match.Success) {
        throw "RED: could not determine device density from '$densityOutput'."
    }
    return [int]$match.Groups[1].Value
}

function Get-Bounds {
    param([Parameter(Mandatory = $true)][System.Xml.XmlElement]$Node)

    $match = [regex]::Match($Node.GetAttribute("bounds"), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) {
        throw "RED: invalid UI bounds '$($Node.GetAttribute("bounds"))'."
    }
    return [pscustomobject]@{
        X1 = [int]$match.Groups[1].Value
        Y1 = [int]$match.Groups[2].Value
        X2 = [int]$match.Groups[3].Value
        Y2 = [int]$match.Groups[4].Value
    }
}

function Get-NodeById {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlDocument]$Document,
        [Parameter(Mandatory = $true)][string]$ResourceId
    )

    return $Document.SelectSingleNode("//node[@resource-id='$ResourceId']")
}

function Assert-MiniPlayerVerticallyCentered {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlDocument]$Document,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    $miniPlayerRow = Get-NodeById $Document "com.apple.android.music:id/mini_player"
    $miniPlayerContent = Get-NodeById $Document "com.apple.android.music:id/mini_player_content"
    if ($null -eq $miniPlayerRow -or $null -eq $miniPlayerContent) {
        throw "RED: $Phase missing mini-player row/content (rowPresent=$($null -ne $miniPlayerRow); contentPresent=$($null -ne $miniPlayerContent))."
    }

    $rowBounds = Get-Bounds $miniPlayerRow
    $contentBounds = Get-Bounds $miniPlayerContent
    if (
        $contentBounds.X1 -ne $rowBounds.X1 -or $contentBounds.X2 -ne $rowBounds.X2 -or
        $contentBounds.Y1 -lt $rowBounds.Y1 -or $contentBounds.Y2 -gt $rowBounds.Y2
    ) {
        throw "RED: $Phase mini-player content is outside its row (row=$($miniPlayerRow.GetAttribute('bounds')); content=$($miniPlayerContent.GetAttribute('bounds')))."
    }

    $topGap = $contentBounds.Y1 - $rowBounds.Y1
    $bottomGap = $rowBounds.Y2 - $contentBounds.Y2
    # Accessibility bounds can round a centered 56dp child by a pixel or two.
    if ([math]::Abs($topGap - $bottomGap) -gt 2) {
        throw "RED: $Phase mini-player content is not vertically centered (topGap=${topGap}px; bottomGap=${bottomGap}px; row=$($miniPlayerRow.GetAttribute('bounds')); content=$($miniPlayerContent.GetAttribute('bounds')))."
    }
}

function Assert-StackedBottomNavigation {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlDocument]$Document,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    $display = $Document.SelectSingleNode('/hierarchy/node[1]')
    if ($null -eq $display) {
        throw "RED: $Phase could not determine display bounds."
    }
    $displayBounds = Get-Bounds $display

    $tabs = Get-NodeById $Document "com.apple.android.music:id/bottom_navigation_tabs_frame"
    $miniPlayer = Get-NodeById $Document "com.apple.android.music:id/mini_player_content"
    if ($null -eq $tabs -or $null -eq $miniPlayer) {
        throw "RED: $Phase missing stacked bottom UI (tabsPresent=$($null -ne $tabs); miniPlayerPresent=$($null -ne $miniPlayer))."
    }
    $tabsBounds = Get-Bounds $tabs
    $miniBounds = Get-Bounds $miniPlayer
    $stacked =
        $tabsBounds.X1 -eq 0 -and $tabsBounds.X2 -eq $displayBounds.X2 -and
        $miniBounds.X1 -eq 0 -and $miniBounds.X2 -eq $displayBounds.X2 -and
        $miniBounds.Y2 -le $tabsBounds.Y1
    if (-not $stacked) {
        throw "RED: $Phase is not the mobile stacked layout (mini=$($miniPlayer.GetAttribute('bounds')); tabs=$($tabs.GetAttribute('bounds')))."
    }
    Assert-MiniPlayerVerticallyCentered $Document $Phase

    $minimumTopGapPx = [math]::Ceiling($MinimumTopGapDp * (Get-DeviceDensity) / 160.0)

    $actionIds = @(
        "action_listen_now",
        "action_browse",
        "action_multiply_radio",
        "action_library",
        "search_fragment"
    )
    $overlaps = @()
    $topGapFailures = @()
    foreach ($actionId in $actionIds) {
        $action = Get-NodeById $Document "com.apple.android.music:id/$actionId"
        if ($null -eq $action) {
            throw "RED: $Phase missing navigation action '$actionId'."
        }
        $icon = $action.SelectSingleNode(".//node[contains(@resource-id, 'navigation_bar_item_icon_view')]")
        $label = $action.SelectSingleNode(".//node[contains(@resource-id, 'navigation_bar_item_') and contains(@resource-id, 'label_view')]")
        if ($null -eq $icon -or $null -eq $label) {
            throw "RED: $Phase action '$actionId' is missing its icon or text label."
        }
        $iconBounds = Get-Bounds $icon
        $labelBounds = Get-Bounds $label
        # Two pixels allows for accessibility bounds rounding. Any larger
        # intersection is the exact user-reported icon/text collision.
        if ($iconBounds.Y2 -gt ($labelBounds.Y1 + 2)) {
            $overlaps += "$actionId icon=$($icon.GetAttribute('bounds')) label=$($label.GetAttribute('bounds'))"
        }
        $topGap = $iconBounds.Y1 - $tabsBounds.Y1
        if ($topGap -lt $minimumTopGapPx) {
            $topGapFailures += "$actionId topGap=${topGap}px"
        }
    }
    if ($overlaps.Count -gt 0) {
        throw "RED: $Phase navigation icon/text overlap: $($overlaps -join '; ')"
    }
    if ($topGapFailures.Count -gt 0) {
        throw "RED: $Phase navigation icons are too close to the top edge (minimum=${minimumTopGapPx}px / ${MinimumTopGapDp}dp; $($topGapFailures -join '; '))."
    }

    return "GREEN: $Phase has full-width mobile stacking, five non-overlapping labels, and at least ${MinimumTopGapDp}dp top icon clearance."
}

$expanded = $false
try {
    Invoke-Adb @("shell", "am", "force-stop", "com.apple.android.music") | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.apple.android.music/.onboarding.activities.SplashActivity") | Out-Null
    Start-Sleep -Seconds 3

    $coldUi = Get-UiHierarchy
    $coldResult = Assert-StackedBottomNavigation $coldUi "cold start"

    $miniPlayer = Get-NodeById $coldUi "com.apple.android.music:id/mini_player_content"
    $miniBounds = Get-Bounds $miniPlayer
    $tapX = [int](($miniBounds.X1 + $miniBounds.X2) / 2)
    $tapY = [int](($miniBounds.Y1 + $miniBounds.Y2) / 2)
    Invoke-Adb @("shell", "input", "tap", "$tapX", "$tapY") | Out-Null
    Start-Sleep -Seconds 2
    $expanded = $true

    Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
    Start-Sleep -Seconds 2
    $restoredUi = Get-UiHierarchy
    $restoredResult = Assert-StackedBottomNavigation $restoredUi "after expand/collapse"

    Write-Output "$coldResult $restoredResult"
}
finally {
    if ($expanded) {
        $finalUi = Get-UiHierarchy
        if ($null -eq (Get-NodeById $finalUi "com.apple.android.music:id/bottom_navigation_tabs_frame")) {
            Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
        }
    }
}
