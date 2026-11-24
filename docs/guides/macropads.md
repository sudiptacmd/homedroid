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

## Import and export

In **Deck settings**, use **Copy JSON** to back up to the clipboard, or **Share JSON**
to send the JSON text through Android's share sheet. **Import JSON** opens a text
field prefilled from the clipboard. Review it and tap Import to replace all profiles.
Invalid imports leave the saved deck intact. Exports can include command text and
private webhook URLs; share only with intended recipients.

The document uses protocol version 1 and is stored atomically in
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
socket writes are closed after 30 seconds. Torch and zoom are best effort, mirror is
applied before JPEG encoding, and optional focus controls report unsupported.

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
