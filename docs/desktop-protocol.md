# Homedroid Desktop protocol (v1)

Homedroid Desktop is a small companion program for Windows, Linux and macOS. It turns the
phone's camera into a webcam on the computer and carries out MacroPads buttons (hotkeys,
media keys, typing, launching apps) on the computer.

**The computer always connects to the phone**, never the other way round: no firewall prompts
on the computer, and it works anywhere the computer can reach the dashboard (LAN, Tailscale,
another cluster phone's address). Everything below is on the dashboard port (8800, plain
HTTP like the dashboard; use Tailscale for remote use).

## Pairing

1. The user opens **Desktop** in the phone app or dashboard and taps **Pair a computer**. The
   phone shows a 6-digit code, valid for 5 minutes, single use.
2. The desktop app asks for the phone's address and the code and calls:

```
POST /desktop/v1/pair
{"code": "123456", "name": "Sudipta's laptop", "os": "windows|linux|macos", "version": "0.9.5"}
→ 200 {"token": "<64 hex chars>", "computerId": "c-3f2a9b", "phone": "Pixel 6", "phoneId": "<cluster id or install id>"}
→ 403 {"error": "That code is wrong or expired"}
```

Wrong codes are rate limited (5 per minute per address). The token is stored by the desktop
app in its config file (`~/.config/homedroid-desktop/config.toml`, or `%APPDATA%\Homedroid\
desktop.toml`), and on the phone only as a SHA-256 hash. Paired computers are listed and
can be removed on the phone; a removed computer's requests get 401 and it shows "Pair again".

Every other request carries `Authorization: Bearer <token>`.

```
GET /desktop/v1/hello → {"phone": "Pixel 6", "computerId": "c-3f2a9b", "cameras": [{"id": "0", "facing": "back", "sizes": [[1920,1080],[1280,720],[640,480]], "fps": [15,30]}], "features": ["webcam", "macros"]}
```

## Webcam

```
GET /desktop/v1/webcam?camera=0&width=1280&height=720&fps=30&quality=80
```

Response: `200`, `Content-Type: multipart/x-mixed-replace; boundary=hdframe`, then for every
frame, as soon as it is encoded:

```
--hdframe\r\n
Content-Type: image/jpeg\r\n
Content-Length: <n>\r\n
X-Frame: <sequence number>\r\n
X-Time: <capture time, ms since epoch on the phone>\r\n
\r\n
<n bytes of JPEG>\r\n
```

- The phone picks the nearest supported size and frame rate; the first frame's JPEG tells
  the real size. `camera` may also be `front` or `back`.
- Low latency over completeness: when the connection can't keep up, the phone drops frames
  rather than queueing them (it always sends the newest one).
- One webcam stream at a time per phone; a second one gets `409` while the first is open.
- Closing the connection stops the camera (unless the phone's IPCam is using it).
- `POST /desktop/v1/webcam/control {"torch": true, "zoom": 1.5, "focus": "auto|tap", "x":0.5, "y":0.5, "mirror": false}`
  adjusts the running stream (optional on both sides).

## MacroPads events

The desktop app keeps one long-lived request open:

```
GET /desktop/v1/events
→ 200, Content-Type: application/x-ndjson, chunked; one JSON object per line:
{"type": "hello", "computerId": "c-3f2a9b"}
{"type": "ping"}                                   (every 15 s; reconnect if nothing for 45 s)
{"type": "action", "id": "a-17", "action": {...}}  (see below)
```

and acknowledges each action:

```
POST /desktop/v1/ack {"id": "a-17", "ok": true}  or  {"id": "a-17", "ok": false, "error": "No such app"}
```

Actions not acknowledged within 10 s show as failed on the deck. When the events request
drops, the desktop app reconnects with backoff (1 s, 2 s, 5 s, then every 10 s).

### Action objects

| `kind` | fields | effect on the computer |
|---|---|---|
| `hotkey` | `keys`: e.g. `["ctrl","shift","m"]` | press all, release in reverse |
| `key` | `key`: one key name, `repeat` (default 1) | tap a key |
| `text` | `text` | types the text (Unicode) |
| `media` | `key`: `play_pause`, `next`, `previous`, `stop`, `volume_up`, `volume_down`, `mute` | media key |
| `open` | `target`: URL, file or folder | opens with the default app |
| `launch` | `path`, `args` (array) | starts a program |
| `command` | `command`, `shell` (bool) | runs a command; **refused unless the user turned on "Allow commands" in the desktop app** |
| `mouse` | `action`: `click`/`right_click`/`move`/`scroll`, `x`,`y` (move), `dy` (scroll) | mouse input |
| `sequence` | `steps`: array of actions, each may have `delayMs` before it | runs steps in order |

Key names: `ctrl`, `shift`, `alt`, `meta` (Windows/Command key), `enter`, `tab`, `escape`,
`space`, `backspace`, `delete`, `up`, `down`, `left`, `right`, `home`, `end`, `pageup`,
`pagedown`, `f1`–`f24`, `a`–`z`, `0`–`9`, `printscreen`, and single printable characters.

Every action object may have `"computer": "<computerId>"` on the phone side; the phone only
sends it to that computer (or to all connected ones when absent).

## MacroPads on the phone (for reference)

Stored in `files/macropads.json`:

```json
{
  "version": 1,
  "profiles": [{
    "id": "p-default", "name": "Default", "rows": 3, "cols": 5,
    "pages": [{
      "id": "pg-1", "name": "Main",
      "buttons": [{
        "id": "b-1", "row": 0, "col": 0,
        "label": "Mute mic", "icon": "mic-off", "emoji": null,
        "color": "#1F6FEB", "textColor": "#FFFFFF",
        "action": {"kind": "hotkey", "keys": ["ctrl", "shift", "m"]},
        "computer": null,
        "toggle": {"label": "Unmute", "icon": "mic", "color": "#DA3633"}
      }]
    }]
  }]
}
```

Phone-local button kinds (handled on the phone, not sent): `{"kind": "page", "page": "pg-2"}`,
`{"kind": "profile", "profile": "p-x"}`, `{"kind": "back"}`, and `{"kind": "http", "method",
"url", "body"}` (calls a URL from the phone, e.g. Home Assistant webhooks).

Dashboard API (session-authenticated, like the rest of `/api`):

- `GET /api/macropads` → the JSON above plus `"computers": [{"id","name","os","online","lastSeen"}]`
- `PUT /api/macropads` (whole document, validated)
- `POST /api/macropads/press {"profile","page","button"}` → `{"ok": true}` or error (test a button from the dashboard)
- `POST /api/desktop/pair` → `{"code": "123456", "expires": <ms>}`;
  `GET /api/desktop/computers`; `DELETE /api/desktop/computers/<id>`
