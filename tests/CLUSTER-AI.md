# Cluster and AI verification (0.9.0)

Run the host checks from the repository root:

```sh
./gradlew testDebugUnitTest
node tests/cluster-dashboard.test.cjs
node tests/ipcam-dashboard.test.cjs
```

These don't exercise real phones. Before release, check on two phones (ideally one older
Android 10 and one current), both on 0.9.0, same Wi-Fi:

## Cluster

1. Phone A: Cluster → Add a phone. Phone B appears within ~20 s (mDNS). Pair; both show the
   same code; B's notification approves it. Repeat with Reject, and let one expire (3 min).
2. Add by IP when mDNS is blocked (or with a phone on Tailscale, by its 100.x address).
3. With three phones: pair A–B, then B–C; A must list C without pairing it directly.
4. Switch to B in A's dashboard: every page shows B (Overview stats, Modules, Files,
   Terminal, IPCam, AI). Open B's terminal through A; log out of A and check it closes.
5. Turn B off or leave the Wi-Fi: it shows offline; the banner explains; switching back works.
   Change B's IP (reconnect Wi-Fi): A finds it again (mDNS) without re-pairing.
6. Remove B from A: B forgets the cluster too. Pair again works.
7. Move qBittorrent A → B with and without its library and with "remove from the old phone".
   Its settings arrive; a forced failure (turn B's Wi-Fi off mid-copy) turns it back on at A.
   Move a deployment; B builds it from Git.
8. All cameras: live tiles from both phones; photos; switching cameras; leaving the page
   turns the cameras off (privacy indicator goes away within ~15 s).
9. Files: browse B's locations from A; copy and move a large video and a folder both ways;
   cancel by closing the page (the transfer continues on the receiving phone).
10. A packet capture of port 8801 shows TLS only; a phone with a different install is
    refused after pairing (403).

## AI

1. Turn on AI, install llama.cpp, download Qwen2.5 0.5B; it becomes Ready. Chat streams.
   Kill the download mid-way and start again: it resumes.
2. Connect OpenAI and Gemini with real keys (and a wrong key: refused with the service's
   message), and Ollama on a computer as an OpenAI-compatible service.
3. From a computer: `curl http://<phone>:8090/v1/models` with and without the key; chat
   completions with `stream: true` and `false`; five wrong keys → 429.
4. Set a cloud fallback, stop the local model (pick None): chat uses the fallback and says so.
5. Through the cluster: chat on B from A's dashboard streams word by word.
6. Watch temperature and battery during 10 minutes of local generation.

## Routines

1. Morning brief template with weather (look up a town), two feeds, email (Gmail app
   password) and phone status, on the local model. Run now; brief appears; read aloud on
   the phone and on another phone; ntfy and Telegram arrive.
2. Email with a cloud model and the box unticked: the run fails with the explanation.
3. Check the mailbox: nothing was marked as read.
4. Schedule a routine two minutes ahead; leave the dashboard closed; it runs on time with the
   screen off. Reboot the phone across a scheduled time by more than 3 hours: it is skipped.
