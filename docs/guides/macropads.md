# MacroPads and Desktop webcam

MacroPads turns your phone into a deck of custom buttons for your computers. Open
**Tools → MacroPads** in Homedroid. The deck fills the screen, works in portrait or
landscape, and keeps the display awake while open. It follows Homedroid's theme,
dynamic colors and app lock.

## Pair a computer

1. Start Homedroid's server. Connect the computer to an address that can reach the
   phone's dashboard port (8800 by default).
2. In MacroPads, tap the computer icon, then **Pair a computer**.
3. Install Homedroid Desktop from
   [Homedroid releases](https://github.com/sudiptacmd/homedroid/releases).
4. Run the command shown on the phone, for example:

   ```sh
   homedroid-desktop pair http://192.168.1.20:8800 123456
   ```

   You can also enter the phone address and code in the companion's local page.
5. Keep the desktop companion running. The connection line on the phone shows each
   computer as online or offline. Tap it to manage computers.

Codes expire in five minutes and work once. Generating another code replaces the old
one. After five wrong attempts from an address, wait until its one-minute window
expires. You can pair up to eight computers. **Unpair** revokes the computer's token,
closes its streams and fails its pending actions; reconnecting requires a new code.
The phone stores only token hashes. Use a trusted LAN or Tailscale: the dashboard's
plain HTTP port does not encrypt pairing codes, bearer tokens or webcam frames.

## Use and customize the deck

Tap a key to run it. Green feedback means the computer acknowledged success; red
feedback and a message explain a failure. A disconnected computer or an action with
no acknowledgement within ten seconds fails. Without a target, an action goes to all
connected computers and succeeds only when all acknowledge it. A broadcast failure
can mean some computers already performed the action.

The starter profile includes media keys, volume, microphone mute, screenshot, lock,
browser, copy/paste and undo/redo. Shortcuts vary by operating system and application:
for example, microphone mute is initially Ctrl+Shift+M and lock is Meta+L. Edit these
for your computer. The Home Assistant webhook example is **disabled** until you
replace its URL and explicitly enable the button.

- **Long-press a key** to open the editor. Choose a label, one of the built-in line
  icons or an emoji, key/text colors, action and target computer.
- **Edit deck** (sliders icon) lets you tap keys to edit, tap an empty cell to add, or
  hold and drag a key to move it. Dropping on another key swaps their positions.
  Tap the checkmark to finish editing.
- Tap a **page dot** or swipe sideways to change pages. Tap the profile name to
  switch profiles or create one.
- **Deck settings** (gear icon) renames profiles/pages, adds/removes pages and changes
  the grid from 2×2 through 6×8 (rows × columns). A smaller grid cannot hide existing
  keys: move or remove keys outside the proposed bounds first. Keep at least one
  page. Remove navigation links to a page before deleting it.
- Optional **toggle appearance** changes the label, icon and color after successful
  presses. This is local visual state, not a measurement of the computer's mute or
  playback state. It resets when the deck is closed, and is preserved on rotation.

## Actions

| Action | Editor fields / behavior |
| --- | --- |
| Keyboard shortcut | Modifier chips (Ctrl, Shift, Alt, Meta) and a key field; extra keys may be comma separated |
| Single key | Key name and repeat count (1–100) |
| Type text | Unicode text, up to 8,192 characters |
| Media control | Play/pause, previous, next, stop, volume up/down or mute |
| Open | URL, file or folder on the computer |
| Launch app | Application path and arguments, one per line |
| Run command | Command and shell option; requires **Allow commands** in the desktop companion |
| Mouse | Click, right click, move with X/Y, or scroll with an amount |
| Sequence | Add/edit/remove/reorder desktop actions and delays before each step; up to 32 steps per sequence, 64 total actions and three nesting levels |
| Page / Profile / Back | Navigate the phone deck; Back returns through navigation-key history |
| HTTP | Run an HTTP/HTTPS request from the phone with a method, URL and optional JSON body |

All steps of a sequence run on the selected computer(s). Phone-local actions and
per-step computer overrides are not allowed inside sequences. The ten-second
acknowledgement deadline applies to the entire action, including a sequence; keep
delays short. A timeout is feedback, not a cancellation of work already sent to the
computer.

HTTP actions support GET, POST, PUT, PATCH, DELETE and HEAD. Bodies are sent for
methods other than GET and HEAD with JSON content type. A 2xx response is success.
Requests have four-second connect/read timeouts and an eight-second connection
cancellation deadline. At most two phone requests execute at once; excess requests
fail immediately. Redirects are not followed. URLs must use HTTP/HTTPS and cannot
contain embedded credentials. Webhook URLs are convenient for Home Assistant;
custom authorization headers are not part of this version's action format.

## Appearance

Tap the palette icon in the deck header (or use the dashboard's **Look** section) to
restyle the deck. Scope can be the whole deck, one profile or one page; the more specific
scope overrides the broader one option by option. Changes preview live behind the sheet
and apply when you tap **Save look**.

- **Themes**: Studio, Neon, Minimal, Paper, OLED, Sunset and Ocean apply in one tap;
  adjusting any option afterwards makes the look custom.
- **Background**: the app default, a solid color, a gradient (2-3 stops, angle in steps
  of 45 degrees), OLED black, or your own image with blur (0-25) and dim (0-90 percent).
  Images are picked from the gallery, scaled to fit and stored on the phone.
- **Font**: family (system, sans, serif, monospace, rounded, condensed or an imported
  `.ttf`/`.otf` file up to 1 MB), weight, size (50-200 percent), label position
  (below the icon, beside it or hidden), text color and uppercase.
- **Keys**: corner radius, gap, border and its color, glow, shadow, a gradient on or off,
  and a key color that replaces every key's own color. Per key you can set a label size
  and content alignment.
- **Icons**: size and tint, 88 built-in line icons with a searchable picker, an emoji,
  or an image from the gallery (cropped to a square, never tinted).

Header text follows the background brightness so it stays readable.

### Look in macropads.json

All fields are optional; absent means the default shown. The same `look` object may appear
on the deck (top level), a profile and a page.

```json
{"version": 2, "look": {
  "theme": "neon",
  "bg":   {"type": "color|gradient|image|oled", "color": "#101418", "colors": ["#1B2230", "#0E121A"],
           "angle": 135, "image": "a-0123456789abcdef", "blur": 0, "dim": 0},
  "font": {"family": "system|sans|serif|mono|rounded|condensed|custom", "custom": "a-...", "weight": 500,
           "scale": 100, "position": "below|inside|none", "color": "#FFFFFF", "upper": false},
  "key":  {"radius": 20, "gap": 6, "border": 1, "borderColor": "#FFFFFF", "glow": 0, "shadow": 0,
           "gradient": true, "color": "#263142"},
  "icon": {"size": 30, "tint": "#00F0FF"}}}
```

Buttons gain `image` (asset id of a custom icon), `textSize` (6-40 sp) and `align`
(`center|start|end`). Image and font files are not stored in `macropads.json`; they are
referenced by asset id and kept in the app's private files (at most 40 files of 1 MB;
files no deck references are removed after ten minutes). The API:

- `GET /api/macropads/themes` returns `[{id, name, look}]`.
- `GET /api/macropads/assets` lists `{id, kind, bytes, name}`.
- `POST /api/macropads/assets?kind=image|font&name=...` stores the raw request body
  (JPEG, PNG or WebP; TTF or OTF; at most 1 MB) and returns the asset description.
- `GET /api/macropads/assets/<id>` returns the file; `DELETE` removes it unless the deck uses it.

**Copy JSON** and **Share JSON** embed the used images and fonts as base64 under a top-level
`assets` object when they total 1.5 MB or less; otherwise they are left out (the app tells you)
and the look only works on this phone. **Import JSON** stores the embedded assets first.

## Import and export

In **Deck settings**, use **Copy JSON** to back up to the clipboard, or **Share JSON**
to send the JSON text through Android's share sheet. **Import JSON** opens a text
field prefilled from the clipboard. Review it and tap Import to replace all profiles.
Invalid imports leave the saved deck intact. Exports can include command text and
private webhook URLs; share only with intended recipients.

The document uses schema version 2 (version 1 decks are migrated when read; they have no look) and is stored atomically in
`files/macropads.json`. IDs are globally unique, with at most 16 profiles and 16 pages
per profile. Each button occupies one grid cell. The document is limited to 512K
characters. Labels and names are limited to 80 characters; colors use `#RRGGBB`.
The `disabled` boolean is a phone-side extension used for unconfigured examples.
See `docs/desktop-protocol.md` for the full JSON shape and desktop action schema.

## Use the phone as a webcam

1. From the MacroPads computer panel, tap **Enable Desktop webcam** and grant camera
   permission. Android requires enabling foreground camera access from the phone.
2. In the desktop companion, select the phone camera, resolution, frame rate and
   JPEG quality. The phone chooses a nearby supported size/frame-rate range.
3. Keep the companion connected. An ongoing notification indicates that webcam
   access is enabled. Closing its stream releases the camera; access remains armed
   for reconnection until you choose **Disable Desktop webcam** or tap **Stop webcam**
   in the notification.

There is one webcam stream per phone. Stop IPCam live view, monitoring, recording or
its torch before starting it. IPCam and Desktop webcam reserve the camera exclusively;
a conflict returns 409 with an explanation. Slow clients receive the newest frame,
not a backlog. Stream disconnects release capture resources when detected; stalled
socket writes are closed after 30 seconds. The desktop companion's Camera panel
(and `homedroid-desktop webcam --zoom 1.5 --torch --ev 1 --wb daylight --rotate 90`) offers
only what the selected phone camera reports it supports: source, resolution and frame
rate, zoom, torch, focus (continuous, auto, manual distance, tap to focus), exposure
compensation, AE/AWB lock, white balance, manual ISO and shutter, stabilization,
anti-banding, and mirror/flip/rotate, which are applied on the phone before JPEG
encoding. Unsupported requests are rejected with a clear error. See
`docs/desktop-protocol.md` for the control fields and capabilities shape.

## Dashboard integration

The dashboard pages use the existing session authentication:

- `POST /api/desktop/pair` returns `{code, expires}` (epoch milliseconds).
- `GET /api/desktop/computers` returns an array of `{id, name, os, online, lastSeen}`.
- `DELETE /api/desktop/computers/<id>` unpairs immediately.
- `GET /api/macropads` returns the deck plus `computers`.
- `PUT /api/macropads` validates and atomically replaces the whole deck; the extra
  `computers` field from GET is ignored on save.
- `POST /api/macropads/press` takes `{profile, page, button}` and waits for the result.
  It returns `{ok:true}` on success, or an error. Navigation keys also return
  `navigation` with the local action for the dashboard to apply to its own view.

The native entry point is `MacroPadActivity.open(context)`. MainActivity's Tools tile,
manifest registration and Dashboard routing are already connected. No separate
settings hook is required; pairing, unpairing and webcam enable/disable are inside
the deck. Desktop clients use `/desktop/v1/pair`, `/hello`, `/events`, `/ack`, `/webcam`
and `/webcam/control`, authenticated with their desktop bearer token except pairing.
