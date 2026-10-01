# v0.9.0-beta security review

Reviewed on 2026-10-01: the new cluster (pairing, the cluster port, forwarding, app moves,
file transfers), the AI module (port 8090, cloud keys, model downloads) and routines
(fetching, email, delivery). This is a source review with unit tests, not an independent
penetration test; the 0.8.5 review still describes everything else.

## New listening ports

| Port | What | Who can use it |
|------|------|----------------|
| 8801 | Cluster (mutual TLS) | Anyone can say hello and ask to pair; everything else needs a certificate a member paired |
| 8090 | AI API (HTTP) | Only while the AI module is on; every request needs the AI key |
| 8091 | llama.cpp server | Bound to 127.0.0.1 only; reached through port 8090 |

## Cluster

- **Identity.** Each phone makes an EC P-256 key and a self-signed certificate on first use,
  kept in private app storage. Phones are known by the SHA-256 fingerprint of that
  certificate; no certificate authority is trusted.
- **Transport.** TLS 1.2/1.3 with client certificates required. A client pins the server's
  fingerprint on every connection after pairing. The server accepts any certificate at
  the TLS layer and checks the fingerprint against the member list on every request;
  only `/cluster/hello` and the two pairing endpoints answer strangers.
- **Pairing.** The 6-digit code is derived from both certificate fingerprints, and each
  phone computes it itself (the requesting phone does not trust the code it is sent). A
  device in between would have to present its own certificate, which changes the code on
  one side. Approval happens only on the phone being added: its notification (a
  non-exported receiver) or its own logged-in dashboard. Requests expire after 3 minutes;
  at most 4 wait at once and 5 per address per 10 minutes.
- **Trust is transitive and total by design.** A member tells the others about members it
  added, and any member can manage every other: start a terminal, read files, change
  modules, move apps. The weakest dashboard password in the cluster therefore protects all
  of it, and a compromised phone compromises the cluster. Removing a phone revokes its
  certificate on every phone that is reachable; an unreachable member keeps its list until
  it next talks to the others, and they refuse it from then on.
- **Forwarding.** `/api/nodes/<id>/…` requires the browser's normal login on the phone it
  opened, then forwards over the pinned connection. Path segments and queries are
  re-encoded; header names and values are filtered both ways. Other phones cannot use
  `/api/nodes`, `/api/cluster`, login or logout, so requests don't chain or approve
  pairings remotely. Forwarded terminals keep the browser session check: logging out
  closes them within a second, as on one phone.
- **Moving apps.** App data exports and full deployment configs (which hold environment
  secrets and repository tokens) are only served to cluster members, never to browsers.
  An export requires the app to be off. Imports only write inside the app's own folders:
  archive entries with `..`, empty or unknown parts, or paths that would pass through a
  symlink are refused before anything is written. Symlinks inside the archive are kept as
  links (proot stores hard links that way) and the file browser still refuses to follow
  links outside their location.
- **File transfers.** The receiving phone pulls from the sending one, writes under a
  temporary name and renames on success; a move deletes the original only after that.
- Addresses learned from mDNS or from requests are accepted only as plain host names or
  IP literals, so they can't inject paths or headers. Moving to a new address never
  changes which certificate is trusted.

## AI

- The API on port 8090 requires its own random 160-bit key (separate from the dashboard
  password), compared in constant time, with the same per-address and overall cooldown as
  dashboard passwords. It can be regenerated from the dashboard. It is plain HTTP on the
  LAN like the other modules; use Tailscale for access from elsewhere.
- Cloud API keys are checked against the service when added, stored in private app
  preferences, sent only to that service's address, and never returned to the browser.
  Base URLs must be plain `http(s)://host[:port]/path`.
- Model downloads use HTTPS. Catalog models are checked against SHA-256 hashes pinned in
  the app; a mismatch deletes the file. Custom links must be `https://` and end in
  `.gguf`; their contents are not verified, and a model file is data for llama.cpp, whose
  GGUF parser is the trust boundary.
- The local server listens on loopback only. Prompts and answers to on-phone models stay
  on the phone; the chat is kept only in the browser. Every answer and brief is labelled
  with where it ran, including when a cloud fallback was used.

## Routines

- Routines only read. Email uses IMAPS with certificate and host-name checks, opens the
  mailbox with `EXAMINE` and fetches with `BODY.PEEK`, so nothing is changed or marked
  read. IMAP strings are quoted and reject line breaks. The app password is stored in
  private preferences and never shown again.
- Email content goes to a cloud model only in routines where the user ticked *allow cloud
  models to read my email*; otherwise such a routine refuses to run with a cloud model,
  including through the fallback.
- Feeds are parsed without DOCTYPEs (no external entities or entity expansion); pages and
  feeds are capped at 2 MB. Sources may be any `http(s)` address the user enters,
  including addresses on the LAN; only logged-in users can create routines.
- Delivery: ntfy topics and Telegram chat ids are validated; tokens are stored privately.
  Push services receive the brief's text, which may summarize email.

## Validation

- JVM tests cover certificate generation, the pairing code, real mutual-TLS connections
  (identity carried, wrong pins refused, plain HTTP rejected), pairing approval, rejection,
  expiry and rate limits, member lists with conflicting identities, address validation,
  the archive format (round trips, traversal, unknown parts, links in the way), AI routing
  and fallback rules, key handling, feed/HTML/MIME/IMAP parsing, IMAP quoting and
  schedules. Dashboard tests cover routing every call through the selected phone.
- Not yet verified on hardware: TLS between real phones (Conscrypt), mDNS on real
  networks, llama.cpp under proot on a phone, text-to-speech delivery and live IMAP
  servers. See `tests/CLUSTER-AI.md`.

## 0.9.1 changes

- **On-phone AI runtime.** llama.cpp is no longer installed from Alpine; it is built by this
  repository's release workflow (`native/build-llama.sh`, sources pinned to exact commits) and
  attached to the GitHub release. The phone downloads it over HTTPS and installs it only if its
  SHA-256 matches the hash compiled into the APK (`assets/llama-runtime.json`), so the runtime
  can't be swapped without the app's signing key. An upload through the dashboard ("install
  from a file", for testing) is held to the same hash. It is unpacked with the same extractor as
  Alpine (paths confined to its folder) and run through `/system/bin/linker64`, because Android
  forbids executing files from app storage directly; it gets no more access than the app.
- **Vendor libraries.** The runtime's library path includes `/vendor/lib64` so the OpenCL driver
  on Qualcomm phones can load; the runtime's own folder comes first.
- **Cleartext HTTP.** The app now allows plain-HTTP connections (`usesCleartextTraffic`).
  Android's default blocked Homedroid from reaching its own llama.cpp server on 127.0.0.1 and
  LAN services the user configures (Ollama, feeds, ntfy). Built-in cloud providers, model and
  runtime downloads, GitHub and IMAP still use HTTPS/TLS; a user-entered `http://` address is
  sent in the clear, as the user chose.
- **Home-screen widget.** Its receiver is exported (launchers require that); another app can
  only make it refresh. It shows phone names, battery, temperature and service names on the
  home screen, so anyone holding the unlocked phone can read them.
