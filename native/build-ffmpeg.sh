#!/usr/bin/env bash
# Cross-compiles an Android-native (bionic) FFmpeg with MediaCodec into
# app/src/main/jniLibs/<abi>/libffmpeg.so, for hardware-accelerated transcoding.
#
# Alpine's FFmpeg can't use MediaCodec: it's only reachable through Android's own libraries
# (libmediandk) and binder. This build links against them; the app's ffmpeg shim runs it for
# hardware encodes and uses Alpine's FFmpeg for everything else.
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-9.0.2}"
FFMPEG_SHA256="${FFMPEG_SHA256:-8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e}"
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
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"

if (($#)); then abis=("$@"); else abis=(arm64-v8a armeabi-v7a x86_64); fi

mkdir -p "$work"
cd "$work"
tarball="ffmpeg-$FFMPEG_VERSION.tar.xz"
[[ -f "$tarball" ]] || curl -fsSL -o "$tarball" "https://ffmpeg.org/releases/$tarball"
echo "$FFMPEG_SHA256  $tarball" | sha256sum -c --quiet
if [[ ! -d "ffmpeg-$FFMPEG_VERSION" ]]; then
  tar -xJf "$tarball"
  for p in "$here"/patches/ffmpeg-*.patch; do patch -s -p1 -d "ffmpeg-$FFMPEG_VERSION" < "$p"; done
fi

for abi in "${abis[@]}"; do
  extra=()
  case $abi in
    arm64-v8a)   triple=aarch64-linux-android;    arch=aarch64 ;;
    armeabi-v7a) triple=armv7a-linux-androideabi; arch=arm; extra=(--cpu=armv7-a --enable-neon) ;;
    # Emulator builds only: skip nasm, speed doesn't matter there.
    x86_64)      triple=x86_64-linux-android;     arch=x86_64; extra=(--disable-x86asm) ;;
    *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
  esac
  echo "==> ffmpeg ($abi)"
  build="$work/ffmpeg-build-$abi"
  rm -rf "$build" && mkdir -p "$build"
  (
    cd "$build"
    "$work/ffmpeg-$FFMPEG_VERSION/configure" \
      --target-os=android --arch="$arch" --enable-cross-compile "${extra[@]}" \
      --cc="$tc/$triple$API-clang" --cxx="$tc/$triple$API-clang++" \
      --ar="$tc/llvm-ar" --nm="$tc/llvm-nm" --ranlib="$tc/llvm-ranlib" --strip="$tc/llvm-strip" \
      --enable-static --disable-shared --enable-pic --disable-debug --disable-doc \
      --disable-ffplay --disable-ffprobe --disable-avdevice --disable-vulkan \
      `# jni is required by configure; with no Java VM at runtime it uses the NDK API` \
      --enable-mediacodec --enable-jni --pkg-config=false \
      --extra-ldflags="-Wl,-z,max-page-size=16384" >/dev/null
    make -j"$(nproc)" ffmpeg >/dev/null 2>&1 || make ffmpeg
    mkdir -p "$out/$abi"
    "$tc/llvm-strip" ffmpeg -o "$out/$abi/libffmpeg.so"
  )
done
ls -l "$out"/*/libffmpeg.so
