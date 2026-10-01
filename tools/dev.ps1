# Dot-source from PowerShell: . .\tools\dev.ps1
# Uses the project-local tools installed during development setup.
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaDirectory = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/java') -Directory -ErrorAction Stop | Select-Object -First 1
$env:JAVA_HOME = $javaDirectory.FullName
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk/30.0.16248370'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\19.0\bin;$(Join-Path $projectRoot '.tools/go/bin');$env:PATH"
Write-Host 'Homedroid tools ready: JDK 21, Android SDK 36, NDK 30, ADB and Go.'
