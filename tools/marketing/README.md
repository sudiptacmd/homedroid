# Marketing site maintenance

`site/` is the complete, buildless GitHub Pages artifact. Edit its HTML, `styles.css` and
`main.js` directly. No npm install, bundler or third-party font service is needed.
The canonical origin is https://homedroid.seltiv.com/. Do not prefix assets with `/homedroid/`.

## Preview and checks

From the repository root, in separate terminals:

```sh
python3 -m http.server 8765 --directory site
/usr/bin/google-chrome --headless=new --no-sandbox --disable-dev-shm-usage \
  --remote-debugging-port=9227 --user-data-dir=/tmp/homedroid-marketing-chrome about:blank
```

Use an isolated Chrome profile; `--no-sandbox` is for the restricted capture environment.
On a normal workstation, retain Chrome's sandbox if supported.

```sh
node tools/marketing/check-site.mjs
node tools/marketing/browser-check.mjs
```

Requires Node 22+ (global WebSocket). `check-site` parses every JSON-LD block, checks local
links/assets/fragments, README file links, canonical/version metadata, image attributes,
manifest/sitemap, banned copy and video file sizes. `browser-check` covers every page at
1440, 390 and 320 pixels, tab keyboard controls, FAQ, OS hints, reduced motion, JS-disabled
content and real video decoding. Screenshots/results go to `/tmp/homedroid-site-qa`, not Git.
Override `CDP_URL`, `SITE_URL` or `QA_DIR` if needed.

## Recapture media

```sh
node tests/dashboard-mock/server.cjs
node tools/marketing/capture-dashboard.mjs
python3 tools/marketing/optimize-media.py
node tools/marketing/capture-social.mjs
```

The dashboard mock is at port 8787, password `demo`. Captures are 1440×900 and 390×844,
including Forest/sidebar, light/top-bar and Midnight/rail overview, modules, deployments,
files, terminal, AI, cluster and customization. The two videos use actual CDP screencast
frames, encoded in Chrome with canvas/MediaRecorder at 1280×800 and 30 fps, without ffmpeg.
`--videos-only` skips still capture. The encoder tab must be foregrounded while encoding.
The script rejects captures with too few distinct frames. Browser QA checks playback too.

The seven supplied phone PNGs and three MP4 recordings remain untouched in `site/media/new/`.
The optimizer makes WebP copies and updates selected README PNGs at stable paths. It removes
intermediate dashboard PNGs from `site/media/` after copying the best to `docs/screenshots/`.
`social.html` is the source for the 1280×640 PNG social preview, rendered with Chrome.

## Content evidence and release limits

Copy was checked against `app/build.gradle.kts`, `apps.json`, the Kotlin service/API/UI code,
`dashboard.html`, `desktop/README.md`, the Desktop/release workflows, all release notes,
the three guides and `docs/desktop-protocol.md`. Raspberry Pi comparison facts use the
linked official Pi 5 specification, not a generic speed or power claim.

In this checkout, pipelines, databases, domains and MCP have backend APIs but their dashboard
pages are still navigation-slot comments. No screen captures were fabricated for these.
`site/media/capture-notes.json` records the omission. Schema relationships and SQL-query APIs
exist, but the schema-diagram and SQL-console dashboard views are not wired in.

Windows 11 virtual camera and macOS input remain experimental; release notes also limit
physical multi-phone load-balancing verification. APK size is not stated as a measured 0.9.5
number. Per-ABI historical guidance is under 50 MB; check the release for exact sizes.
Desktop's approximately 1 MB figure applies to Linux. Direct beta links follow the asset names
in the workflows; publishing/release availability is outside this local-only change.

The final copy pass rewrote the hero, release band, grouped features, phone/dashboard tour,
Dock/MacroPads/Desktop sections, comparison, setup/downloads, FAQ, all five topic pages,
README and both llms files. It removed broad speed/heat claims and distinguished local AI
from optional cloud requests. The banned-phrase checker runs over all public copy.
