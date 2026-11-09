param([string]$Serial = 'emulator-5560', [string]$ReportPrefix = 'full-acceptance')

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'The full destructive fixture suite is emulator-only.'
}
$directory = Join-Path (Split-Path -Parent $PSScriptRoot) "build/device-qa/$(Get-Date -Format 'yyyy-MM-dd')"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$classes = @(
    'ExampleInstrumentedTest', 'LocalIntegrationSettingsTest', 'AiConsentMigrationTest',
    'AiProfileStorageTest', 'AppRepositoryFocusCompletionTest', 'CalendarEventComposerTest',
    'data.core.RepositorySnapshotCodecTest', 'data.ProposalUndoVersionTest', 'TutorialFlowTest',
    'OAuthBrowserReturnTest', 'OAuthRecoverySessionTest', 'OAuthConnectionPersistenceTest',
    'settings.AiSettingsDraftIsolationTest', 'settings.OAuthSignInHelpTest', 'settings.AiQuotaPresentationTest',
    'Sub2ApiQuotaCompatibilityTest#referencePreflightRequiresAnActualAccountId',
    'Sub2ApiQuotaCompatibilityTest#referenceHeadersPreserveBearerAndAccountIsolation',
    'Sub2ApiQuotaCompatibilityTest#responseStatusTakesPrecedenceOverContentType',
    'ui.UiRedesignBehaviorTest', 'ui.ChatVoiceBehaviorTest', 'ui.ChatPresentationTest',
    'ui.AiConversationWorkflowTest', 'ui.FullFeatureWorkflowTest', 'ui.InteractionRefinementTest',
    'ui.schedule.ScheduleSwipeLayoutGestureTest', 'ui.AttentionReminderChatWorkflowTest',
    'ui.ChatLayoutPresentationTest', 'ui.DeviceConnectionUiTest', 'ui.DeviceUiSmokeTest',
    'ui.SoftMotionBehaviorTest', 'ui.FocusLayoutTransitionTest', 'ui.FocusLayoutPhoneSmokeTest'
)
$results = @()
foreach ($class in $classes) {
    $name = $class.Replace('.', '-').Replace('#', '-')
    $report = "$ReportPrefix-$name"
    $failure = ''
    try {
        & "$PSScriptRoot/Invoke-DeviceQa.ps1" -Serial $Serial `
            -Classes "com.hawson.timeassistant.$class" -ReportName $report
    } catch { $failure = $_.Exception.Message }
    $file = Join-Path $directory "$report.txt"
    $body = if (Test-Path -LiteralPath $file) { Get-Content -Raw -LiteralPath $file } else { '' }
    $passed = ([regex]::Matches($body, 'INSTRUMENTATION_STATUS_CODE: 0\r?\n')).Count
    $skipped = ([regex]::Matches($body, 'INSTRUMENTATION_STATUS_CODE: -[34]\r?\n')).Count
    $failed = ([regex]::Matches($body, 'INSTRUMENTATION_STATUS_CODE: -[12]\r?\n')).Count
    $results += [pscustomobject]@{
        Class = $class; Passed = $passed; Skipped = $skipped; Failed = $failed
        Result = $(if ($failure) { 'FAILED' } else { 'PASSED' }); Error = $failure; Report = $file
    }
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $directory "$ReportPrefix-index.json") -Encoding UTF8
    Write-Output "Completed $class : passed=$passed skipped=$skipped failed=$failed"
}
$results | Format-Table Class, Passed, Skipped, Failed, Result -AutoSize
if ($results.Result -contains 'FAILED') { throw 'One or more acceptance batches failed; inspect the index and retained reports.' }
