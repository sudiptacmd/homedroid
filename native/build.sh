#!/usr/bin/env bash
# Cross-compiles the bundled daemons into app/src/main/jniLibs/<abi>/lib<name>.so.
#
#   native/build.sh                  # arm64-v8a armeabi-v7a x86_64
#   native/build.sh arm64-v8a        # just one ABI
#
# Binaries are built with GOOS=android and cgo against the NDK so they use bionic's DNS
# resolver: a pure-Go build reads /etc/resolv.conf, which Android doesn't have, and every
# lookup (including cloudflared reaching Cloudflare, or tailscaled its control server) would fail.
set -euo pipefail

CADDY_VERSION="${CADDY_VERSION:-v2.11.4}"
CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-2026.9.3}"
TAILSCALE_VERSION="${TAILSCALE_VERSION:-v1.102.5}"
# Tailscale features Homedroid doesn't use (it runs in userspace mode, without root or a VPN).
TAILSCALE_TAGS="ts_include_cli,ts_omit_aws,ts_omit_bird,ts_omit_tap,ts_omit_kube,ts_omit_completion,ts_omit_ssh"
TAILSCALE_TAGS+=",ts_omit_wakeonlan,ts_omit_capture,ts_omit_relayserver,ts_omit_systray,ts_omit_taildrop,ts_omit_tpm"
TAILSCALE_TAGS+=",ts_omit_desktop_sessions"
API=29

here="$(cd "$(dirname "$0")" && pwd)"
work="$here/.work"
out="$(dirname "$here")/app/src/main/jniLibs"

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk" ]]; then
  sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
  ndk="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[[ -d "$ndk" ]] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"

if (($#)); then abis=("$@"); else abis=(arm64-v8a armeabi-v7a x86_64); fi

fetch() { # url tag dir
  [[ -d "$work/$3" ]] || git clone --quiet --depth 1 --branch "$2" "$1" "$work/$3"
}

gobuild() { # abi srcdir package name [extra-ldflags] [tags]
  local abi=$1 src=$2 pkg=$3 name=$4 extra=${5:-} tags=${6:-} goarch goarm="" cc
  case $abi in
    arm64-v8a)   goarch=arm64; cc=aarch64-linux-android$API-clang ;;
    armeabi-v7a) goarch=arm; goarm=7; cc=armv7a-linux-androideabi$API-clang ;;
    x86_64)      goarch=amd64; cc=x86_64-linux-android$API-clang ;;
    *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$out/$abi"
  echo "==> $name ($abi)"
  (cd "$src" && GOOS=android GOARCH=$goarch GOARM=$goarm CGO_ENABLED=1 CC="$toolchain/$cc" \
    go build -trimpath -buildvcs=false -tags="$tags" -ldflags="-s -w $extra" -o "$out/$abi/lib$name.so" "$pkg")
}

mkdir -p "$work"
fetch https://github.com/caddyserver/caddy.git "$CADDY_VERSION" "caddy-$CADDY_VERSION"
fetch https://github.com/cloudflare/cloudflared.git "$CLOUDFLARED_VERSION" "cloudflared-$CLOUDFLARED_VERSION"
fetch https://github.com/tailscale/tailscale.git "$TAILSCALE_VERSION" "tailscale-$TAILSCALE_VERSION"
(cd "$here/sshd" && go mod tidy)

for abi in "${abis[@]}"; do
  gobuild "$abi" "$here/sshd" . sshd
  gobuild "$abi" "$work/caddy-$CADDY_VERSION" ./cmd/caddy caddy
  gobuild "$abi" "$work/cloudflared-$CLOUDFLARED_VERSION" ./cmd/cloudflared cloudflared \
    "-X main.Version=$CLOUDFLARED_VERSION"
  # One binary for the daemon and, when run as "tailscale", the CLI.
  ts_version="${TAILSCALE_VERSION#v}"
  gobuild "$abi" "$work/tailscale-$TAILSCALE_VERSION" ./cmd/tailscaled tailscaled \
    "-X tailscale.com/version.longStamp=$ts_version -X tailscale.com/version.shortStamp=$ts_version" "$TAILSCALE_TAGS"
done

ls -lh "$out"/*/
