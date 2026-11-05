param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$RuntimeArguments = (Join-Path $PSScriptRoot '..\..\mihon\app\build\jianyue-test-runtime.args')
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome with a JDK 21 directory.'
}
if (!(Test-Path -LiteralPath $RuntimeArguments)) {
    throw 'Export the Android unit-test runtime with export-test-classpath.gradle first; see README.md.'
}
& (Join-Path $JavaHome 'bin\java.exe') ('@' + (Resolve-Path -LiteralPath $RuntimeArguments).Path) '-Xmx1g' '-XX:+EnableDynamicAgentLoading' (Join-Path $PSScriptRoot 'RunJianyueTests.java') 2>&1 |
    Tee-Object -FilePath (Join-Path $PSScriptRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw "JUnit launcher exited with $LASTEXITCODE" }
