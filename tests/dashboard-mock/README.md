# Dashboard mock server

A dependency-free simulation of the Homedroid phone API for developing and screenshotting `app/src/main/assets/dashboard.html`
without a phone. It never runs commands, sends packets or contacts providers; uploads stay in memory.

```
node tests/dashboard-mock/server.cjs          # http://127.0.0.1:8787, password "demo"
PORT=9000 node tests/dashboard-mock/server.cjs
MOCK_NO_PREFS=1 node tests/dashboard-mock/server.cjs   # behave like an older phone without /api/ui/prefs
```

Serves the dashboard and `/vendor`, `/guides`, `/favicon.svg` from `app/src/main/assets` with the phone's CSP, and mocks every `/api` route
the page calls with fixtures from `fixtures.cjs` (mutable, in-memory; metrics vary). Unknown routes are logged and answered with 404 JSON.

Notable endpoints: settings (`sshPort`, `appsDns` validation), `modules/<id>/update`, cluster pairing, Wake on LAN, IPCam, AI, files, terminal
WebSocket, and `GET/PUT/DELETE /api/ui/prefs` (JSON object up to 256 KB; writes need the `X-Homedroid-Request: 1` header and return the saved
object with `updated`).

`visual-check.cjs` is optional browser QA (needs a Playwright module and Chrome). `node --test tests/` covers the server shapes.
