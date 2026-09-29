# Lindroid

Turn an old Android phone (Android 10+) into a small, always-on Linux server. No root required.

- **SSH / SFTP** on port 8022 (public-key auth only, `ssh -L` port forwarding)
- **Caddy** web server on port 8080, serving `~/www`
- **Cloudflare Tunnel** to publish it on the internet: no port forwarding, works behind CGNAT

## How it works

```
MainActivity ── settings, status, logs
     │
ServerService (foreground service + wakelock + Wi-Fi lock, starts on boot)
     │
Supervisor ── one thread per daemon, restart with exponential backoff, log rotation
     │
 lib{sshd,caddy,cloudflared}.so   ← Go executables shipped as "native libs"
```

Apps targeting SDK 29+ may only execute files from `nativeLibraryDir`, so the daemons are
cross-compiled with the NDK and packaged as `jniLibs/<abi>/lib<name>.so`. They're built with
`GOOS=android` and cgo so DNS goes through bionic. Pure-Go builds look for
`/etc/resolv.conf`, which doesn't exist on Android.

On-device layout (`/data/data/dev.lindroid/files`):

| Path | Contents |
|------|----------|
| `home/` | `$HOME` for SSH sessions and daemons |
| `home/www/` | Website root served by Caddy |
| `home/.ssh/authorized_keys` | Keys allowed to SSH in (editable from the app) |
| `etc/Caddyfile` | Caddy config. Edit over SFTP, then *Save & apply* |
| `bin/` | Symlinks `caddy`, `cloudflared`, `sshd` (on `$PATH` in SSH) |
| `logs/` | Per-daemon logs (rotated at 512 KB) |

## Building

Requires JDK 17+, Go, and the Android SDK + NDK.

```bash
native/build.sh            # cross-compile daemons (arm64-v8a, armeabi-v7a, x86_64)
./gradlew assembleRelease  # APKs in app/build/outputs/apk/release/
adb install app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Pin other daemon versions with `CADDY_VERSION=v2.x.y CLOUDFLARED_VERSION=YYYY.M.P native/build.sh`.

## Phone setup

1. Open the app, paste your public key (`~/.ssh/id_ed25519.pub`), then tap **Save & apply** and **Start server**.
2. Tap **Allow running in background** (disables battery optimization).
3. Android 12+: disable the phantom process killer (instructions are shown in the app).
4. Public website: create a tunnel in the Cloudflare Zero Trust dashboard, point its public
   hostname at `http://localhost:8080`, and paste the tunnel token into the app.
5. Keep the phone cool, and if possible limit charging to around 80%. Batteries kept at 100% for months can swell.

```bash
ssh -p 8022 <phone-ip>
sftp -P 8022 <phone-ip>   # upload your site into www/
```

## Roadmap

- [ ] Alpine rootfs (`apk add` anything). Needs a proot loader that maps binaries into
      anonymous memory (W^X blocks exec from app data on SDK 29+), or chroot on rooted phones.
- [ ] Optional root mode: chroot, ports < 1024, charge limiting
- [ ] Tailscale
- [ ] Slimmer Caddy build (only the modules we need)
- [ ] App templates (Vaultwarden, file sync, DNS ad-blocker)
