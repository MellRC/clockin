param([string]$Sdk = 'D:\AndroidSDK', [string]$Jdk = 'D:\AndroidSDK\jbr\PFiles64\Microsoft\jdk-17.0.20.101-hotspot')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.gradle-home'
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.android-home'
& "$Jdk\bin\javac.exe" -encoding UTF-8 -d output\policy-tests app\src\main\java\com\example\clockin\TaskPolicy.java app\src\main\java\com\example\clockin\LaunchEvidence.java tests\PolicyTests.java
if ($LASTEXITCODE -ne 0) { throw 'Policy test compilation failed' }
& "$Jdk\bin\java.exe" -cp output\policy-tests com.example.clockin.PolicyTests
if ($LASTEXITCODE -ne 0) { throw 'Policy tests failed' }
& .\.gradle-toolchain\gradle-8.9\bin\gradle.bat --offline --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
Write-Output 'Run FlowInstrumentation only on a development emulator. It temporarily changes app preferences and the non-secure lockscreen setting, then restores them. Do not run it on a personal phone.'
