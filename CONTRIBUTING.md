# Contributing

Thanks for helping. Bug reports from real phones are especially valuable; include the phone
model, Android version, and the service's log (dashboard → Logs, or `files/logs/` over SFTP).

## Development setup

1. Install JDK 17+, Go, the Android SDK and NDK; set `ANDROID_NDK_HOME`.
2. `native/build-all.sh` builds the bundled binaries into `app/src/main/jniLibs/` (ignored by git).
3. `./gradlew assembleDebug` and install the APK for your phone or emulator's ABI.

An x86_64 emulator with KVM is the fastest way to iterate; `adb forward tcp:8800 tcp:8800`
makes the dashboard reachable on your computer. Emulator MediaCodec is a software codec,
so hardware-transcoding speed has to be checked on a real phone.

## Code layout

| Path | What |
|------|------|
| `app/src/main/java/dev/lindroid/` | The app: service, supervisor, dashboard, Alpine/proot, image puller |
| `app/src/main/assets/apps.json` | The app catalog |
| `app/src/main/assets/dashboard.html` | The web dashboard (single file, no build step) |
| `native/` | Build scripts for bundled binaries, the SSH server, and upstream patches |

The app has no third-party dependencies beyond the Kotlin standard library, and that's
deliberate: keep it light.

## Adding an app to the catalog

A single-process app runs in Alpine:

```json
{
  "id": "myapp", "name": "My App", "description": "…", "port": 1234, "size": "≈ 50 MB",
  "install": "apk add --no-cache myapp",
  "uninstall": "apk del myapp",
  "run": ["/usr/bin/myapp", "--port", "1234"],
  "env": {},
  "storage": {"path": "/var/lib/myapp/files", "label": "Files"}
}
```

A multi-process app can run container images (see `immich` in `apps.json`). Use `images`,
`services` and `arches`; `binds` take `@data/<dir>` (kept in the app's data folder) or
`@storage` (the user's chosen location), and env values may use `{{secret}}`.

Things that don't work in an Android app sandbox, and how the catalog works around them:

- **No hard links.** proot fakes them, but tools that rely on them should be told not to
  (e.g. `UV_LINK_MODE=copy`, `git config core.createObject rename`).
- **No System V IPC.** Services that need it (PostgreSQL) set `"sysvipc": true`.
- **No `/dev/ashmem`, no raw USB or Bluetooth devices, no ports below 1024.**
- **Some `/proc` files are hidden.** Lindroid provides stand-ins for `stat`, `loadavg`, `uptime`,
  `version` and `vmstat`.

## Pull requests

Keep changes focused, match the surrounding style, and describe how you tested (device,
Android version). Patches to upstream projects go in `native/patches/` as unified diffs,
with a comment explaining why.
