# Changelog

What changed in each Homedroid release, newest first. Each release also has fuller notes in
[`docs/releases`](docs/releases) and on the [releases page](https://github.com/sudiptacmd/homedroid/releases).

## 0.9.6.2 beta

- **Tailscale starts on more phones:** on some Android 11+ phones (reported on Samsung One UI,
  Android 15) the Tailscale module crashed at startup with
  `netmon.New: route ip+net: netlinkrib: permission denied`, because Android blocks the call that
  lists network interfaces. Tailscale now falls back to a call Android still allows. Phones where
  it already worked are unaffected. Reported in
  [#17](https://github.com/sudiptacmd/homedroid/issues/17).

See the [0.9.6.2 release notes](docs/releases/v0.9.6.2-beta.md).

## 0.9.6.1 beta

- **A calmer sidebar:** the heavy scrollbar on the slim Rail and the sidebar is gone. A soft
  fade on the cut-off edge and a small chevron now show that the menu scrolls.
- **Less empty space on big screens:** the Overview fills wide monitors (the Terminal theme's list
  style used to stop at 820 px), services flow into columns, vitals sit side by side, and content
  is centred instead of hugging the left edge.
- **Fixed:** a stray 1 px page scrollbar on short pages.

See the [0.9.6.1 release notes](docs/releases/v0.9.6.1-beta.md).

## 0.9.6 beta

- **Automated movies and TV:** Prowlarr, Radarr, Sonarr and Jellyseerr (now called Seerr) join
  the app library. Ask for a movie or show and the phone finds it, downloads it with
  qBittorrent and files it in Jellyfin. Homedroid wires the apps together, so there are no API
  keys to copy between them. Sonarr is version 5, Sonarr's test release, because version 4
  can't run on Android. Requested in
  [#15](https://github.com/sudiptacmd/homedroid/issues/15).
- **Know what each module costs:** typical memory and CPU use on every module, in the
  dashboard, the phone app and [the module table in the README](README.md#how-much-memory-and-cpu-each-module-needs).
- **A tighter phone app:** a denser Overview that fits small phones and spreads out on
  tablets, one-row app actions, and less empty space throughout.
- **Fixed:** the web terminal, which didn't open in 0.9.5.
- **Security updates** for the Go libraries inside Caddy, cloudflared and Tailscale.

See the [0.9.6 release notes](docs/releases/v0.9.6-beta.md).

## 0.9.5 beta

- **A redesigned phone app:** Material 3, wallpaper colours on Android 12+, OLED black and
  burn-in protection. The app library and settings have been rebuilt too.
- **A dashboard you can arrange:** six layouts (sidebar, rail, top bar, split, focus and kiosk),
  13 themes, font choices, overview widgets and a Ctrl/Cmd+K command palette. Preferences live
  on the phone and follow you between browsers.
- **Dock mode:** five clock faces, live server tiles, a red night mode and OLED care. Works
  while charging or as Android’s screen saver. Four status widget sizes and a new clock widget.
- **MacroPads + Homedroid Desktop:** custom phone buttons for your computer, plus a wireless
  phone webcam. Linux, Windows and macOS companion downloads below.
- **Hosting from the dashboard:** add your own domain, keep dynamic DNS (DuckDNS or Cloudflare)
  updated, set firewall rules and read request logs. Go, PHP, Bun and Deno join Node.js, Python
  and static deployments. Upload ZIP/tar archives, trigger deploys from CI, run build pipelines
  and roll deployments across load-balanced cluster phones.
- **MCP and databases:** let AI assistants such as Claude, Cursor and VS Code manage the server
  over MCP, with write tools off until you allow them and an on-phone agent that asks before
  it changes anything. Browse SQLite databases with a schema diagram and a SQL console, or use
  the JSON document store.
- **Your own mail server:** install Stalwart from the app catalog and follow the guided setup
  in the dashboard. It lists the DNS records to copy, runs health checks and shows the settings
  for your mail apps. Many home internet providers block port 25, so you may need a relay.
- **Wi-Fi repeater:** the phone hosts a Wi-Fi Direct network and shares its connection through
  a built-in HTTP, SOCKS5 and PAC proxy. Join with a QR code. It is proxy-based, so apps that
  ignore proxy settings will not work.
- **Security and fixes:** PIN/biometric app lock, Keystore-encrypted secrets, DNS install fixes,
  configurable SSH port, a terminal that works with SSH off, and installed-app updates from
  the phone or dashboard.

- **Camera controls and a custom MacroPads look:** zoom, torch, focus, exposure, white balance
  and presets for the PC webcam; backgrounds, fonts and icons for the button deck.

See the [0.9.5 release notes](docs/releases/v0.9.5-beta.md).

## Older releases

- [0.9.4 beta](docs/releases/v0.9.4-beta.md)
- [0.9.3 beta](docs/releases/v0.9.3-beta.md)
- [0.9.2 beta](docs/releases/v0.9.2-beta.md)
- [0.9.1 beta](docs/releases/v0.9.1-beta.md)
- [0.9.0 beta](docs/releases/v0.9.0-beta.md)
- [0.8.5 beta](docs/releases/v0.8.5-beta.md)
- [0.8.4](docs/releases/v0.8.4.md)
- [0.8.3](docs/releases/v0.8.3.md)
- [0.8.1 beta](docs/releases/v0.8.1-beta.md)
- [0.4.1 beta](docs/releases/v0.4.1-beta.md)
