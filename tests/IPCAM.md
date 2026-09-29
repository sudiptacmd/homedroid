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
