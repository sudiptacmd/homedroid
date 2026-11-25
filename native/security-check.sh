#!/usr/bin/env bash
# Scan the exact Android package imports, including build tags. Module-only advisories
# in packages which are not imported (notably deprecated OpenPGP) are reported separately.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$tc/aarch64-linux-android29-clang" GOFLAGS=-mod=mod
tags="ts_include_cli,ts_omit_aws,ts_omit_bird,ts_omit_tap,ts_omit_kube,ts_omit_completion,ts_omit_ssh,ts_omit_wakeonlan,ts_omit_capture,ts_omit_relayserver,ts_omit_systray,ts_omit_taildrop,ts_omit_tpm,ts_omit_desktop_sessions"
failed=()
scan() { local name=$1 dir=$2; shift 2
  echo "=== govulncheck: $name"
  (cd "$dir" && govulncheck -scan=package "$@") || failed+=("$name")
}
scan sshd "$here/sshd" ./...
scan caddy "$here/.work/caddy-v2.11.4" ./cmd/caddy
scan cloudflared "$here/.work/cloudflared-2026.9.3" ./cmd/cloudflared
scan tailscaled "$here/.work/tailscale-v1.102.5" -tags="$tags" ./cmd/tailscaled
if ((${#failed[@]})); then echo "Vulnerable: ${failed[*]}" >&2; exit 1; fi
