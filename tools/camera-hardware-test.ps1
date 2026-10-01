# Tests a separate package; never replaces the user's release app.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev.ps1"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $connected = @(adb devices | Select-String '^\S+\s+device$')
    if ($connected.Count -ne 1) { throw 'Connect exactly one authorized Android phone for this test.' }
    .\gradlew.bat --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' -PcameraTest=true assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Hardware test build failed' }
    adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Separate camera test app installation failed' }
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Hardware test runner installation failed' }
    foreach ($permission in 'CAMERA', 'RECORD_AUDIO', 'POST_NOTIFICATIONS') {
        adb shell pm grant dev.homedroid.cameratest "android.permission.$permission"
    }
    $result = adb shell am instrument -w dev.homedroid.cameratest.test/dev.homedroid.CameraHardwareTest
    $result | Tee-Object -FilePath .tools/camera-hardware-test.log
    if (($result -join "`n") -notmatch 'OK \(1 camera hardware test\)') { throw 'Camera hardware test failed; see .tools/camera-hardware-test.log' }
} finally {
    # These packages and recordings exist solely for this test.
    adb uninstall dev.homedroid.cameratest.test
    adb uninstall dev.homedroid.cameratest
    Pop-Location
}
