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

Homedroid runs SSH, a web server, a Cloudflare tunnel, Tailscale and self-hosted apps (Jellyfin, Immich,
qBittorrent, Home Assistant) on any Android 10+ phone, and manages them from the phone or
from a web dashboard, where you can also deploy web apps straight from GitHub.

<p align="center">
  <img src="docs/screenshots/phone-main.png" width="260" alt="The Homedroid app: services, addresses and battery status">
  <img src="docs/screenshots/phone-apps.png" width="260" alt="The Apps screen with Jellyfin, qBittorrent, Home Assistant and Immich">
  <img src="docs/screenshots/phone-settings.png" width="260" alt="Phone settings: appearance, startup and connection controls">
</p>
<p align="center">
  <img src="docs/screenshots/dashboard-overview-dark.png" width="820" alt="The web dashboard overview: phone health and running services">
</p>

## Walkthrough

<a href="https://github.com/sudiptacmd/homedroid/releases/download/v0.8.4/homedroid-walkthrough.mp4"><img src="docs/walkthrough-preview.gif" alt="Homedroid walkthrough: phone app, SSH, web dashboard, Jellyfin, Immich, qBittorrent" width="720"></a>

A 6-minute tour of every module on an Android 10 emulator, including Tailscale, the IPCam live view and updating from the dashboard. [Watch the full video (MP4, 18 MB)](https://github.com/sudiptacmd/homedroid/releases/download/v0.8.4/homedroid-walkthrough.mp4).

## Download

Homedroid is in **beta (0.9.2)**. Get the APK from the
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

**Updating:** from 0.8.4 on, open **Settings → App updates** in the dashboard and click
**Download and install**: the phone fetches the right APK from the latest release and Android
asks you to confirm on the phone (tap the *Homedroid update ready* notification). On Android 12
and later, updates after the first one install without a prompt. You can also upload an APK
from the browser there. The server comes back on the new version by itself.

## Features

| Module | What you get | Port |
|--------|--------------|------|
| **SSH & SFTP** | Shell and file transfer, public-key login, `ssh -L` port forwarding | 8022 |
| **Web hosting** | Caddy serving `~/www`, plus sites deployed from GitHub | 8080 |
| **Cloudflare Tunnel** | Publish services on the internet: no port forwarding, works behind CGNAT | – |
| **Tailscale** | Private access to every module from your own devices, anywhere; optional HTTPS (Serve/Funnel), exit node, subnet routes. Userspace mode: no root, no VPN slot | – |
| **Jellyfin** | Media server with **hardware transcoding on the phone's video encoder** | 8096 |
| **Immich** | Google Photos-style backup (face/object recognition off: too heavy for phones) | 2283 |
| **qBittorrent** | Downloads in order into the media library (shown in Jellyfin as *Downloads*) and keeps seeding; `torrent <magnet>` over SSH | 8081 |
| **Home Assistant** | Home automation (network and cloud integrations) | 8123 |
| **IPCam** | Live view of any camera, remote photos and video (previewed in the browser), flash, microphone listening/recording and spoken announcements | 8800 |
| **AI** | Chat and an OpenAI-compatible API with models **on the phone** (llama.cpp) or cloud services you connect (OpenAI, Gemini, any compatible API); scheduled **routines** that brief you by voice, in the dashboard or as a push | 8090 |
| **Cluster** | Several phones, one dashboard: pick which phone runs each app (and move apps with their data), every camera on one page, every phone's storage in Files | 8801 |
| **Dashboard** | Manage everything from a browser: live load, files, a terminal, SSH access, deployments from Git | 8800 |

- **Everything is a module.** Add, remove, turn on or off at any time, from the phone or the
  dashboard. Nothing you don't use runs.
- **Your storage, your choice.** Keep app data inside the app (no permissions), or put media
  and photos on internal storage, a microSD card or a USB drive, per app.
- **Built to stay up.** Foreground service, wakelocks, start on boot, automatic restarts
  with backoff, per-service logs. Crash loops show their reason in the UI.
- **Light.** A single APK under 50 MB per ABI; apps are downloaded only when you install them.

## Requirements

- Android 10 or newer; arm64 recommended (Immich images are 64-bit only, the rest also runs on
  32-bit ARM). Tested on emulated Android 10 and 16; pilot device: Galaxy S9+ (Exynos 9810).
- Free storage for the apps you pick: Jellyfin ≈ 420 MB (and wants 2 GB free), Immich ≈ 1.6 GB,
  Home Assistant ≈ 900 MB.

## Getting started

1. Install the APK from [Download](#download) (or [build it](#building)).
2. Open Homedroid, go to **Settings → SSH public keys**, paste your public key
   (`~/.ssh/id_ed25519.pub`) and tap **Save**. Return to **Overview → Start server**.
   Keys can also be added later in the dashboard.
3. Open **Settings → Battery settings** and allow background running. On Samsung phones also add Homedroid to
   *Settings → Battery → Background usage limits → Never sleeping apps*.
4. Android 12+: turn off the phantom process killer (the app shows how).
5. Tap **Open control panel** to manage everything on the phone, or open
   `http://<phone-ip>:8800` from another device. **Overview → Dashboard login** shows the password.
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

To reach the phone privately from anywhere, turn on the Tailscale module and log in with the
link it shows (or paste an auth key). Every module is then at `http://homedroid:<port>` from
your devices on the tailnet, and `ssh -p 8022 homedroid` works too. Both modules have a
step-by-step **Setup guide** in the dashboard.

### The dashboard

`http://<phone-ip>:8800`, on a computer or a phone:

<p align="center">
  <img src="docs/screenshots/dashboard-files-dark.png" width="410" alt="The Files tab: browse, upload and download">
  <img src="docs/screenshots/dashboard-terminal-dark.png" width="410" alt="The Terminal &amp; SSH tab: web terminal, SSH keys, session log and history">
</p>

- **Overview:** CPU load, temperature, upload and download speed, battery, memory and storage,
  and every service with its logs and a restart button.
- **Modules:** install, remove and turn modules on or off; set up Cloudflare Tunnel and
  Tailscale with built-in guides. **Clear data** resets an app like a
  fresh install; **Remove** can also delete its data. The media or photo library is only
  deleted if you tick it separately.
- **Deployments:** web apps from Git (see [below](#deploying-from-github)).
- **Files:** browse the media and photo libraries, the SSH home (with the website in `www`),
  and phone storage, SD cards and USB drives when access is granted. Upload files or whole
  folders (drag and drop works), download files or folders (as zip), open videos, pictures and
  PDFs in the browser, rename, move and delete.
- **Terminal & SSH:** a terminal in the browser, in the phone's shell or straight into Alpine
  Linux; the SSH keys that may log in (add and remove them); a log of SSH and terminal
  sessions (who, from where, how long, what they ran, and rejected logins); and the command
  history of both shells.

It works without internet access: everything it needs is in the app.
On mobile browsers, a menu button opens the navigation sidebar with every page, theme
controls and logout. The sidebar closes after choosing a page or tapping outside.

### Settings

The phone app has **Overview**, **Apps** and **Settings** in its bottom navigation. Settings
controls appearance (phone theme, light or dark), startup on boot, SSH and web hosting,
Tailscale setup, Cloudflare Tunnel tokens, SSH public keys and the dashboard password.
It also links to battery optimization, permissions, shared storage access and updates.
Connection changes restart active services; while stopped, they apply on the next start.

The phone's **control panel** opens the local dashboard inside the app and signs in with an
expiring session. Manage files, AI, routines, deployments, camera controls and your cluster
from the Overview shortcuts. Document uploads and HTTP downloads work in the panel;
generated browser-only downloads may need the browser dashboard to save.

Open **Settings** in the browser dashboard to change the login password or whether the server
starts when the phone boots. Browser password changes require the current password. Both
phone and browser password changes sign out existing browser sessions; **Dashboard login**
in the phone's Overview shows the current password.

### IPCam

Enable **IPCam** in Modules to show it in the web dashboard sidebar. On the phone, tap
**Enable IPCam camera and microphone access** and grant both permissions. The dashboard
can then take photos, record video, switch the torch on or off, listen to or record the
microphone, and **Announce** a typed message using the phone’s text-to-speech voice.
Announcements use the current audio output and media volume.

- Choose any camera exposed by Android; capture uses one camera at a time. Flash controls
  appear only on cameras that support them.
- Capture uses a partial wake lock and does not turn on the display or play app sounds.
  Device-enforced sounds may still apply. Android’s privacy indicators and an IPCam
  notification remain active; its **Stop IPCam** action ends camera and microphone access.
- [Android requires camera and microphone foreground services to start while the app is visible](https://developer.android.com/develop/background-work/services/fgs/service-types).
  After stopping IPCam or rebooting, enable access on the phone again.
- Photos are JPEG, video is silent MP4, and microphone recordings are separate mono WAV
  files. Download completed captures from the page; files stay in the app’s private
  `camera` folder and are removed if the app is uninstalled. Recording stops after
  30 minutes, when storage is low, or at 1 GB for video.
- **Stop listening** mutes the browser. **Stop microphone** ends microphone capture and
  saves any audio recording. Use headphones when listening to avoid feedback.

**Continuous monitoring:** each camera has its own saved settings: 240p/480p/720p/1080p,
2/5/10/15/30 FPS, rotation in 90-degree steps (added to sensor orientation), motion threshold,
quiet period, and maximum clip length (5 seconds–30 minutes). Hardware may use a different
supported size or capture frame rate; the dashboard reports the actual preview configuration.
The motion encoder limits its output to the requested FPS.

Choose **Save video only when motion occurs**, then **Start continuous monitoring**. The
camera stays open with the browser closed; idle encoded frames are discarded without writing
to disk. Motion starts a silent MP4 at the next keyframe, and the quiet period ends it.
Continued motion starts another clip when the maximum length is reached. **Always running**
keeps the camera ready without automatically saving video. Only one camera monitors at a time;
stop monitoring before manual photos or video. Re-enable IPCam on the phone after a restart
to resume the saved monitoring selection.

**Capture storage:** set a shared allocation of 128–102400 MB for all camera videos, photos
and microphone recordings. Oldest completed captures are removed automatically to make room;
active recordings and files outside the capture library are protected. Lowering the allocation
also removes old captures. Download clips you want to keep. A free-space reserve protects the
phone from filling its disk; recording stops if no room can be freed.

### Cluster

Run Homedroid on several phones and manage them together. In the dashboard, open **Cluster
→ Add a phone**: phones on the same network show up by themselves (or enter an address, for
example over Tailscale). Both phones show the same 6-digit code; approve the request on the
phone being added, in its notification or its own dashboard. A phone added anywhere is
known to every phone in the cluster.

- **One dashboard.** Pick a phone at the top of the sidebar and every page shows that phone.
  The Cluster page shows the health of each one.
- **Where apps run.** The table on the Cluster page lists every app and deployment on every
  phone. **Move** an app and its settings and database go with it (its library too, if you
  choose); deployments are rebuilt from Git on the new phone. An app is turned off on the
  old phone while it moves, and back on if the move fails.
- **All cameras** shows a live view from every phone that has IPCam on, plus the latest
  captures from all of them.
- **Files** lists every phone's locations; **Copy/Move to another phone** sends files and
  folders straight between the phones.

Phones talk over mutual TLS on port 8801 and trust only the phones they were paired with.
A phone in the cluster can fully control the others: give each one a strong dashboard
password. Apps can't use another phone's storage directly, and there is no automatic
failover: if a phone is off, so are its apps until you move them.

### AI

Turn on **AI** in Modules, then open the AI page:

- **On this phone:** install llama.cpp (built for Android by this project, about 17 MB) and
  download a model (Qwen2.5, Llama 3.2 or Gemma 3), paste any Hugging Face `.gguf` link, or
  upload a model to *AI models* in Files. The page **recommends** the best model your phone
  runs well, from its memory, free space and measured speed. 64-bit phones only.
- **GPU or CPU:** *Run on: Auto / CPU / GPU*. The **Benchmark** measures both and Auto uses the
  faster: Vulkan on most phone GPUs, OpenCL on Qualcomm Adreno. A GPU whose driver crashes
  llama.cpp (older Mali GPUs do) is detected and never used.
- **Cloud services:** connect OpenAI, Google Gemini or any OpenAI-compatible API (OpenRouter,
  Groq, Ollama or LM Studio on your computer). Keys stay on the phone. A cloud model can
  stand in when the phone is too hot or its model isn't running.
- **Chat** streams answers and labels each one *on this phone* or *cloud*.
- **For other apps:** `http://<phone-ip>:8090/v1` is an OpenAI-compatible API (models and
  chat completions) with its own key, shown on the AI page. Use `local/<model>` or
  `<service>/<model>` as the model name.
- **Routines** run on a schedule: they read news feeds, web pages, the weather, the phones'
  health and unread email (IMAP with an app password; nothing is marked read), and write
  a brief that is read aloud on any phone, kept under *Briefs*, or pushed to your phone with
  ntfy or Telegram. Email only goes to a cloud model in routines where you allow it.

### Home-screen widget

Add **Homedroid status** from your launcher's widgets: every phone in the cluster (online,
battery, temperature) and the services running on this one, refreshed every minute while the
server runs. Tap it to open the dashboard; tap its title to open the app.

### Storage

App data stays inside the app by default. To use an SD card or USB drive, choose
**Storage location** on the app (phone) and pick a volume:

- Android 11+: data goes to a visible `Homedroid/<name>` folder, with *All files access*.
- Android 10: apps can only write to their own folder on removable volumes
  (`Android/data/dev.homedroid/files/<name>`). Android deletes it if Homedroid is uninstalled;
  the picker says so.

Jellyfin and qBittorrent share one *Media* folder, and installing both adds a *Downloads*
library to Jellyfin that picks up finished torrents within a minute. Databases and settings always stay in
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
  <img src="docs/architecture.svg" width="900" alt="Homedroid architecture: clients reach the phone through the dashboard, SSH, apps, the AI API, Cloudflare Tunnel or Tailscale; paired phones connect over mutual TLS; routines and the AI router use cloud services or llama.cpp running natively on the phone's GPU or CPU; supervised native daemons and apps under proot use Android storage and MediaCodec transcoding">
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

Releases are built by CI: pushing a tag such as `v0.8.1-beta` builds every ABI, signs the APKs
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
- **CPU figures.** Android doesn't let apps read system-wide CPU load or most temperature
  sensors, so the dashboard shows the load of Homedroid's own processes (which are all the
  servers), the battery temperature and Android's thermal status, plus the CPU temperature on
  phones that expose it.
- **No TLS on the LAN.** The dashboard, the AI API and apps speak plain HTTP on your network
  (traffic between cluster phones is encrypted); use Tailscale, the Cloudflare tunnel or
  `ssh -L` from outside it.
- **AI on the phone is slow and warm.** Expect a few to a dozen words a second with small
  (0.5–3B) models and a warm phone. GPU support depends on the phone's drivers; on older GPUs
  models run on the CPU. llama.cpp is available for 64-bit phones only.
- **Updates.** Apps are installed from Alpine and container registries at install time;
  update them with `apk upgrade` over SSH or by reinstalling from the Apps screen.

## Security notes

- SSH accepts public keys only. The dashboard uses a generated password; sessions are
  HttpOnly/SameSite cookies, and scripts can send the password as a Bearer token. The
  dashboard password also opens the web terminal, so treat it like an SSH key. SSH and terminal
  sessions, including rejected logins, are logged in the dashboard.
- From 0.8.5 beta, five failed password checks from a peer trigger a 15-minute cooldown,
  with a shared 50-failure budget across peers. Login, Bearer requests and password changes
  share the persisted limits. Sessions expire after 24 hours; upgrading requires a fresh
  login. See the [security review](docs/SECURITY-REVIEW-0.8.5.md).
- From 0.9.0 beta, cluster phones pair with a code checked on both phones, talk over mutual
  TLS, and can fully control each other, so the weakest dashboard password protects the
  whole cluster. The AI API has its own key; cloud keys and the email password never leave
  the phone. See the [0.9.0 security review](docs/SECURITY-REVIEW-0.9.0.md).
- Everything listens on the phone's network, so keep the phone on a network you trust, and
  expose services to the internet through the Cloudflare tunnel rather than port forwarding.
- Other apps on the same phone can reach `localhost`. Homedroid binds databases to localhost and
  protects them with generated passwords. qBittorrent skips authentication for localhost so the
  `torrent` command works.

## Roadmap

- Cluster failover: restart an app on another phone when its phone goes away
- Root mode: chroot at native speed, ports below 1024, charge limiting
- More apps: Vaultwarden, Syncthing, Pi-hole/AdGuard; Tailscale
- Hostname routing for deployments through Caddy
- Verify hardware transcoding on more SoCs (Exynos, Snapdragon, MediaTek)

## License

Homedroid is free software under the [GNU General Public License v3.0](LICENSE). It bundles and
builds third-party components under their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md).
