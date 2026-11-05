param(
    [Parameter(Mandatory=$true)][string]$ToolsDir,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [switch]$Tests
)
$ErrorActionPreference='Stop'
$taskJava=Join-Path $JavaHome 'bin'
$taskBuild=Join-Path $PSScriptRoot 'build'
$taskSource=Join-Path $PSScriptRoot 'app\src\main'
$taskSigning=Join-Path $PSScriptRoot 'signing'
$taskDeps=@(Get-ChildItem "$PSScriptRoot\deps" -Filter '*.jar' | ForEach-Object FullName)
$taskClasspath="$ToolsDir\android.jar;"+($taskDeps -join ';' )
function Invoke-Checked($exe,$taskArgs){ & $exe @taskArgs; if($LASTEXITCODE -ne 0){throw "Build failed: $exe"} }
New-Item -ItemType Directory -Force "$taskBuild\classes","$taskBuild\generated","$taskBuild\dex",$taskSigning | Out-Null
if(!(Test-Path -LiteralPath "$taskSigning\jmlite.jks")){
    $taskRandom=New-Object byte[] 24
    $taskRng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
    $taskRng.GetBytes($taskRandom)
    $taskRng.Dispose()
    $taskPassword=[Convert]::ToBase64String($taskRandom)
    [IO.File]::WriteAllText("$taskSigning\password.txt",$taskPassword)
    Invoke-Checked "$taskJava\keytool.exe" @('-genkeypair','-keystore',"$taskSigning\jmlite.jks",'-alias','jmlite','-storepass:file',"$taskSigning\password.txt",'-keypass:file',"$taskSigning\password.txt",'-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=JM Lite Local, O=Personal')
}
Invoke-Checked "$ToolsDir\build-tools\aapt.exe" @('package','-f','-m','-J',"$taskBuild\generated",'-M',"$taskSource\AndroidManifest.xml",'-S',"$taskSource\res",'-A',"$taskSource\assets",'-I',"$ToolsDir\android.jar",'-F',"$taskBuild\resources.apk")
$taskSources=@(Get-ChildItem "$taskSource\java" -Recurse -Filter '*.java' | ForEach-Object FullName)
$taskSources+=@(Get-ChildItem "$taskBuild\generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
Invoke-Checked "$taskJava\javac.exe" (@('-encoding','UTF-8','--release','8','-classpath',"$taskClasspath",'-d',"$taskBuild\classes")+$taskSources)
Invoke-Checked "$taskJava\jar.exe" @('cf',"$taskBuild\app-classes.jar",'-C',"$taskBuild\classes",'.')
Invoke-Checked "$taskJava\java.exe" (@('-cp',"$ToolsDir\r8.jar",'com.android.tools.r8.D8','--release','--min-api','26','--lib',"$ToolsDir\android.jar",'--output',"$taskBuild\dex", "$taskBuild\app-classes.jar")+$taskDeps)
Copy-Item -LiteralPath "$taskBuild\resources.apk" -Destination "$taskBuild\unsigned.apk" -Force
Push-Location "$taskBuild\dex"
try{Invoke-Checked "$ToolsDir\build-tools\aapt.exe" @('add',"$taskBuild\unsigned.apk",'classes.dex')}finally{Pop-Location}
Invoke-Checked "$ToolsDir\build-tools\zipalign.exe" @('-f','4',"$taskBuild\unsigned.apk", "$taskBuild\aligned.apk")
Invoke-Checked "$taskJava\java.exe" @('-jar',"$ToolsDir\build-tools\lib\apksigner.jar",'sign','--ks',"$taskSigning\jmlite.jks",'--ks-key-alias','jmlite','--ks-pass',"file:$taskSigning\password.txt",'--out',"$taskBuild\JM-Lite-2.0.1-native-checks.apk", "$taskBuild\aligned.apk")
Invoke-Checked "$taskJava\java.exe" @('-jar',"$ToolsDir\build-tools\lib\apksigner.jar",'verify','--verbose',"$taskBuild\JM-Lite-2.0.1-native-checks.apk")
if($Tests){
    New-Item -ItemType Directory -Force "$taskBuild\test-classes","$taskBuild\test-dex" | Out-Null
    $taskTests=@(Get-ChildItem "$PSScriptRoot\tests\java" -Recurse -Filter '*.java' | ForEach-Object FullName)
    Invoke-Checked "$taskJava\javac.exe" (@('-encoding','UTF-8','--release','8','-classpath',"$ToolsDir\android.jar;$taskBuild\app-classes.jar",'-d',"$taskBuild\test-classes")+$taskTests)
    Invoke-Checked "$taskJava\jar.exe" @('cf',"$taskBuild\tests.jar",'-C',"$taskBuild\test-classes",'.')
    Invoke-Checked "$ToolsDir\build-tools\aapt.exe" @('package','-f','-M',"$PSScriptRoot\tests\AndroidManifest.xml",'-I',"$ToolsDir\android.jar",'-F',"$taskBuild\tests-unsigned.apk")
    Invoke-Checked "$taskJava\java.exe" @('-cp',"$ToolsDir\r8.jar",'com.android.tools.r8.D8','--release','--min-api','26','--lib',"$ToolsDir\android.jar",'--classpath',"$taskBuild\app-classes.jar",'--output',"$taskBuild\test-dex", "$taskBuild\tests.jar")
    Push-Location "$taskBuild\test-dex"
    try{Invoke-Checked "$ToolsDir\build-tools\aapt.exe" @('add',"$taskBuild\tests-unsigned.apk",'classes.dex')}finally{Pop-Location}
    Invoke-Checked "$ToolsDir\build-tools\zipalign.exe" @('-f','4',"$taskBuild\tests-unsigned.apk", "$taskBuild\tests-aligned.apk")
    Invoke-Checked "$taskJava\java.exe" @('-jar',"$ToolsDir\build-tools\lib\apksigner.jar",'sign','--ks',"$taskSigning\jmlite.jks",'--ks-key-alias','jmlite','--ks-pass',"file:$taskSigning\password.txt",'--out',"$taskBuild\tests.apk", "$taskBuild\tests-aligned.apk")
}
