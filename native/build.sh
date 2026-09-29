#!/usr/bin/env bash
# Cross-compiles the bundled daemons into app/src/main/jniLibs/<abi>/lib<name>.so.
#
#   native/build.sh                  # arm64-v8a armeabi-v7a x86_64
#   native/build.sh arm64-v8a        # just one ABI
#
# Binaries are built with GOOS=android and cgo against the NDK so they use bionic's DNS
# resolver: a pure-Go build reads /etc/resolv.conf, which Android doesn't have, and every
# lookup (including cloudflared reaching Cloudflare) would fail.
set -euo pipefail

CADDY_VERSION="${CADDY_VERSION:-v2.11.4}"
CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-2026.9.3}"
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

gobuild() { # abi srcdir package name [extra-ldflags]
  local abi=$1 src=$2 pkg=$3 name=$4 extra=${5:-} goarch goarm="" cc
  case $abi in
    arm64-v8a)   goarch=arm64; cc=aarch64-linux-android$API-clang ;;
    armeabi-v7a) goarch=arm; goarm=7; cc=armv7a-linux-androideabi$API-clang ;;
    x86_64)      goarch=amd64; cc=x86_64-linux-android$API-clang ;;
    *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$out/$abi"
  echo "==> $name ($abi)"
  (cd "$src" && GOOS=android GOARCH=$goarch GOARM=$goarm CGO_ENABLED=1 CC="$toolchain/$cc" \
    go build -trimpath -buildvcs=false -ldflags="-s -w $extra" -o "$out/$abi/lib$name.so" "$pkg")
}

mkdir -p "$work"
fetch https://github.com/caddyserver/caddy.git "$CADDY_VERSION" "caddy-$CADDY_VERSION"
fetch https://github.com/cloudflare/cloudflared.git "$CLOUDFLARED_VERSION" "cloudflared-$CLOUDFLARED_VERSION"
(cd "$here/sshd" && go mod tidy)

for abi in "${abis[@]}"; do
  gobuild "$abi" "$here/sshd" . sshd
  gobuild "$abi" "$work/caddy-$CADDY_VERSION" ./cmd/caddy caddy
  gobuild "$abi" "$work/cloudflared-$CLOUDFLARED_VERSION" ./cmd/cloudflared cloudflared \
    "-X main.Version=$CLOUDFLARED_VERSION"
done

ls -lh "$out"/*/
