param([string]$Serial = 'emulator-5560')

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'Visual fixtures are restricted to the emulator.'
}
$oldScale = (& $adb -s $Serial shell settings get global animator_duration_scale).Trim()
try {
    & $adb -s $Serial shell settings put global animator_duration_scale 1
    foreach ($theme in @('violet', 'ocean', 'forest', 'clay')) {
        foreach ($night in @(1, 2)) {
            & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
                -Classes 'com.hawson.timeassistant.ui.InteractionVisualCaptureTest' `
                -RunnerArguments @{captureTheme=$theme; captureNight=$night; uiVariant='full-acceptance'} `
                -ReportName "full-acceptance-visual-$theme-$night"
        }
    }
    foreach ($mode in @('REDUCED', 'OFF')) {
        & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
            -Classes 'com.hawson.timeassistant.ui.InteractionVisualCaptureTest' `
            -RunnerArguments @{captureMotion=$mode; uiVariant='full-acceptance'} `
            -ReportName "full-acceptance-visual-$mode"
    }
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.InteractionVisualCaptureTest' `
        -RunnerArguments @{captureKeyboard='true'; uiVariant='full-acceptance-keyboard'} `
        -ReportName 'full-acceptance-visual-keyboard'
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.SoftMotionVisualCaptureTest' `
        -ReportName 'full-acceptance-motion-frames'
    & $adb -s $Serial shell settings put global animator_duration_scale 0
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.FocusLayoutTransitionTest#systemDisabledFocusLayoutChangesSynchronously' `
        -ReportName 'full-acceptance-system-motion-disabled'
} finally {
    if ($oldScale -and $oldScale -ne 'null') {
        & $adb -s $Serial shell settings put global animator_duration_scale $oldScale
    } else {
        & $adb -s $Serial shell settings delete global animator_duration_scale
    }
}
