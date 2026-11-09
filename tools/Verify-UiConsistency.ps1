param([string]$Serial = 'emulator-5560', [switch]$CorrectionsOnly)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Fictional fixture matrix is emulator-only.' }
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package = 'com.hawson.timeassistant.interaction.qa'
$size = (& $adb -s $Serial shell wm size) -join "`n"
$density = (& $adb -s $Serial shell wm density) -join "`n"
$font = (& $adb -s $Serial shell settings get system font_scale).Trim()
$sizeOverride = [regex]::Match($size, 'Override size: (\d+x\d+)').Groups[1].Value
$densityOverride = [regex]::Match($density, 'Override density: (\d+)').Groups[1].Value
try {
    foreach ($variant in @(
        @{name='compact320';size='960x2112';font='1.0'},
        @{name='width360';size='1080x2376';font='1.0'},
        @{name='width412';size='1236x2718';font='1.0'},
        @{name='font13';size='1080x2376';font='1.3'},
        @{name='font2';size='1080x2376';font='2.0'},
        @{name='landscape';size='1080x2376';font='2.0'}
    )) {
        & $adb -s $Serial shell wm size $variant.size
        if ($LASTEXITCODE -ne 0) { throw 'Cannot apply emulator viewport.' }
        & $adb -s $Serial shell wm density 480
        if ($LASTEXITCODE -ne 0) { throw 'Cannot apply emulator density.' }
        & $adb -s $Serial shell settings put system font_scale $variant.font
        if ($LASTEXITCODE -ne 0) { throw 'Cannot apply emulator font scale.' }
        Start-Sleep -Seconds 2
        $prefix = if ($CorrectionsOnly) { 'corrected-' } else { '' }
        $scope = if ($CorrectionsOnly) { 'corrections' } else { 'all' }
        $report = Join-Path $PSScriptRoot "../build/ui-consistency-$($prefix)display-$($variant.name).txt"
        & $adb -s $Serial shell am instrument -w -r -e class `
            'com.hawson.timeassistant.ui.UiConsistencyWorkflowTest#captureVerifiedPagesAcrossFiveThemesAndNightModes' `
            -e consistencyVariant $variant.name -e consistencyTheme neutral -e consistencyScope $scope `
            "$package.test/com.hawson.timeassistant.QaTestRunner" *> $report
        if (-not (Select-String -Path $report -Pattern 'OK \(1 test\)' -Quiet)) {
            throw "Display matrix failed: $($variant.name); see $report"
        }
        Write-Output "Passed $($variant.name)"
    }
} finally {
    if ($sizeOverride) { & $adb -s $Serial shell wm size $sizeOverride }
    else { & $adb -s $Serial shell wm size reset }
    if ($densityOverride) { & $adb -s $Serial shell wm density $densityOverride }
    else { & $adb -s $Serial shell wm density reset }
    if ($font -and $font -ne 'null') { & $adb -s $Serial shell settings put system font_scale $font }
    else { & $adb -s $Serial shell settings delete system font_scale }
}
