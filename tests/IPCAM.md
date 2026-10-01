# IPCam verification

Run the dependency-free dashboard checks from the repository root:

```sh
node tests/ipcam-dashboard.test.cjs
```

Build with the Android SDK using `./gradlew assembleDebug`. The host dashboard tests do
not exercise Android hardware. Check the following on Android 10 and a current Android
phone before release:

1. IPCam starts disabled. Enable it in Modules and verify that its sidebar entry appears.
   Permission denial leaves capture controls unavailable and explains phone setup.
2. Enable access on the phone, then lock it. Take photos on each exposed camera, with
   and without flash. Verify JPEG exposure, focus, orientation, and that the screen
   stays off. Test unsupported flash, camera privacy toggle, and another app using it.
3. Record and stop video on each camera. Download and play each MP4. Try a very short
   recording, camera disconnect, storage exhaustion, and the time/file limits. Errors
   should release the camera and incomplete files should not appear in downloads.
4. Listen in the browser, record the microphone, stop it, and play the WAV download.
   Verify playback in Chrome and Firefox, permission revocation, reconnect, and a
   background browser tab. Stop listening only mutes the browser; Stop microphone
   must end capture. Test camera and microphone recording together.
5. Announce a message; verify speaker output at the phone’s media volume, Bluetooth
   routing, and feedback while listening. Check an unavailable TTS engine and messages
   of 0, 500, and 501 characters (the API must reject 0 and 501).
6. Stop IPCam from the notification, disable its module, and stop the server during
   recordings. Verify flash, camera and microphone release and playable saved files.
   Rebooting must require enabling access on the phone again.
7. Unauthenticated camera, audio, announcement and file requests must return 401.
   With IPCam disabled they must return 409. Downloads must reject traversal and
   `.partial` names. Verify a large recording downloads without loading it into RAM.
8. Set different FPS, resolution and rotation for rear/front cameras. Reload the dashboard
   and re-arm the service; verify saved settings, preview rotation, JPEG and MP4 orientation.
   Settings/storage drafts must survive polling. Watch the live canvas for blank frames while
   switching cameras, slowing the network and closing the view during frame decoding.
9. Start motion monitoring at 480p/5 FPS, then close/background the browser for longer than
   15 seconds. Stillness should create no files; move in view and verify a playable silent MP4
   beginning at a keyframe, followed by a stop after the quiet period. Continuous movement
   should split into clips at the saved limit without reopening the camera. Check watch-only
   mode, permission revocation, unsupported encoder surfaces and Stop IPCam notification.
10. Use a small capture quota and generate videos/photos/WAV files until full. Verify oldest
    completed captures disappear first, active files are protected, usage remains bounded and
    recording stops cleanly when active recordings consume all available space. Lower the
    quota while idle and during capture; unrelated files must survive. Download important
    footage before testing eviction on a real phone.
