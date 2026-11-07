param(
    [string]$JavaHome = (Join-Path $PSScriptRoot 'toolchain\jdk21-clean\jdk-21.0.13+9'),
    [string]$AndroidHome = (Join-Path $PSScriptRoot 'toolchain\android-sdk'),
    [switch]$Tests,
    [switch]$Debug
)
$ErrorActionPreference='Stop'
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))){throw 'Pass -JavaHome with JDK 21 or later.'}
if(!(Test-Path -LiteralPath (Join-Path $AndroidHome 'platforms\android-37.2\android.jar'))){throw 'Pass -AndroidHome with Android SDK platform 37.2 and Build Tools 37 installed.'}
$env:JAVA_HOME=(Resolve-Path $JavaHome).Path
$env:ANDROID_HOME=(Resolve-Path $AndroidHome).Path
$env:GRADLE_USER_HOME=Join-Path $PSScriptRoot 'toolchain\gradle-cache'
$taskSigning=Join-Path $PSScriptRoot 'signing'
New-Item -ItemType Directory -Force $taskSigning | Out-Null
if(!(Test-Path -LiteralPath (Join-Path $taskSigning 'jmlite.jks'))){
    $taskRandom=New-Object byte[] 24
    $taskRng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
    $taskRng.GetBytes($taskRandom);$taskRng.Dispose()
    [IO.File]::WriteAllText((Join-Path $taskSigning 'password.txt'),[Convert]::ToBase64String($taskRandom))
    $taskPrevError=$ErrorActionPreference;$ErrorActionPreference='Continue'
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore (Join-Path $taskSigning 'jmlite.jks') -alias jmlite -storepass:file (Join-Path $taskSigning 'password.txt') -keypass:file (Join-Path $taskSigning 'password.txt') -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=JM Lite Local, O=Personal' 2>&1 | Out-Null
    $taskKeyExit=$LASTEXITCODE
    $ErrorActionPreference=$taskPrevError
    if($taskKeyExit -ne 0){throw 'Unable to create local signing key.'}
}
$taskPassword=Get-Content (Join-Path $taskSigning 'password.txt') -Raw
$taskProperties="storeFile=../../signing/jmlite.jks`nstorePassword=$taskPassword`nkeyAlias=jmlite`nkeyPassword=$taskPassword`n"
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'mihon\keystore.properties'),$taskProperties,[Text.UTF8Encoding]::new($false))
$taskTasks=@(':app:assembleRelease')
if($Debug){$taskTasks=@(':app:assembleDebug')}
if($Tests){$taskTasks+=@(':app:assembleDebug',':app:assembleDebugAndroidTest')}
Push-Location (Join-Path $PSScriptRoot 'mihon')
try{
    $taskGradleErrorAction=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    & ./gradlew.bat @taskTasks --console=plain --no-daemon -Pinclude-telemetry=false -Penable-updater=false
    $taskGradleExit=$LASTEXITCODE
    $ErrorActionPreference=$taskGradleErrorAction
    if($taskGradleExit -ne 0){throw 'Mihon framework build failed.'}
}finally{Pop-Location}
$taskVariant=if($Debug){'debug'}else{'release'}
$taskApk=Join-Path $PSScriptRoot "mihon\app\build\outputs\apk\$taskVariant\app-$taskVariant.apk"
if(!(Test-Path -LiteralPath $taskApk)){throw 'arm64-v8a APK was not generated.'}
New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'build') | Out-Null
Copy-Item -LiteralPath $taskApk -Destination (Join-Path $PSScriptRoot 'build\JM-Lite-5.3.0-arm64-v8a.apk') -Force
$taskVersion=[regex]::Match([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'mihon\app\build.gradle.kts')),'versionName = "([0-9.]+)"').Groups[1].Value
$taskProject='https://github.com/esn-idiotzzz/jianyue'
$taskManifest=[ordered]@{
    tag_name="v$taskVersion";draft=$false;prerelease=$false
    html_url="$taskProject/releases/tag/v$taskVersion"
    body=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'RELEASE_NOTES.md'),[Text.UTF8Encoding]::new($false))
    assets=@([ordered]@{name="JianYue-$taskVersion-arm64-v8a.apk";state='uploaded';browser_download_url="$taskProject/releases/download/v$taskVersion/JianYue-$taskVersion-arm64-v8a.apk";size=(Get-Item -LiteralPath $taskApk).Length;digest='sha256:'+((Get-FileHash -LiteralPath $taskApk).Hash.ToLowerInvariant())})
}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'build\update.json'),($taskManifest | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Get-Item -LiteralPath (Join-Path $PSScriptRoot 'build\JM-Lite-5.3.0-arm64-v8a.apk') | Select-Object FullName,Length
