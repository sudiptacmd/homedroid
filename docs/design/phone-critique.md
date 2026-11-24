# Phone UI critique (0.9.5)

A review of the native phone screens (Overview, Apps, Settings, the control-panel wrapper and
the lock screen) as of `release/0.9.5`, before the design-system revamp. Ordered by impact.
Each finding names the fix that the revamp applies.

## P0: things that hurt every session

1. **No hierarchy on Overview.** Everything is a white card of equal weight: the server state,
   four metric cards, the address, every service (each with its own full-width "Restart
   service" button) and a camera card. The one decision that matters (is the server on, and
   how do I reach it?) is not visually dominant.
   *Fix:* one hero card whose container colour follows state (primary container when online,
   neutral when off), a status pill, a 56 dp start/stop button and the dashboard URL with
   copy / share / open beside it. Everything else sits below in quieter list groups.

2. **Buttons everywhere, all the same.** `MobileUi.button` makes full-width 48 dp buttons that
   are either "primary" or a tint; Apps cards show up to five of them in two rows (Install /
   Open / Turn off / Clear data / Storage location), and Overview stacks seven. There is no
   distinction between the main action, a secondary one and a destructive one.
   *Fix:* a real button set (filled, tonal, outlined, text, error) and one main action per card.
   Rare or destructive actions move into a "Manage" bottom sheet.

3. **App installs have no visible progress.** Status is a single coloured line ("Installing
   Jellyfin…"); the log is hidden behind a toggle at the bottom of a long list.
   *Fix:* a job banner pinned at the top of Apps with an animated progress bar, the latest
   log line and a "View activity" sheet; the installing card shows the same progress inline;
   a failed job turns the banner into an error state with the reason. Installed apps gain
   "Update".

4. **Editors are bare `AlertDialog`s.** Default Material (2014) dialogs with Toast feedback,
   `EditText.error` popups and no way to show a message and checkboxes together (the code has
   a comment working around that).
   *Fix:* bottom sheets with rounded top, drag handle, inline supporting / error text under
   each field, and snackbars for confirmation with an optional action.

## P1: polish and consistency

5. **Colour is a hand-picked handful of hex values** (`background`, `surface`, `ink`, `muted`,
   `accent`, `tint`, `warning`), no error or success roles, no surface levels, and no Material
   You. Warning text uses orange on dark navy at small sizes.
   *Fix:* token-based roles (surface + five container levels, primary / secondary / tertiary
   and their containers, error, success, warning, outline), Android 12+ dynamic colour from
   `system_accent*` / `system_neutral*` with a curated teal fallback, and a Settings switch.

6. **Typography is ad-hoc** (11 to 25 sp, bold or not, chosen per call). Section labels use
   letter-spaced bold caps (`H O M E D R O I D`) that screen readers spell out letter by letter.
   *Fix:* a type scale (display / headline / title / body / label) with set weights and line
   heights; sentence-case section headers marked as accessibility headings.

7. **Spacing and shape drift.** Radii of 10, 12, 14, 16 and 20 dp; paddings of 2–24 dp chosen
   per view; metric cards use 3 dp side margins.
   *Fix:* a 4 dp spacing scale and a shape scale (8 / 12 / 16 / 28 dp, full); grouped lists use
   M3 Expressive "segmented" corners (large outside, small between items, 2 dp gaps).

8. **Vitals are just numbers.** "84%", "31.2 °C", "2.1 GB" with no context (of what total? is
   that good?). CPU and network, which the dashboard already measures, are missing.
   *Fix:* a vitals grid of six tiles (battery, temperature, CPU, memory, storage, network) with
   progress rings, totals and tone (warning when hot or nearly full); sampled off the main
   thread.

9. **Services are cards with restart buttons.** A restart is rare; the state is what matters,
   and it is shown as grey text.
   *Fix:* list rows with an icon per service and a status pill (dot + label, colour by state);
   tapping opens a sheet with details, logs and Restart.

10. **Settings is a mix of cards, rows and floating switches.** Switch descriptions sit above
    the framework `Switch`, which draws its own title; ports and current values are hidden in
    descriptions. Changes from the web dashboard force a full `recreate()`.
    *Fix:* grouped sections of list rows with leading icons and trailing values or M3
    switches; values rebind on resume without recreating the screen.

## P2: details

11. **Navigation bar** looks like tabs with a rounded selected background on text; no
    active indicator pill, no top app bar, and the brand wordmark takes the title's place.
    *Fix:* large-title top app bar that collapses into a small title as you scroll, and an M3
    navigation bar with an animated indicator pill behind the icon.

12. **States.** Empty services shows a single grey sentence; the control panel shows a raw
    message; no loading states.
    *Fix:* reusable empty / loading / error state views with an icon, title, body and action.

13. **Touch targets and semantics.** The "›" chevron is a 28 sp text glyph; nav items are
    shorter than 48 dp at 1.0 font scale on some phones; pills and metrics have no combined
    descriptions; the switch rows put the description outside the touch area.
    *Fix:* 48 dp minimum everywhere, whole-row targets for switches (announced as a switch with
    its state), combined content descriptions for tiles and pills, live region on the hero.

14. **Large fonts.** Two-column metric rows and three-button rows squeeze at 1.5x+ font scale.
    *Fix:* wrap-content heights everywhere, a flow layout for button rows, and a single-column
    vitals grid above 1.3x font scale.

15. **Feedback.** No haptics on start/stop or toggles; Toasts for everything.
    *Fix:* confirm/toggle haptics and snackbars; press-scale and colour cross-fades with
    `ViewPropertyAnimator`, respecting the system animation scale.

16. **Microcopy.** Mostly good. A few fixes: "Installed · backoff" leaked the supervisor's
    state name; "IPCam: <exception>" is jargon; "Settings saved" says nothing about what
    happened (services restarting). Use "Needs attention", "Camera couldn't start: …", and
    say when services restart.

17. **New features with no phone UI.** App lock, SSH port, app DNS servers and the MCP server
    exist in the backend but only some are reachable (from the web dashboard).
    *Fix:* Settings sections for each, with validation, and the lock check in every screen's
    resume.

18. **Burn-in on always-on server phones.** Phones left on a shelf with the screen on show the
    same bright layout for weeks, which ghosts OLED panels.
    *Fix:* an "OLED black" theme (true #000 background, dimmed surfaces and containers), and
    burn-in protection: an untouched Overview turns into a dim standby clock that moves every
    minute (logic in `BurnIn.kt`, unit tested). Keeping the screen on is opt-in; the app did
    not hold `FLAG_KEEP_SCREEN_ON` anywhere before.
