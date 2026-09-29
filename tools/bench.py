#!/usr/bin/env python3
"""Measures Homedroid on a phone: idle RAM and CPU, battery, network, transcoding, thermals, uptime.

Runs on your computer and talks to the phone over adb and Homedroid's SSH server, so the
phone needs USB debugging, Homedroid running, and your SSH key added in the app.

    tools/bench.py                          # everything except the long tests
    tools/bench.py --battery-minutes 30     # also measure battery use over 30 idle minutes
    tools/bench.py --soak-hours 24          # also check availability for a day
    tools/bench.py --only transcode thermal

Results are printed as a Markdown table and saved as JSON (--out).
"""
import argparse
import json
import re
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PKG = "dev.homedroid"
SSH_PORT = 8022


class Phone:
    def __init__(self, serial, host):
        self.adb_base = ["adb"] + (["-s", serial] if serial else [])
        self.host = host
        self.ssh_base = ["ssh", "-p", str(SSH_PORT), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                         "-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR", host]

    def adb(self, *args, check=True):
        return subprocess.run(self.adb_base + list(args), capture_output=True, text=True, check=check).stdout

    def sh(self, script, timeout=600):
        """Runs a shell script on the phone as Homedroid (mksh, with `alpine` available)."""
        r = subprocess.run(self.ssh_base + [script], capture_output=True, text=True, timeout=timeout,
                           stdin=subprocess.DEVNULL)
        if r.returncode != 0 and not r.stdout:
            raise RuntimeError(f"ssh failed: {r.stderr.strip()}")
        return r.stdout

    def forward(self, port):
        if self.host in ("localhost", "127.0.0.1"):
            self.adb("forward", f"tcp:{port}", f"tcp:{port}")


# Processes owned by Homedroid's uid: CPU ticks (utime+stime) and PSS in kB, one line each.
PROBE = r"""
for d in /proc/[0-9]*; do
  [ -O "$d" ] || continue
  st=$(cat "$d/stat" 2>/dev/null) || continue
  comm=${st#*\(}; comm=${comm%%\)*}
  case $comm in sh|mksh|cat|awk|grep|sleep|toybox) continue;; esac
  ticks=$(echo "${st##*\) }" | awk '{print $12+$13}')
  pss=$(awk '/^Pss:/{print $2; exit}' "$d/smaps_rollup" 2>/dev/null)
  echo "${d#/proc/} $comm $ticks ${pss:-0}"
done
"""


def probe(phone):
    rows = []
    for line in phone.sh(PROBE).splitlines():
        parts = line.split()
        if len(parts) == 4:
            rows.append({"pid": parts[0], "name": parts[1], "ticks": int(parts[2]), "pss_kb": int(parts[3])})
    return rows


def measure_idle(phone, seconds=60):
    first = probe(phone)
    t0 = time.time()
    time.sleep(seconds)
    second = probe(phone)
    elapsed = time.time() - t0
    before = {r["pid"]: r["ticks"] for r in first}
    ticks = sum(r["ticks"] - before[r["pid"]] for r in second if r["pid"] in before)
    hz = 100  # USER_HZ on Android kernels
    top = sorted(second, key=lambda r: -r["pss_kb"])[:6]
    return {
        "processes": len(second),
        "ram_mb": round(sum(r["pss_kb"] for r in second) / 1024, 1),
        "cpu_percent_of_one_core": round(100 * ticks / hz / elapsed, 2),
        "largest": [f"{r['name']} {round(r['pss_kb'] / 1024)} MB" for r in top],
    }


def measure_battery(phone, minutes):
    phone.adb("shell", "dumpsys", "batterystats", "--reset")
    phone.adb("shell", "dumpsys", "battery", "unplug")
    level0 = battery(phone)["level"]
    try:
        time.sleep(minutes * 60)
        stats = phone.adb("shell", "dumpsys", "batterystats", "--charged", PKG)
        level1 = battery(phone)["level"]
    finally:
        phone.adb("shell", "dumpsys", "battery", "reset")
    uid = re.search(r"userId=(\d+)", phone.adb("shell", "dumpsys", "package", PKG))
    mah = None
    if uid:
        u = "u0a" + str(int(uid.group(1)) - 10000)
        m = re.search(rf"Uid {u}: ([\d.]+)", stats)
        mah = float(m.group(1)) if m else None
    return {"minutes": minutes, "homedroid_mah": mah, "battery_level_drop_percent": level0 - level1,
            "mah_per_hour": round(mah * 60 / minutes, 1) if mah is not None else None}


