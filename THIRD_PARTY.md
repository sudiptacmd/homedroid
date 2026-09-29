# Third-party components

The Homedroid APK contains binaries built from these projects by the scripts in `native/`.
Their source code is available from the upstream projects below; Homedroid's modifications
are the patch files in `native/patches/`.

| Component | Version | License | Source |
|-----------|---------|---------|--------|
| Caddy | v2.11.4 | Apache-2.0 | https://github.com/caddyserver/caddy |
| cloudflared | 2026.9.3 | Apache-2.0 | https://github.com/cloudflare/cloudflared |
| PRoot (Termux fork) | 5.1.107.95 | GPL-2.0-or-later | https://github.com/termux/proot |
| talloc | 2.4.3 | LGPL-3.0-or-later | https://talloc.samba.org |
| FFmpeg | 9.0.2 (LGPL build, no GPL components enabled) | LGPL-2.1-or-later | https://ffmpeg.org |
| golang.org/x/crypto | see `native/sshd/go.sum` | BSD-3-Clause | https://go.googlesource.com/crypto |
| github.com/creack/pty | see `native/sshd/go.sum` | MIT | https://github.com/creack/pty |
| github.com/pkg/sftp | see `native/sshd/go.sum` | BSD-2-Clause | https://github.com/pkg/sftp |
| xterm.js and its fit addon (web terminal, in `app/src/main/assets/vendor`) | 6.0.0 / 0.11.0 | MIT | https://github.com/xtermjs/xterm.js |

## Downloaded at run time

These are not part of Homedroid. They are downloaded from their official sources when the
user installs the corresponding app, and remain under their own licenses:

- Alpine Linux and its packages (Jellyfin, qBittorrent, Valkey, Python, …): https://alpinelinux.org
- Home Assistant and its Python dependencies: https://www.home-assistant.io
- Immich server and database container images: https://immich.app
