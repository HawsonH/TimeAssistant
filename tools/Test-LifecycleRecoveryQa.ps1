param([string]$Serial = 'emulator-5560')

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package = 'com.hawson.timeassistant.interaction.qa'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'Process-stop and screen-off fixtures are emulator-only.'
}
if (-not (& $adb -s $Serial shell pm path $package)) { throw 'Install the isolated QA package first.' }
try {
    foreach ($state in @('running', 'paused')) {
        & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
            -Classes 'com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#checkpointSurvivesExplicitProcessRestart' `
            -RunnerArguments @{ focusPhase = "seed-$state" } -ReportName "full-acceptance-focus-seed-$state"
        & $adb -s $Serial shell am force-stop $package
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the isolated emulator package.' }
        Start-Sleep -Seconds 2
        & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
            -Classes 'com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#checkpointSurvivesExplicitProcessRestart' `
            -RunnerArguments @{ focusPhase = "verify-$state" } -ReportName "full-acceptance-focus-verify-$state"
    }
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.ReminderProcessRecoveryTest' `
        -RunnerArguments @{ reminderPhase = 'seed' } -ReportName 'full-acceptance-reminder-process-seed'
    & $adb -s $Serial shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the isolated emulator package.' }
    Start-Sleep -Seconds 13
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.ReminderProcessRecoveryTest' `
        -RunnerArguments @{ reminderPhase = 'verify' } -ReportName 'full-acceptance-reminder-process-verify'
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#awakeBackgroundTimerSettlesOnceWithoutCompletingTask,com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#screenOffTimerSettlesOnceWithoutCompletingTask' `
        -RunnerArguments @{ awakeBackground = 'true'; screenOff = 'true' } -ReportName 'full-acceptance-focus-background'
} finally {
    & $adb -s $Serial shell input keyevent 224
}
