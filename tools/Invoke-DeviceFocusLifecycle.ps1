param([int]$AdbPort = 5037, [switch]$IncludeScreenOff)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$devices = @(& $adb -P $AdbPort devices -l | Where-Object { $_ -match '\sdevice\s' })
for ($attempt = 0; $devices.Count -eq 0 -and $attempt -lt 5; $attempt++) {
    Start-Sleep -Seconds 1
    $devices = @(& $adb -P $AdbPort devices -l | Where-Object { $_ -match '\sdevice\s' })
}
if ($devices.Count -ne 1) { throw 'Exactly one authorized device is required.' }
$transport = [regex]::Match($devices[0], 'transport_id:(\d+)').Groups[1].Value
$class = 'com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#checkpointSurvivesExplicitProcessRestart'
$install = $true
foreach ($state in @('running','paused')) {
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -AdbPort $AdbPort -Install:$install -Classes $class `
        -ReportName "focus-seed-$state" -RunnerArguments @{focusPhase="seed-$state"}
    $install = $false
    # Explicit force-stop is a persisted-state test, not a claim of OEM service restart support.
    & $adb -P $AdbPort -t $transport shell am force-stop com.hawson.timeassistant.device.qa
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop isolated QA process.' }
    Start-Sleep -Seconds 3
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -AdbPort $AdbPort -Classes $class `
        -ReportName "focus-verify-$state" -RunnerArguments @{focusPhase="verify-$state"}
}
& "$PSScriptRoot/Invoke-DeviceQa.ps1" -AdbPort $AdbPort `
    -Classes 'com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#awakeBackgroundTimerSettlesOnceWithoutCompletingTask' `
    -ReportName 'focus-awake-background' -RunnerArguments @{awakeBackground='true'}
if ($IncludeScreenOff) {
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -AdbPort $AdbPort `
        -Classes 'com.hawson.timeassistant.ui.DeviceFocusLifecycleTest#screenOffTimerSettlesOnceWithoutCompletingTask' `
        -ReportName 'focus-screen-off' -RunnerArguments @{screenOff='true'}
}