def battery(phone):
    out = phone.adb("shell", "dumpsys", "battery")
    get = lambda k: int(re.search(rf"{k}: (-?\d+)", out).group(1))
    return {"level": get("level"), "temp_c": get("temperature") / 10}


def thermal_zones(phone):
    out = phone.adb("shell", "for z in /sys/class/thermal/thermal_zone*; do echo $(cat $z/type) $(cat $z/temp); done",
                    check=False)
    zones = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("-").isdigit():
            v = int(parts[1])
            zones[parts[0]] = v / 1000 if abs(v) > 1000 else v
    return zones


def measure_network(phone, mb=256):
    phone.forward(8080)
    phone.sh(f"dd if=/dev/zero of=www/bench.bin bs=1048576 count={mb} 2>/dev/null")
    try:
        t0 = time.time()
        with urllib.request.urlopen(f"http://{phone.host}:8080/bench.bin", timeout=600) as r:
            n = 0
            while chunk := r.read(1 << 20):
                n += len(chunk)
        down = n / (1 << 20) / (time.time() - t0)
        payload = Path("/tmp/homedroid-bench.bin")
        payload.write_bytes(b"\0" * (mb << 20))
        t0 = time.time()
        with payload.open("rb") as f:
            subprocess.run(phone.ssh_base + ["cat > /dev/null"], stdin=f, check=True)
        up = mb / (time.time() - t0)
        payload.unlink()
    finally:
        phone.sh("rm -f www/bench.bin")
    return {"download_http_mb_s": round(down, 1), "upload_ssh_mb_s": round(up, 1),
            "note": "phone to computer; bounded by Wi-Fi/USB and the computer"}


# 1080p H.264 source (made once with Jellyfin's FFmpeg), then transcoded the way Jellyfin does.
SOURCE = ("alpine -c 'test -f /tmp/bench-1080p.mkv || /usr/lib/jellyfin-ffmpeg/ffmpeg -loglevel error "
          "-f lavfi -i testsrc2=d=30:s=1920x1080:r=30 -c:v libx264 -preset veryfast -b:v 10M /tmp/bench-1080p.mkv'")
HW = ("alpine -c '/usr/local/lib/homedroid/ffmpeg -hide_banner -nostats -stats_period 1 -progress pipe:1 "
      "-i /tmp/bench-1080p.mkv -vf scale=1280:720,format=nv12 -c:v h264_v4l2m2m -b:v 4M -g 90 -f mpegts -y /dev/null'")
SW = ("alpine -c '/usr/lib/jellyfin-ffmpeg/ffmpeg -hide_banner -nostats -progress pipe:1 -i /tmp/bench-1080p.mkv "
      "-vf scale=1280:720 -c:v libx264 -preset veryfast -b:v 4M -f mpegts -y /dev/null'")


def transcode_fps(phone, cmd):
    out = phone.sh(cmd, timeout=900)
    fps = [float(v) for v in re.findall(r"^fps=([\d.]+)", out, re.M) if float(v) > 0]
    speed = re.findall(r"^speed=\s*([\d.]+)x", out, re.M)
    return {"fps": round(fps[-1], 1) if fps else None, "realtime": f"{speed[-1]}x" if speed else None}


def measure_transcode(phone):
    phone.sh(SOURCE, timeout=900)
    return {"hardware_mediacodec_1080p_to_720p": transcode_fps(phone, HW),
            "software_x264_1080p_to_720p": transcode_fps(phone, SW)}


