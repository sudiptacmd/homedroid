# Homedroid Desktop

A small companion program (one static binary, about 1 MB, no runtime, no async framework) that
connects your computer to your Homedroid phone:

- **Webcam**: the phone's camera becomes a webcam. On Linux it feeds a `v4l2loopback` device,
  on Windows 11 a virtual camera (experimental), and everywhere it is re-served as an MJPEG
  network camera at `http://127.0.0.1:8891/stream` for OBS and friends.
- **MacroPads**: buttons on the phone press hotkeys, media keys, type text, open files and
  URLs, launch programs and (if you allow it) run commands on the computer.

The computer always connects **to the phone** (plain HTTP on the dashboard port, 8800), so no
firewall prompt is needed on the computer. Use Tailscale if you want it away from home. The wire
format is in [`docs/desktop-protocol.md`](../docs/desktop-protocol.md).

## Install

Download `homedroid-desktop-<version>-<platform>` from the
[GitHub releases](https://github.com/sudiptacmd/homedroid/releases) page (Linux x86_64/aarch64
static, Windows x86_64, macOS universal) and unpack it. On Linux/macOS:

```sh
tar xf homedroid-desktop-*-linux-x86_64.tar.gz
./homedroid-desktop
```

With no arguments it starts the local control page at <http://127.0.0.1:8891> (and opens it in
your browser) where you can pair, start the webcam, pick camera/size/fps, allow commands, watch
a live preview and see the recent MacroPads actions.

## Pair

On the phone open **Desktop** and tap **Pair a computer**; it shows a 6-digit code valid for five
minutes. Then either use the page above or:

```sh
homedroid-desktop pair 192.168.1.20 123456      # port 8800 is the default
homedroid-desktop run                           # MacroPads
homedroid-desktop webcam --camera back --size 1280x720 --fps 30 --quality 80 [--mirror]
homedroid-desktop status                        # connection, fps, latency, virtual camera
homedroid-desktop unpair                        # forget the pairing on this computer
```

`webcam` and `run --webcam` also run MacroPads and the local page. `--no-browser` stops the page
opening a browser, `--dry-run` validates and logs MacroPads actions without performing them.
Remove the computer on the phone to revoke its token.

Config (token, address, "Allow commands") is a JSON file, mode 0600:
`~/.config/homedroid-desktop/config.json` (`$XDG_CONFIG_HOME` respected), or
`%APPDATA%\Homedroid\desktop.json` on Windows. `"allowCommands": false` by default.

## Linux webcam (v4l2loopback)

Install the kernel module, then create a device:

```sh
# Debian / Ubuntu            sudo apt install v4l2loopback-dkms
# Fedora (RPM Fusion)        sudo dnf install akmod-v4l2loopback
# Arch                       sudo pacman -S v4l2loopback-dkms linux-headers
sudo modprobe v4l2loopback exclusive_caps=1 card_label="Homedroid Camera"
```

To load it at every boot: `echo v4l2loopback | sudo tee /etc/modules-load.d/v4l2loopback.conf`
and `echo 'options v4l2loopback exclusive_caps=1 card_label="Homedroid Camera"' | sudo tee
/etc/modprobe.d/v4l2loopback.conf`. The first loopback device found is used (`--device
/dev/videoN` picks one). Frames are JPEG-decoded on the computer and written as YUYV; when
frames arrive faster than they are consumed only the newest is kept (no queueing). Without the
module the preview and `/stream` still work, and `status` says how to fix it.

## MacroPads input backends

- **X11**: XTEST through a pure-Rust X11 client. Unicode text works by briefly remapping a spare
  keycode.
- **Wayland / no X**: `/dev/uinput` (a virtual keyboard and mouse). Your user needs access:

  ```sh
  sudo modprobe uinput
  echo 'KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="input", MODE="0660"' | sudo tee /etc/udev/rules.d/70-homedroid-input.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger
  sudo usermod -aG input $USER      # then log out and in
  ```

  uinput types ASCII directly; other characters use the GTK/IBus `Ctrl+Shift+U` hex entry.
  `mouse` `move` is relative here (a virtual device cannot know the screen).
- **Windows**: `SendInput` (Unicode text with `KEYEVENTF_UNICODE`, `VK_MEDIA_*`/`VK_VOLUME_*`).
  It cannot type into elevated windows unless Homedroid Desktop is elevated too.
- **macOS**: CoreGraphics events (grant *Accessibility* in System Settings). Volume and mute use
  `osascript`; play/next/previous drive the Music app. Experimental, untested.

`mouse` `move` takes absolute screen pixels on X11, Windows and macOS.

## Windows virtual camera (Windows 11, new and experimental)

`homedroid-desktop.exe` plus `homedroid_vcam.dll` (a COM media source for
`MFCreateVirtualCamera`) are in the Windows zip. Keep them in one folder and run **once from an
elevated prompt**:

```bat
homedroid-desktop.exe install-camera
homedroid-desktop.exe webcam
```

"Homedroid Camera" then appears in Teams, Zoom, the Camera app and browsers. Frames are passed
through a shared-memory slot (1280x720, scaled and letterboxed). This code could only be
compile-checked by the author; please report problems. **Windows 10** has no virtual camera API:
use the stream URL in OBS and start OBS's own *Virtual Camera*.

## macOS

macOS only allows virtual cameras that ship as a signed system extension, which this project does
not do. Use `http://127.0.0.1:8891/stream` as a Browser/Media source in OBS and enable
*Start Virtual Camera* there. MacroPads works (see above).

## OBS / any MJPEG consumer

Start the webcam, then add a **Media Source** (untick *Local File*) or **Browser** source with
`http://127.0.0.1:8891/stream`, or open it in VLC/ffplay/a browser `<img>`.

## Security notes

- The local page and `/stream` bind to **127.0.0.1 only**. State-changing requests need a random
  per-run token (embedded in the page) and a matching `Origin`; the page has a strict CSP and
  the Host header is checked.
- The phone token is stored 0600, and only a SHA-256 hash is kept on the phone.
- Traffic to the phone is plain HTTP, like the dashboard. Pair and run on a trusted network or
  over Tailscale.
- `command` actions are refused unless **Allow commands** is enabled locally; the phone cannot
  change that. `launch` and `open` run only what the paired phone asks, so pair only your phone.
- Everything from the phone is validated (key names, lengths, sizes, nesting, delays) before use;
  malformed input yields a failed acknowledgement, never a panic.

## Build from source

```sh
cd desktop
cargo build --release              # needs a C linker for the host target, e.g. gcc
```

On a machine without a C compiler (static Linux binary):

```sh
rustup target add x86_64-unknown-linux-musl
RUSTFLAGS="-C linker=rust-lld -C link-self-contained=yes" \
  cargo build --release --target x86_64-unknown-linux-musl
```

Windows: `cargo build --release --workspace` (MSVC) produces `homedroid-desktop.exe` and
`homedroid_vcam.dll`. Tests: `cargo test` (parsers, key names, JPEG to YUYV, config, action
validation). `tests/mock_phone.py` is a fake phone for manual end-to-end runs:

```sh
python3 tests/mock_phone.py &                         # pairing code 123456 on 127.0.0.1:18800
homedroid-desktop pair 127.0.0.1:18800 123456
homedroid-desktop webcam --no-browser --dry-run
```

## License

GPL-3.0-only, like the rest of Homedroid.
