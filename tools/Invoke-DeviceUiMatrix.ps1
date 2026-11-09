param(
    [switch]$ThemeMatrix,
    [int]$AdbPort = 5037,
    [string]$Serial = '',
    [string[]]$Variants = @(),
    [string]$Classes = 'com.hawson.timeassistant.ui.DeviceConfigurationWorkflowTest',
    [string]$ReportPrefix = 'config'
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$targetFilter = { $_ -match '\sdevice\s' -and (-not $Serial -or
        [regex]::Match($_, '^(.*?)\s+device\s').Groups[1].Value -eq $Serial) }
$devices = @(& $adb -P $AdbPort devices -l | Where-Object $targetFilter)
for ($attempt = 0; $devices.Count -eq 0 -and $attempt -lt 5; $attempt++) {
    Start-Sleep -Seconds 1
    $devices = @(& $adb -P $AdbPort devices -l | Where-Object $targetFilter)
}
if ($devices.Count -ne 1) { throw 'Exactly one authorized device is required.' }
$transport = [regex]::Match($devices[0], 'transport_id:(\d+)').Groups[1].Value
$originalSerial = [regex]::Match($devices[0], '^(.*?)\s+device\s').Groups[1].Value
$oldSize = & $adb -P $AdbPort -t $transport shell wm size
$oldDensity = & $adb -P $AdbPort -t $transport shell wm density
$oldFont = (& $adb -P $AdbPort -t $transport shell settings get system font_scale).Trim()
$sizeOverride = [regex]::Match(($oldSize -join "`n"), 'Override size: (\d+x\d+)').Groups[1].Value
$densityOverride = [regex]::Match(($oldDensity -join "`n"), 'Override density: (\d+)').Groups[1].Value
try {
    if ($ThemeMatrix) {
        foreach ($theme in @('neutral','violet','ocean','forest','clay')) {
            & "$PSScriptRoot/Invoke-DeviceQa.ps1" -AdbPort $AdbPort -Serial $originalSerial -ReportName "theme-$theme" `
                -Classes 'com.hawson.timeassistant.ui.UiVisualCaptureTest#captureRealScreens' `
                -RunnerArguments @{captureVariant='matrix';captureTheme=$theme}
        }
    } else {
        foreach ($variant in @(
            @{name='compact320';size='960x2112';font='1.0'},
            @{name='width360';size='1080x2376';font='1.0'},
            @{name='width412';size='1236x2718';font='1.0'},
            @{name='font13';size='1080x2376';font='1.3'},
            @{name='font2';size='1080x2376';font='2.0'},
            @{name='landscape';size='1080x2376';font='1.0'}
        )) {
            if ($Variants.Count -gt 0 -and $variant.name -notin $Variants) { continue }
            & $adb -P $AdbPort -t $transport shell wm size $variant.size
            if ($LASTEXITCODE -ne 0) { throw 'Cannot apply test viewport.' }
            & $adb -P $AdbPort -t $transport shell wm density 480
            if ($LASTEXITCODE -ne 0) { throw 'Cannot apply test density.' }
            & $adb -P $AdbPort -t $transport shell settings put system font_scale $variant.font
            if ($LASTEXITCODE -ne 0) { throw 'Cannot apply test font scale.' }
            Start-Sleep -Seconds 2
            & "$PSScriptRoot/Invoke-DeviceQa.ps1" -AdbPort $AdbPort -Serial $originalSerial -ReportName "$ReportPrefix-$($variant.name)" `
                -Classes $Classes `
                -RunnerArguments @{uiVariant=$variant.name}
        }
    }
} finally {
    # Wireless ADB may reconnect after the host server restarts. Restore only with one authorized target.
    $ready = $false
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $connected = @(& $adb -P $AdbPort devices -l | Where-Object {
            $_ -match '\sdevice\s' -and [regex]::Match($_, '^(.*?)\s+device\s').Groups[1].Value -eq $originalSerial
        })
        if ($connected.Count -eq 1) {
            $transport = [regex]::Match($connected[0], 'transport_id:(\d+)').Groups[1].Value
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Display restoration requires reconnecting the original test phone; no other device was modified.' }
    if ($sizeOverride) { & $adb -P $AdbPort -t $transport shell wm size $sizeOverride }
    else { & $adb -P $AdbPort -t $transport shell wm size reset }
    if ($densityOverride) { & $adb -P $AdbPort -t $transport shell wm density $densityOverride }
    else { & $adb -P $AdbPort -t $transport shell wm density reset }
    if ($oldFont -and $oldFont -ne 'null') { & $adb -P $AdbPort -t $transport shell settings put system font_scale $oldFont }
    else { & $adb -P $AdbPort -t $transport shell settings delete system font_scale }
}
