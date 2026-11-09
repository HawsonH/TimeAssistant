param(
    [switch]$Install,
    [switch]$SplitClasses,
    [int]$AdbPort = 5037,
    [string]$Serial = '',
    [string]$Classes = '',
    [hashtable]$RunnerArguments = @{},
    [string]$ReportName = 'instrumentation'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package = 'com.hawson.timeassistant.interaction.qa'
$directory = Join-Path $root "build/device-qa/$(Get-Date -Format 'yyyy-MM-dd')"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$targetFilter = { $_ -match '\sdevice\s' -and (-not $Serial -or
        [regex]::Match($_, '^(.*?)\s+device\s').Groups[1].Value -eq $Serial) }
$devices = @(& $adb -P $AdbPort devices -l | Where-Object $targetFilter)
for ($attempt = 0; $devices.Count -eq 0 -and $attempt -lt 5; $attempt++) {
    Start-Sleep -Seconds 1
    $devices = @(& $adb -P $AdbPort devices -l | Where-Object $targetFilter)
}
if ($devices.Count -ne 1) { throw 'Exactly one authorized Android device is required.' }
$transport = [regex]::Match($devices[0], 'transport_id:(\d+)').Groups[1].Value
if (-not $transport) { throw 'Missing ADB transport ID.' }
if ($Install) {
    $main = Join-Path $root 'app/build/outputs/apk/debug'
    $test = Join-Path $root 'app/build/outputs/apk/androidTest/debug'
    foreach ($entry in @(@($main, $package, 'app-debug.apk'), @($test, "$package.test", 'app-debug-androidTest.apk'))) {
        $metadata = Get-Content -Raw -LiteralPath (Join-Path $entry[0] 'output-metadata.json') | ConvertFrom-Json
        if ($metadata.applicationId -ne $entry[1]) { throw "Refusing to install unexpected package: $($metadata.applicationId)" }
        & $adb -P $AdbPort -t $transport install -r (Join-Path $entry[0] $entry[2])
        if ($LASTEXITCODE -ne 0) { throw 'QA installation failed; no uninstall fallback is permitted.' }
    }
}
if (-not $Classes) {
    $Classes = (@(
        'ExampleInstrumentedTest','LocalIntegrationSettingsTest','AiConsentMigrationTest',
        'AiProfileStorageTest','AppRepositoryFocusCompletionTest','CalendarEventComposerTest',
        'data.core.RepositorySnapshotCodecTest','TutorialFlowTest','OAuthBrowserReturnTest',
        'OAuthRecoverySessionTest','OAuthConnectionPersistenceTest',
        'ui.UiRedesignBehaviorTest','ui.ChatVoiceBehaviorTest','ui.ChatPresentationTest',
        'ui.AiConversationWorkflowTest','ui.FullFeatureWorkflowTest','ui.DeviceUiSmokeTest',
        'ui.DeviceConnectionUiTest','settings.AiSettingsDraftIsolationTest'
    ) | ForEach-Object { "com.hawson.timeassistant.$_" }) -join ','
}
if ($SplitClasses) {
    $completed = @()
    foreach ($class in ($Classes -split ',')) {
        $name = $class.Substring($class.LastIndexOf('.') + 1)
        & $PSCommandPath -AdbPort $AdbPort -Serial $Serial -Classes $class -RunnerArguments $RunnerArguments -ReportName "$ReportName-$name"
        $completed += $class
    }
    $completed | Set-Content -LiteralPath (Join-Path $directory "$ReportName-completed-classes.txt") -Encoding UTF8
    Write-Output "All $($completed.Count) class batches passed."
    return
}
$arguments = @('-P', "$AdbPort", '-t', $transport, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'physicalFixtures', 'true', '-e', 'class', $Classes)
foreach ($key in $RunnerArguments.Keys) { $arguments += @('-e', $key, [string]$RunnerArguments[$key]) }
$arguments += "$package.test/com.hawson.timeassistant.QaTestRunner"
$report = Join-Path $directory "$ReportName.txt"
if (Test-Path -LiteralPath $report) {
    $history = Join-Path $directory 'history'
    New-Item -ItemType Directory -Force -Path $history | Out-Null
    Copy-Item -LiteralPath $report -Destination (Join-Path $history "$ReportName-$(Get-Date -Format 'HHmmssfff').txt")
}
$output = & $adb @arguments 2>&1 | Tee-Object -FilePath (Join-Path $directory "$ReportName.txt")
$exit = $LASTEXITCODE
$output | Set-Content -LiteralPath (Join-Path $directory "$ReportName.txt") -Encoding UTF8
$output | Write-Output
if ($exit -ne 0 -or ($output -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=') {
    throw "Device test failed. See $directory/$ReportName.txt"
}
if (($output -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Missing successful instrumentation summary.' }
