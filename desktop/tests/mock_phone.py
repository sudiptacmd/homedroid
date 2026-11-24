#!/usr/bin/env python3
"""Tiny fake Homedroid phone for exercising homedroid-desktop end to end.

    python3 tests/mock_phone.py [port]      (default 18800, pairing code 123456)

Implements /desktop/v1/{pair,hello,events,ack,webcam,webcam/control} per docs/desktop-protocol.md.
After a computer connects to /events it is sent a scripted set of actions; acknowledgements
are printed as ACK lines. Webcam frames are generated JPEGs (needs Pillow).
"""
import io, json, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "ab" * 32
CODE = "123456"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18800
ACTIONS = [
    {"id": "a-1", "action": {"kind": "hotkey", "keys": ["ctrl", "shift", "m"]}},
    {"id": "a-2", "action": {"kind": "text", "text": "héllo ✓"}},
    {"id": "a-3", "action": {"kind": "media", "key": "play_pause"}},
    {"id": "a-4", "action": {"kind": "command", "command": "echo hi", "shell": True}},
    {"id": "a-5", "action": {"kind": "sequence", "steps": [
        {"kind": "key", "key": "enter", "repeat": 2}, {"kind": "mouse", "action": "move", "x": 10, "y": 20, "delayMs": 50}]}},
    {"id": "a-6", "action": {"kind": "bogus"}},
]


def frames():
    from PIL import Image, ImageDraw
    out = []
    for i in range(6):
        im = Image.new("RGB", (640, 360), (20 * i, 80, 160))
        ImageDraw.Draw(im).rectangle([20 + 80 * i, 40, 100 + 80 * i, 120], fill=(255, 255, 0))
        b = io.BytesIO(); im.save(b, "JPEG", quality=80); out.append(b.getvalue())
    return out


FRAMES = frames()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send_json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def authed(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.send_json(401, {"error": "Pair again"})
            return False
        return True

    def do_POST(self):
        p = self.path.split("?")[0]
        if p == "/desktop/v1/pair":
            b = self.body()
            if b.get("code") != CODE:
                return self.send_json(403, {"error": "That code is wrong or expired"})
            print("PAIR", b, flush=True)
            return self.send_json(200, {"token": TOKEN, "computerId": "c-test01", "phone": "Mock Phone", "phoneId": "phone-1"})
        if not self.authed():
            return
        if p == "/desktop/v1/ack":
            print("ACK", json.dumps(self.body()), flush=True)
            return self.send_json(200, {"ok": True})
        if p == "/desktop/v1/webcam/control":
            print("CONTROL", json.dumps(self.body()), flush=True)
            return self.send_json(200, {"ok": True})
        self.send_json(404, {"error": "no"})

    def do_GET(self):
        p = self.path.split("?")[0]
        if not self.authed():
            return
        if p == "/desktop/v1/hello":
            return self.send_json(200, {"phone": "Mock Phone", "computerId": "c-test01", "features": ["webcam", "macros"],
                                        "cameras": [{"id": "0", "facing": "back", "sizes": [[1280, 720]], "fps": [15, 30]}]})
        if p == "/desktop/v1/events":
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()

            def chunk(o):
                d = (json.dumps(o) + "\n").encode()
                self.wfile.write(b"%x\r\n" % len(d) + d + b"\r\n"); self.wfile.flush()
            try:
                chunk({"type": "hello", "computerId": "c-test01"})
                time.sleep(0.5)
                for a in ACTIONS:
                    chunk({"type": "action", **a}); time.sleep(0.4)
                while True:
                    time.sleep(2); chunk({"type": "ping"})
            except OSError:
                print("EVENTS closed", flush=True)
            return
        if p == "/desktop/v1/webcam":
            print("WEBCAM", self.path, flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=hdframe")
            self.end_headers()
            try:
                n = 0
                while True:
                    j = FRAMES[n % len(FRAMES)]; n += 1
                    self.wfile.write(b"--hdframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nX-Frame: %d\r\nX-Time: %d\r\n\r\n" % (len(j), n, int(time.time() * 1000)))
                    self.wfile.write(j + b"\r\n"); self.wfile.flush()
                    time.sleep(0.05)
            except OSError:
                print("WEBCAM closed", flush=True)
            return
        self.send_json(404, {"error": "no"})


class S(ThreadingHTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    print("mock phone on", PORT, "code", CODE, flush=True)
    S(("127.0.0.1", PORT), H).serve_forever()
