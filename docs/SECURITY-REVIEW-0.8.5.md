# v0.8.5-beta security review

Reviewed on 2026-10-01: Android components and permissions, dashboard routes, camera/audio
access, HTTP parsing, cookies, terminal/WebSocket authentication, file uploads/downloads,
archive extraction, APK updates, deployment commands, SSH and bundled Go dependencies.
This is a source review with regression and device checks, not an independent penetration
test or a guarantee against every vulnerability.

## Fixes

- All password checks share one persisted limiter: login, Bearer API authentication and
  confirmation of the current password. Five failures from a socket peer trigger a
  15-minute cooldown. Fifty failures across peers trigger an account-wide cooldown.
  Requests during a cooldown do not extend it. A successful check clears that peer's
  failures; it does not clear the account-wide budget. Responses use HTTP 429 and
  `Retry-After`. Existing valid sessions continue working during a password lockout.
- Counters use the real socket address. Forwarded IP headers cannot reset the budget.
  Reverse proxies can share a peer budget; the global limit also covers changing peers.
- Sessions use 256-bit random tokens, store only SHA-256 hashes, expire after 24 hours on
  the server, and are limited to 20. Logout revokes its session; changing the password
  revokes all sessions. Existing cookies from earlier releases require a fresh login.
  Cookies use HttpOnly and SameSite=Strict, with Secure on HTTPS browser logins.
- Browser mutations require `X-Homedroid-Request: 1`; the dashboard and upload requests
  send it. Requests from another Origin or marked cross-site are rejected. Bearer scripts
  can authenticate without this browser-only header. No cross-origin API access is enabled.
- Terminal upgrades require a valid WebSocket handshake and same dashboard authority.
  Established terminals recheck authorization every second, including while idle.
  Frame lengths, masking, control frames and combined fragmented messages are validated.
- HTTP uses 16 workers and at most 32 queued connections, closes sockets on shutdown,
  caps headers and their reading time, and rejects ambiguous framing, duplicate headers,
  negative body lengths and unsupported transfer encoding. Unexpected server exceptions
  no longer disclose their internal message to unauthenticated clients.
- SSH accepts public keys only, explicitly limits each handshake to five auth attempts,
  sets a 10-second handshake deadline and caps connections at 64. The loopback terminal
  requires its separate random secret, caps connections at 16 and bounds its greeting.
- The boot receiver accepts only its two intended Android system actions. Archive metadata
  allocation and malformed PAX records are bounded. Dashboard CSP forbids objects, base
  tags, external form targets and framing.
- Android 12+ explicitly excludes app data from both cloud backup and device transfer,
  supplementing the existing `allowBackup=false` setting for credentials and footage.
- Pinned upstream Caddy, Cloudflare and Tailscale releases receive explicit security
  dependency upgrades from `native/go-security-deps.txt`. Caddy's CEL adapter has a small
  compatibility patch for the fixed CEL library. Both Windows and Linux builders apply it.
  Release CI runs tests, lint and Android package dependency checks before publication.

## Existing protections verified

Camera frames, saved footage, audio and control routes all sit behind dashboard auth.
Camera access requires enabling the module, granting Android permissions and arming the
foreground service on the phone. Captures and credentials use private app storage; Android
backup is disabled. Camera/server services and updater receiver are not exported.

File-browser paths are canonicalized within their configured roots and downloads use a
sandbox CSP. OCI blobs and Alpine downloads are checksum checked; registry credentials are
not forwarded to blob storage redirects. APK uploads/downloads must match the app package
and installed signing certificate, and cannot downgrade the installed version code.

## Dependency checks and operational boundaries

`govulncheck` v1.8.0 reports no known vulnerabilities in imported Android packages for SSH,
Caddy, Cloudflare or Tailscale after the dependency upgrades. Its module-only advisory
[GO-2026-5932](https://pkg.go.dev/vuln/GO-2026-5932) concerns deprecated OpenPGP packages,
which these build configurations do not import. Binary scanning conservatively reports
that wildcard advisory even for SSH; the exact Android package scan verifies the imports.
FFmpeg remains pinned to 9.0.2 with a verified source checksum; its upstream
[security record](https://ffmpeg.org/security.html) was reviewed. proot and talloc source
downloads are checksum pinned. These checks do not scan every user-installed app or
prove arbitrary media parsers free of defects.

The direct LAN dashboard uses HTTP. Passwords, session cookies and camera traffic on that
connection are not encrypted. Use the existing Tailscale HTTPS Serve or Cloudflare HTTPS
tunnel for untrusted networks and remote access; do not expose the HTTP port directly.
Authenticated dashboard users are administrators with shell, deployment and file access.
proot and installed applications share Homedroid's Android UID and are not isolated
security containers. Applications exposed on other ports need their own authentication.

## Validation

- JVM tests cover parallel guessing, shared/persisted budgets, cooldown timing, session
  expiration/revocation/count, browser policies, HTTP framing and WebSocket bounds.
- Dashboard tests cover camera settings, rotation, stable canvas frames, polling drafts,
  escaping, browser request protection and capture behavior.
- A separate package on a USB-connected Samsung SM-S926B checks dashboard auth across
  camera routes, mixed login/Bearer guessing, password-confirmation limits, persistence
  and logout, plus live frames and bounded playable motion clips across exposed cameras.
  It also checks bundled daemon execution and closure of an idle SSH handshake.
- Android debug/release builds and lint, Go builds for all three ABIs, and upstream Caddy
  expression tests validate the implementation and compatibility patch.

Reference guidance: [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html),
[CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html),
[session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
and [WebSocket security](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html).
