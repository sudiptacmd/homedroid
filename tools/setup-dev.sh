#!/usr/bin/env bash
# Installs the project-local build tools into .tools/ on Linux and macOS (the bash
# counterpart of the Windows setup): JDK 21, Android SDK 36 with platform-tools and,
# optionally, the NDK, Go and an x86_64 emulator.
#
#   tools/setup-dev.sh                 # JDK + SDK: enough for Kotlin and dashboard changes
#   tools/setup-dev.sh --ndk --go      # also what native/build-all.sh needs
#   tools/setup-dev.sh --emulator      # also an emulator and a "homedroid" virtual device
#
# Then: source tools/dev.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tools="$root/.tools"
want_ndk=0 want_go=0 want_emu=0
for a in "$@"; do
  case "$a" in
    --ndk) want_ndk=1 ;;
    --go) want_go=1 ;;
    --emulator) want_emu=1 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

case "$(uname -s)" in
  Linux) os=linux; sdk_os=linux ;;
  Darwin) os=mac; sdk_os=mac ;;
  *) echo "Use tools/dev.ps1 on Windows" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch=x64; goarch=amd64; emu_abi=x86_64 ;;
  arm64|aarch64) arch=aarch64; goarch=arm64; emu_abi=arm64-v8a ;;
  *) echo "unsupported CPU: $(uname -m)" >&2; exit 1 ;;
esac

mkdir -p "$tools"
fetch() { curl -fL --retry 3 --progress-bar -o "$2" "$1"; }

# JDK 21 (Eclipse Temurin)
if ! ls -d "$tools"/java/*/ >/dev/null 2>&1; then
  echo "── JDK 21"
  mkdir -p "$tools/java"
  fetch "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse" "$tools/jdk.tar.gz"
  tar -xzf "$tools/jdk.tar.gz" -C "$tools/java"
  rm "$tools/jdk.tar.gz"
fi
java_home="$(ls -d "$tools"/java/*/ | head -1)"
[ -d "$java_home/Contents/Home" ] && java_home="$java_home/Contents/Home"
export JAVA_HOME="${java_home%/}"

# Android command-line tools, newest listed in Google's repository
sdk="$tools/android-sdk"
if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "── Android command-line tools"
  zip="$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml \
    | grep -o "commandlinetools-$sdk_os-[0-9]*_latest.zip" | sort -t- -k3 -n | tail -1)"
  fetch "https://dl.google.com/android/repository/$zip" "$tools/cmdline-tools.zip"
  rm -rf "$sdk/cmdline-tools/latest" "$tools/cmdline-tools"
  mkdir -p "$sdk/cmdline-tools"
  unzip -q "$tools/cmdline-tools.zip" -d "$tools"
  mv "$tools/cmdline-tools" "$sdk/cmdline-tools/latest"
  rm "$tools/cmdline-tools.zip"
fi
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdkmanager" --sdk_root="$sdk" --licenses >/dev/null || true
pkgs=("platform-tools" "platforms;android-36" "build-tools;36.0.0")
[ $want_ndk = 1 ] && pkgs+=("ndk;30.0.16248370")
[ $want_emu = 1 ] && pkgs+=("emulator" "system-images;android-34;google_apis;$emu_abi")
echo "── SDK packages: ${pkgs[*]}"
"$sdkmanager" --sdk_root="$sdk" --install "${pkgs[@]}" >/dev/null

if [ $want_go = 1 ] && [ ! -x "$tools/go/bin/go" ]; then
  echo "── Go"
  v="$(curl -fsSL 'https://go.dev/VERSION?m=text' | head -1)"
  goos=$([ $os = mac ] && echo darwin || echo linux)
  fetch "https://go.dev/dl/$v.$goos-$goarch.tar.gz" "$tools/go.tar.gz"
  tar -xzf "$tools/go.tar.gz" -C "$tools"
  rm "$tools/go.tar.gz"
fi

if [ $want_emu = 1 ] && ! "$sdk/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: homedroid"; then
  echo "── Virtual device \"homedroid\""
  export ANDROID_AVD_HOME="$tools/avd"
  mkdir -p "$ANDROID_AVD_HOME"
  echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n homedroid \
    -k "system-images;android-34;google_apis;$emu_abi" -d pixel_6 >/dev/null
fi

echo "Done. Run: source tools/dev.sh"