def measure_thermal(phone, minutes=5):
    phone.sh(SOURCE, timeout=900)
    loop = HW.replace("-i /tmp", "-stream_loop -1 -i /tmp").replace("-y /dev/null", f"-t {minutes * 60} -y /dev/null")
    samples = []
    proc = subprocess.Popen(phone.ssh_base + [loop], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            stdin=subprocess.DEVNULL)
    t0 = time.time()
    while proc.poll() is None and time.time() - t0 < minutes * 60 + 60:
        b = battery(phone)
        zones = thermal_zones(phone)
        cpu = [v for k, v in zones.items() if re.search(r"cpu|soc|big|little|mngs|apollo", k, re.I)]
        samples.append({"t": round(time.time() - t0), "battery_c": b["temp_c"], "soc_max_c": max(cpu) if cpu else None})
        time.sleep(10)
    proc.wait()
    soc = [s["soc_max_c"] for s in samples if s["soc_max_c"] is not None]
    return {"minutes_of_continuous_transcoding": minutes,
            "battery_c": {"start": samples[0]["battery_c"], "max": max(s["battery_c"] for s in samples),
                          "end": samples[-1]["battery_c"]},
            "soc_c": {"start": soc[0], "max": max(soc), "end": soc[-1]} if soc else None,
            "samples": samples}


def dashboard_services(phone, password):
    phone.forward(8800)
    req = urllib.request.Request(f"http://{phone.host}:8800/api/services", headers={"Authorization": f"Bearer {password}"})
    return json.load(urllib.request.urlopen(req, timeout=10))


def measure_soak(phone, hours, password):
    checks = ok = 0
    start = {s["name"]: s["restarts"] for s in dashboard_services(phone, password)}
    ports = {"sshd": None, "caddy": 8080, "jellyfin": 8096, "qbittorrent": 8081, "immich-server": 2283, "homeassistant": 8123}
    for p in ports.values():
        if p:
            phone.forward(p)
    t_end = time.time() + hours * 3600
    while time.time() < t_end:
        for name in start:
            port = ports.get(name)
            if not port:
                continue
            checks += 1
            try:
                urllib.request.urlopen(f"http://{phone.host}:{port}/", timeout=10)
                ok += 1
            except urllib.error.HTTPError:
                ok += 1  # answered, even if with a redirect or auth error
            except Exception:
                pass
        time.sleep(60)
    end = {s["name"]: s["restarts"] for s in dashboard_services(phone, password)}
    return {"hours": hours, "http_checks": checks, "availability_percent": round(100 * ok / max(checks, 1), 3),
            "restarts": {n: end.get(n, 0) - start.get(n, 0) for n in start}}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", help="adb device serial")
    ap.add_argument("--host", default="localhost", help="phone address for SSH/HTTP (default: adb-forwarded localhost)")
    ap.add_argument("--password", help="dashboard password (for --soak-hours)")
    ap.add_argument("--battery-minutes", type=float, default=0)
    ap.add_argument("--soak-hours", type=float, default=0)
    ap.add_argument("--thermal-minutes", type=float, default=5)
    ap.add_argument("--only", nargs="*", choices=["idle", "network", "transcode", "thermal", "battery", "soak"])
    ap.add_argument("--out", default="bench-results.json")
    a = ap.parse_args()

    phone = Phone(a.serial, a.host)
    phone.forward(SSH_PORT)
    want = set(a.only or ["idle", "network", "transcode", "thermal"])
    if a.battery_minutes:
        want.add("battery")
    if a.soak_hours:
        want.add("soak")
    prop = lambda k: phone.adb("shell", "getprop", k).strip()
    res = {"device": f"{prop('ro.product.manufacturer')} {prop('ro.product.model')}",
           "soc": prop("ro.soc.model") or prop("ro.board.platform") or prop("ro.hardware"),
           "android": prop("ro.build.version.release"), "uptime_s": phone.adb("shell", "cut -d. -f1 /proc/uptime").strip()}
    steps = [("idle", lambda: measure_idle(phone)), ("network", lambda: measure_network(phone)),
             ("transcode", lambda: measure_transcode(phone)),
             ("thermal", lambda: measure_thermal(phone, a.thermal_minutes)),
             ("battery", lambda: measure_battery(phone, a.battery_minutes)),
             ("soak", lambda: measure_soak(phone, a.soak_hours, a.password))]
    for name, fn in steps:
        if name in want:
            print(f"measuring {name}…", file=sys.stderr)
            try:
                res[name] = fn()
            except Exception as e:  # keep the other measurements
                res[name] = {"error": str(e)}
    Path(a.out).write_text(json.dumps(res, indent=2))
    print(json.dumps({k: v for k, v in res.items() if k != "thermal"} | (
        {"thermal": {k: v for k, v in res["thermal"].items() if k != "samples"}} if "thermal" in res else {}), indent=2))


if __name__ == "__main__":
    main()
