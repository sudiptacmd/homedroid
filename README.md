<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo/homedroid-logo-dark.svg">
    <img src="docs/logo/homedroid-logo-light.svg" alt="Homedroid: your old phone, now a home server" width="520">
  </picture>
</p>

# Homedroid

<p>
  <a href="https://github.com/sudiptacmd/homedroid/releases"><img alt="Release" src="https://img.shields.io/github/v/release/sudiptacmd/homedroid?include_prereleases&label=release&color=2ea043"></a>
  <a href="https://github.com/sudiptacmd/homedroid/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/sudiptacmd/homedroid/build.yml?branch=main"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/github/license/sudiptacmd/homedroid"></a>
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3ddc84">
</p>

**Turn an old Android phone into a home server.** No root, no Termux, one app.

Homedroid runs SSH, a web server, a Cloudflare tunnel and self-hosted apps (Jellyfin, Immich,
qBittorrent, Home Assistant) on any Android 10+ phone, and manages them from the phone or
from a web dashboard, where you can also deploy web apps straight from GitHub.

<p align="center">
  <img src="docs/screenshots/phone-main.png" width="260" alt="The Homedroid app: services, addresses and battery status">
  <img src="docs/screenshots/phone-apps.png" width="260" alt="The Apps screen with Jellyfin, qBittorrent, Home Assistant and Immich">
</p>
<p align="center">
  <img src="docs/screenshots/dashboard-overview-dark.png" width="820" alt="The web dashboard overview: phone health and running services">
</p>

## Walkthrough

<!-- walkthrough-video -->

## Download

