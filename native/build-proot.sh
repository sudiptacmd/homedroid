#!/usr/bin/env bash
# Cross-compiles proot (+ static talloc) into app/src/main/jniLibs/<abi>/:
#   libproot.so          the tracer
#   libproot-loader.so   loader for 64-bit guests (32-bit on armeabi-v7a)
#   libproot-loader32.so loader for 32-bit guests on 64-bit ABIs
#
# The loaders must live in nativeLibraryDir: SDK 29+ forbids execve() of app-data files, so
# proot's default of extracting its bundled loader to a temp dir would fail. The app points
# PROOT_LOADER / PROOT_LOADER_32 at these instead.
set -euo pipefail

PROOT_VERSION="${PROOT_VERSION:-5.1.107.95}"
PROOT_SHA256="${PROOT_SHA256:-f76716a9531c25be6f0bf7eb21003f0ebec3f832085a7fc233c3f475e75e4bf5}"
TALLOC_VERSION="${TALLOC_VERSION:-2.4.3}"
TALLOC_SHA256="${TALLOC_SHA256:-dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd}"
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

# fetch <file> <sha256> <url>...: download with fallbacks and verify
fetch() {
  local file=$1 sum=$2; shift 2
  if [[ ! -f "$file" ]] || ! echo "$sum  $file" | sha256sum -c --quiet 2>/dev/null; then
    for url in "$@"; do curl -fsSL --retry 2 --max-time 120 -o "$file" "$url" && break; done
  fi
  echo "$sum  $file" | sha256sum -c --quiet
}

mkdir -p "$work"
cd "$work"
fetch "proot-$PROOT_VERSION.tar.gz" "$PROOT_SHA256" \
  "https://github.com/termux/proot/archive/v$PROOT_VERSION.tar.gz"
fetch "talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256" \
  "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
  "https://distfiles.alpinelinux.org/distfiles/v3.24/talloc-$TALLOC_VERSION.tar.gz"
[[ -d "proot-$PROOT_VERSION" ]] || tar -xzf "proot-$PROOT_VERSION.tar.gz"
[[ -d "talloc-$TALLOC_VERSION" ]] || tar -xzf "talloc-$TALLOC_VERSION.tar.gz"

for abi in "${abis[@]}"; do
  case $abi in
    arm64-v8a)   triple=aarch64-linux-android ;;
    armeabi-v7a) triple=armv7a-linux-androideabi ;;
    x86_64)      triple=x86_64-linux-android ;;
    *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
  esac
  cc="$tc/$triple$API-clang"
  prefix="$work/sysroot-$abi"
  echo "==> talloc ($abi)"
  if [[ ! -f "$prefix/lib/libtalloc.a" ]]; then
    rm -rf "talloc-build-$abi" && cp -r "talloc-$TALLOC_VERSION" "talloc-build-$abi"
    (
      cd "talloc-build-$abi"
      cat > cross-answers.txt <<'ANS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANS
      CC="$cc" AR="$tc/llvm-ar" ./configure --prefix="$prefix" --disable-rpath --disable-python \
        --cross-compile --cross-answers=cross-answers.txt >/dev/null
      make -j"$(nproc)" >/dev/null
      mkdir -p "$prefix/lib" "$prefix/include"
      "$tc/llvm-ar" rcs "$prefix/lib/libtalloc.a" bin/default/talloc.c*.o
      cp talloc.h "$prefix/include/"
    )
  fi

  echo "==> proot ($abi)"
  rm -rf "proot-build-$abi" && cp -r "proot-$PROOT_VERSION" "proot-build-$abi"
  (
    cd "proot-build-$abi/src"
    # Newer clang rejects implicit declarations; this file forgot <string.h>.
    sed -i '1i #include <string.h>' extension/ashmem_memfd/ashmem_memfd.c
    for p in "$here"/patches/proot-*.patch; do patch -s -p1 -d .. < "$p"; done
    # Passed via the environment: the makefile appends its own flags with +=.
    export PROOT_UNBUNDLE_LOADER=/nonexistent # loaders come from PROOT_LOADER at runtime
    export CPPFLAGS="-I$prefix/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\""
    export LDFLAGS="-L$prefix/lib"
    make -j"$(nproc)" CC="$cc" STRIP="$tc/llvm-strip" OBJCOPY="$tc/llvm-objcopy" OBJDUMP="$tc/llvm-objdump" \
      proot >/dev/null
    mkdir -p "$out/$abi"
    "$tc/llvm-strip" proot -o "$out/$abi/libproot.so"
    cp loader/loader "$out/$abi/libproot-loader.so"
    if [[ -f loader/loader-m32 ]]; then cp loader/loader-m32 "$out/$abi/libproot-loader32.so"; fi
  )
done
ls -l "$out"/*/libproot*
