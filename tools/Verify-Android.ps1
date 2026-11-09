param(
    [switch]$Offline,
    [string[]]$Tasks = @("checkModuleBoundaries", "lintDebug", "assembleDebug",
        "testDebugUnitTest", ":core:domain:test", ":core:network:test", ":app:assembleDebugAndroidTest")
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$javaHome = Join-Path $root "build-tools\temurin-17\jdk-17.0.20.1+1"
$unavailable = Join-Path $root "build-tmp\nio-unavailable"
if (Test-Path -LiteralPath $unavailable) {
    throw "The Unix-domain fallback path must not exist: $unavailable"
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "Expected bundled JDK 17 at $javaHome"
}

$saved = @{}
foreach ($key in @("JAVA_HOME", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "GRADLE_USER_HOME")) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
}
Push-Location $root
try {
    $env:JAVA_HOME = $javaHome
    $env:JAVA_OPTS = ""
    # A nonexistent Unix-domain directory makes this JDK fall back to TCP on Windows.
    $env:JDK_JAVA_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$unavailable -Dfile.encoding=UTF-8"
    $env:GRADLE_USER_HOME = Join-Path $root ".gradle-mobile-build"
    $arguments = @("--no-daemon", "--console=plain")
    if ($Offline) { $arguments += "--offline" }
    & (Join-Path $root "gradlew.bat") @arguments @Tasks
    $result = $LASTEXITCODE
} finally {
    Pop-Location
    foreach ($key in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($key, $saved[$key], "Process")
    }
}
exit $result