Homedroid is in **beta (0.4.1)**. Get the APK from the
[latest release](https://github.com/sudiptacmd/homedroid/releases):

| Your phone | APK |
|------------|-----|
| Almost every phone from the last ~8 years (64-bit) | `homedroid-<version>-arm64-v8a.apk` |
| Older 32-bit phones | `homedroid-<version>-armeabi-v7a.apk` |
| Emulators, Chromebooks | `homedroid-<version>-x86_64.apk` |
| Not sure | `homedroid-<version>-universal.apk` (larger, runs everywhere) |

Open it on the phone and allow installing from your browser or file manager, or run
`adb install homedroid-<version>-arm64-v8a.apk`. `SHA256SUMS` in each release lets you check
the download. Every release is signed with the same key, so newer versions install over older
ones and keep your data.

## Features

| Module | What you get | Port |
|--------|--------------|------|
| **SSH & SFTP** | Shell and file transfer, public-key login, `ssh -L` port forwarding | 8022 |
| **Web hosting** | Caddy serving `~/www`, plus sites deployed from GitHub | 8080 |
| **Cloudflare Tunnel** | Publish services on the internet: no port forwarding, works behind CGNAT | – |
| **Jellyfin** | Media server with **hardware transcoding on the phone's video encoder** | 8096 |
| **Immich** | Google Photos-style backup (face/object recognition off: too heavy for phones) | 2283 |
| **qBittorrent** | Downloads in order into the media library and keeps seeding; `torrent <magnet>` over SSH | 8081 |
| **Home Assistant** | Home automation (network and cloud integrations) | 8123 |
| **Dashboard** | Manage everything from a browser; deploy Node.js, Python and static sites from Git | 8800 |

- **Everything is a module.** Add, remove, turn on or off at any time, from the phone or the
  dashboard. Nothing you don't use runs.
- **Your storage, your choice.** Keep app data inside the app (no permissions), or put media
  and photos on internal storage, a microSD card or a USB drive, per app.
- **Built to stay up.** Foreground service, wakelocks, start on boot, automatic restarts
  with backoff, per-service logs. Crash loops show their reason in the UI.
- **Light.** A single APK under 40 MB per ABI; apps are downloaded only when you install them.

## Requirements

- Android 10 or newer; arm64 recommended (Immich images are 64-bit only, the rest also runs on
  32-bit ARM). Tested on emulated Android 10 and 16; pilot device: Galaxy S9+ (Exynos 9810).
- Free storage for the apps you pick: Jellyfin ≈ 420 MB (and wants 2 GB free), Immich ≈ 1.6 GB,
  Home Assistant ≈ 900 MB.

## Getting started

1. Install the APK from [Download](#download) (or [build it](#building)).
2. Open Homedroid, paste your SSH public key (`~/.ssh/id_ed25519.pub`), tap **Save & apply**,
   then **Start server**.
3. Tap **Allow running in background**. On Samsung phones also add Homedroid to
   *Settings → Battery → Background usage limits → Never sleeping apps*.
4. Android 12+: turn off the phantom process killer (the app shows how).
5. Open the dashboard at `http://<phone-ip>:8800` with the password shown in the app.
6. Keep the phone cool and, if you can, limit charging to about 80%.

```bash
ssh -p 8022 <phone-ip>                       # shell; `alpine` opens a root shell in Alpine Linux
sftp -P 8022 <phone-ip>                      # files; put your website in www/
ssh -p 8022 <phone-ip> torrent 'magnet:?…'   # download, in order, into Media/Downloads
ssh -p 8022 <phone-ip> torrent list          # progress
```

To publish a service on the internet, create a tunnel in the Cloudflare Zero Trust dashboard,
point a public hostname at `http://localhost:<port>`, and turn on the Cloudflare Tunnel module
with the tunnel token.

### Storage

App data stays inside the app by default. To use an SD card or USB drive, choose
**Change location** on the app (phone) and pick a volume:

- Android 11+: data goes to a visible `Homedroid/<name>` folder, with *All files access*.
- Android 10: apps can only write to their own folder on removable volumes
  (`Android/data/dev.homedroid/files/<name>`). Android deletes it if Homedroid is uninstalled;
  the picker says so.

Jellyfin and qBittorrent share one *Media* folder. Databases and settings always stay in
internal storage, because SD cards and USB drives are usually FAT/exFAT. If the card is removed,
apps wait for it instead of writing elsewhere. With storage access, every volume also appears
inside Alpine at `/storage/…`, so existing folders can be added to Jellyfin.

### Deploying from GitHub

<img src="docs/screenshots/dashboard-deploys-dark.png" width="820" alt="The Deployments tab">

Give a repository URL, branch and port. Node.js, Python and static sites are detected; build and
start commands and environment variables can be set. Apps get `$PORT`; static sites are served
by Caddy. **Redeploy** pulls and rebuilds, and a failed build keeps the old version running.
**Auto-deploy** checks the branch every 5 minutes, so no public webhook is needed. For private
repositories use `https://<token>@github.com/you/repo` with a read-only token; it is never shown
back in the dashboard.

## How it works

<p align="center">
  <img src="docs/architecture.svg" width="900" alt="Architecture: your devices reach the phone; inside the Homedroid app a foreground service runs the dashboard and a supervisor, which starts native daemons and apps under proot (Alpine Linux and container images); Jellyfin transcodes through an Android-native FFmpeg to the phone's MediaCodec encoder">
</p>

**Running Linux software without root.** Apps targeting Android 10+ may only execute files
from their native-library directory, and still map others with `mmap(PROT_EXEC)`. Homedroid
ships its daemons as `lib*.so` in the APK and runs everything else under
[proot](https://github.com/termux/proot), whose loader maps binaries from app storage. Alpine
Linux (3 MB, checksum-verified) is downloaded on first use and unpacked by a small extractor
([`Tar.kt`](app/src/main/java/dev/homedroid/Tar.kt)). Android 10's own `tar` can't cope with
the ownership changes apps aren't allowed to make.

**Container images without Docker.** [`Oci.kt`](app/src/main/java/dev/homedroid/Oci.kt) pulls
images from Docker Hub or ghcr.io. It picks the phone's architecture, verifies every layer's
digest, and applies whiteouts. Hard links become copies, since Android forbids them in app
storage, and absolute symlinks resolve inside the image. Immich runs from its official images
this way.

**Hardware transcoding.** The phone's video encoder is only reachable through Android's
MediaCodec (bionic `libmediandk` and binder), not VAAPI. Homedroid builds an Android-native
FFmpeg with MediaCodec. It presets Jellyfin's V4L2 option and points it at a shim that runs
that FFmpeg (`h264_v4l2m2m` → `h264_mediacodec`) and Jellyfin's own FFmpeg for everything
else. Android's `/system`, `/apex` and `/vendor` are bind-mounted into Alpine so the bionic
binary can run there.

### Patches

`native/patches/` holds the changes that make upstream code work in Android's app sandbox:

| Patch | Why |
|-------|-----|
| `proot-fork-to-clone` | Android rejects `fork`/`vfork` on x86_64 and armv7, and musl uses them; rewrite them as `clone(SIGCHLD)`. |
| `proot-sigsys-fork` | The same rewrite for syscalls the sandbox traps with SIGSYS, so proot's fast seccomp mode works everywhere (≈50× faster for syscall-heavy work). |
| `proot-sigsys-syscall-number` | On x86, proot's SIGSYS emulation read the syscall number from a register it had already overwritten, breaking `rename(2)` and with it `uv`. |
| `proot-sysvipc-memfd` | Apps targeting Android 10+ can't open `/dev/ashmem`; back emulated System V shared memory (PostgreSQL) with `memfd`. |
| `proot-netlink-reads` | Android lets apps read rtnetlink but not write it; proot took that as "netlink blocked" and faked empty replies, hiding every IPv4 address. |
| `proot-android-default-routes` | Android keeps default routes in per-network policy tables, so Linux software thought it was offline (libtorrent: no tracker announces, no DHT). Report them in the main table. |
| `proot-android-siocgifname` | Android denies `SIOCGIFNAME` to apps, breaking `if_indextoname(3)`; answer it from the tracer. |
| `ffmpeg-mediacodec-extradata-without-eos` | MP4/HLS headers were probed with a dummy frame plus end-of-stream; some encoders stay at EOS afterwards. |
| `ffmpeg-mediacodec-parameter-sets` | Codec2 encoders report SPS/PPS in the output format; capture them and repeat them before every key frame. |
| `ffmpeg-mediacodec-sync-frames` | Honour forced key frames (HLS segment boundaries) with `request-sync`, and start with a key frame after the probe. |

The supervisor starts every process in its own session and signals whole process groups:
proot ignores SIGTERM, and killing only proot would orphan the program it runs.

## Building

Needs JDK 17+, Go 1.23+ and the Android SDK with NDK r27+ (`ANDROID_NDK_HOME`).

```bash
native/build-all.sh                 # sshd, Caddy, cloudflared, proot, FFmpeg for arm64, armv7, x86_64
./gradlew assembleRelease           # APKs in app/build/outputs/apk/release/
adb install app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

The individual scripts take ABIs as arguments (`native/build-ffmpeg.sh arm64-v8a`), and
versions can be overridden (`CADDY_VERSION=… CLOUDFLARED_VERSION=… native/build.sh`). To sign
release builds with your own key, copy `keystore.properties.example` to `keystore.properties`;
without it they use the debug key.

Releases are built by CI: pushing a tag such as `v0.4.1-beta` builds every ABI, signs the APKs
with the project key (kept in repository secrets) and publishes them with `SHA256SUMS`.

### Adding an app

Apps are data: add an entry to [`apps.json`](app/src/main/assets/apps.json). A simple app runs
an install script and a command in Alpine (see Jellyfin). A multi-service app pulls
container images and runs several processes, with `{{secret}}` passwords and `@data`/`@storage`
binds (see Immich). See [CONTRIBUTING.md](CONTRIBUTING.md).

## Measurements

Measured with [`tools/bench.py`](tools/bench.py), which drives a phone over adb and SSH;
run it on your phone and open an issue with the output to add a column.

| | Emulator (Android 10, x86_64, 3 GB RAM) | Galaxy S9+ (Exynos 9810, 6 GB) |
|---|---|---|
| **Idle RAM**, SSH + web only | 136 MB (3 processes) | pending |
| **Idle RAM**, + Jellyfin, qBittorrent, Immich | 1.19 GB (34 processes; Immich ≈ 800 MB) | pending |
| **Idle CPU**, SSH + web only | 0.1 % of one core | pending |
| **Idle CPU**, + Jellyfin, qBittorrent, Immich | 2.6 % of one core | pending |
| **Battery** | not measurable: emulated battery | pending |
| **Network**, phone → computer | 457 MB/s HTTP, 194 MB/s SSH (virtual NIC) | pending |
| **Transcoding**, 1080p → 720p H.264 | 114 fps (3.8× real time) through MediaCodec¹ | pending |
| **Thermals**, 5 min of continuous transcoding | not measurable: fixed 25 °C | pending |
| **Uptime** | <!-- soak --> | pending |

¹ The emulator's MediaCodec encoder is software running on the host, and the same encode in
software x264 ran at 202 fps on its 12-thread desktop CPU. On a phone MediaCodec uses the
dedicated video hardware, which is the point: it keeps the CPU free and cool.

## Known limitations

- **Beta.** Tested end to end on emulated Android 10 and 16; the first real-device pilot
  (Galaxy S9+) is in progress.
- **No root, so:** no ports below 1024 (use 8080 or the Cloudflare tunnel), and proot adds
  overhead to syscall-heavy work such as package installs, Home Assistant's startup and
  databases. Programs are unaffected once they are computing.
- **Android can still stop it.** Vendor battery managers (Samsung, Xiaomi, …) and, on Android
  12+, the phantom process killer need the one-time settings described in
  [Getting started](#getting-started).
- **No device access from apps.** Home Assistant can't use USB radios (Zigbee, Z-Wave) or
  Bluetooth; nothing can use raw USB, serial or GPIO.
- **Hardware transcoding is encode-only.** Decoding, scaling, subtitle burn-in and HDR tone
  mapping run in software; only H.264 and HEVC encoding use MediaCodec, and quality and
  speed depend on the phone's encoder.
- **Immich** runs on 64-bit phones only, with machine learning (faces, search) turned off, and
  without the old pgvecto.rs extension, whose IPC the app sandbox blocks (new installs don't
  need it).
- **Storage on SD cards.** On Android 10 data on removable volumes lives in the app's own folder,
  which Android deletes when Homedroid is uninstalled. FAT32 cards can't hold files over 4 GB.
- **No TLS on the LAN.** The dashboard and apps speak plain HTTP on your network; use the
  Cloudflare tunnel or `ssh -L` from outside it.
- **Updates.** Apps are installed from Alpine and container registries at install time;
  update them with `apk upgrade` over SSH or by reinstalling from the Apps screen.

## Security notes

- SSH accepts public keys only. The dashboard uses a generated password; sessions are
  HttpOnly/SameSite cookies, and scripts can send the password as a Bearer token.
- Everything listens on the phone's network, so keep the phone on a network you trust, and
  expose services to the internet through the Cloudflare tunnel rather than port forwarding.
- Other apps on the same phone can reach `localhost`. Homedroid binds databases to localhost and
  protects them with generated passwords. qBittorrent skips authentication for localhost so the
  `torrent` command works.

## Roadmap

- Root mode: chroot at native speed, ports below 1024, charge limiting
- More apps: Vaultwarden, Syncthing, Pi-hole/AdGuard; Tailscale
- Hostname routing for deployments through Caddy
- Verify hardware transcoding on more SoCs (Exynos, Snapdragon, MediaTek)

## License

Homedroid is free software under the [GNU General Public License v3.0](LICENSE). It bundles and
builds third-party components under their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md).
