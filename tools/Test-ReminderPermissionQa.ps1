param([string]$Serial = 'emulator-5560', [string]$ReportName = 'attention-reminder-permission-blocked')

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package = 'com.hawson.timeassistant.interaction.qa'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'Notification permission fixtures are restricted to the emulator.'
}
if (-not (& $adb -s $Serial shell pm path $package)) { throw 'Install the isolated QA package first.' }
try {
    & $adb -s $Serial shell pm revoke $package android.permission.POST_NOTIFICATIONS
    if ($LASTEXITCODE -ne 0) { throw 'Could not revoke notification permission.' }
    & $adb -s $Serial shell pm set-permission-flags $package android.permission.POST_NOTIFICATIONS user-set user-fixed
    if ($LASTEXITCODE -ne 0) { throw 'Could not stage the denied-permission fixture.' }
    & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
        -Classes 'com.hawson.timeassistant.ui.AttentionReminderChatWorkflowTest#foregroundReminderAndDiagnosticsRespectSystemPermission' `
        -RunnerArguments @{ notificationBlocked = 'true' } -ReportName $ReportName
} finally {
    & $adb -s $Serial shell pm clear-permission-flags $package android.permission.POST_NOTIFICATIONS user-set user-fixed
    & $adb -s $Serial shell pm grant $package android.permission.POST_NOTIFICATIONS
}
