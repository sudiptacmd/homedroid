#!/usr/bin/env bash
# Cross-compiles llama.cpp's server (and llama-bench) for Android: bionic binaries using the
# phone's GPU through Vulkan (any GPU) or OpenCL (Qualcomm Adreno), with CPU backends for
# each ARM generation. GPU and CPU backends are separate libraries loaded at runtime, so a
# phone without OpenCL still runs.
#
# The result isn't bundled in the APK: native/out/llama-runtime-<abi>.tar.gz is attached to
# the GitHub release and downloaded by phones that turn on local AI. Its SHA-256 goes into
# app/src/main/assets/llama-runtime.json, so the app only accepts the bundle it was built with.
#
#   native/build-llama.sh arm64-v8a x86_64
set -euo pipefail

LLAMA_TAG=v0.5.0
LLAMA_COMMIT=7fe450e19305b828c199d602c23a8337aaa1f03b
OPENCL_HEADERS=(v2026.05.29 6fe718c31a45fe25151362a72ef041c3a1047cbd)
OPENCL_LOADER=(v2026.05.29 b7bd2803acc779c03d96588e9ca9e9568a18698a)
VULKAN_HEADERS=(vulkan-sdk-1.4.363.0 6802bb4733b63ed5efd3adb308a6c885ef180ea1)
SPIRV_HEADERS=(vulkan-sdk-1.4.363.0 496543121ce6419f23d6fa5d7194ba66c36212d2)
API=29

here="$(cd "$(dirname "$0")" && pwd)"
work="$here/.work/llama"
out="$here/out"
assets="$(dirname "$here")/app/src/main/assets"

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk" ]]; then
  sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
  ndk="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[[ -d "$ndk" ]] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
toolchain="$ndk/build/cmake/android.toolchain.cmake"
sysroot="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
glslc="$(ls "$ndk"/shader-tools/linux-x86_64/glslc 2>/dev/null || command -v glslc)"
[[ -x "$glslc" ]] || { echo "glslc not found (NDK shader-tools)" >&2; exit 1; }

if (($#)); then abis=("$@"); else abis=(arm64-v8a x86_64); fi

# Clones repo $1 at tag $2 into $3 and checks it is exactly commit $4.
fetch() {
  local repo=$1 tag=$2 dir=$3 commit=$4
  if [[ ! -d "$dir" ]]; then
    git clone -q --depth 1 --branch "$tag" "https://github.com/$repo.git" "$dir"
  fi
  local got
  got="$(git -C "$dir" rev-parse HEAD)"
  [[ "$got" == "$commit" ]] || { echo "$repo $tag is $got, expected $commit" >&2; exit 1; }
}

mkdir -p "$work" "$out"
cd "$work"
fetch ggml-org/llama.cpp "$LLAMA_TAG" llama.cpp "$LLAMA_COMMIT"
fetch KhronosGroup/OpenCL-Headers "${OPENCL_HEADERS[0]}" opencl-headers "${OPENCL_HEADERS[1]}"
fetch KhronosGroup/OpenCL-ICD-Loader "${OPENCL_LOADER[0]}" opencl-loader "${OPENCL_LOADER[1]}"
fetch KhronosGroup/Vulkan-Headers "${VULKAN_HEADERS[0]}" vulkan-headers "${VULKAN_HEADERS[1]}"
fetch KhronosGroup/SPIRV-Headers "${SPIRV_HEADERS[0]}" spirv-headers "${SPIRV_HEADERS[1]}"

# Header-only packages, installed once for every ABI.
cmake -S spirv-headers -B build-spirv -G Ninja -DCMAKE_INSTALL_PREFIX="$work/host" -DSPIRV_HEADERS_ENABLE_TESTS=OFF > /dev/null
cmake --install build-spirv > /dev/null

manifest="{\"llama\": \"$LLAMA_TAG\", \"abis\": {"
first=1
for abi in "${abis[@]}"; do
  echo "== llama.cpp $LLAMA_TAG for $abi"
  prefix="$work/install-$abi"
  rm -rf "$prefix" "build-$abi"

  # OpenCL: build the Khronos loader only to link against. On the phone, the vendor's own
  # libOpenCL.so (present on Qualcomm phones) is used; elsewhere the backend just doesn't load.
  cmake -S opencl-loader -B "build-opencl-$abi" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" \
    -DOPENCL_ICD_LOADER_HEADERS_DIR="$work/opencl-headers" -DBUILD_TESTING=OFF > /dev/null
  cmake --build "build-opencl-$abi" > /dev/null

  cpu=(-DGGML_CPU_ALL_VARIANTS=ON)
  [[ $abi == arm64-v8a ]] && cpu+=(-DGGML_CPU_KLEIDIAI=ON)

  cmake -S llama.cpp -B "build-$abi" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_shared \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
    "${cpu[@]}" \
    -DGGML_VULKAN=ON -DVulkan_INCLUDE_DIR="$work/vulkan-headers/include" \
    -DVulkan_LIBRARY="$sysroot/usr/lib/$(case $abi in arm64-v8a) echo aarch64-linux-android;; x86_64) echo x86_64-linux-android;; esac)/$API/libvulkan.so" \
    -DVulkan_GLSLC_EXECUTABLE="$glslc" -DSPIRV-Headers_DIR="$work/host/share/cmake/SPIRV-Headers" \
    -DGGML_OPENCL=ON -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
    -DOpenCL_INCLUDE_DIR="$work/opencl-headers" -DOpenCL_LIBRARY="$work/build-opencl-$abi/libOpenCL.so" \
    -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON
  cmake --build "build-$abi" --target llama-server llama-bench --parallel
  cmake --install "build-$abi" > /dev/null

  # What the phone runs: the two programs, every library they load, and the C++ runtime.
  bundle="$work/bundle-$abi"
  rm -rf "$bundle"; mkdir -p "$bundle"
  cp "$prefix/bin/llama-server" "$prefix/bin/llama-bench" "$bundle/"
  find "$prefix/lib" -maxdepth 1 -name '*.so*' -exec cp -P {} "$bundle/" \;
  triple=$(case $abi in arm64-v8a) echo aarch64-linux-android;; x86_64) echo x86_64-linux-android;; esac)
  cp "$sysroot/usr/lib/$triple/libc++_shared.so" "$bundle/"
  # The vendor's OpenCL is used at runtime, never this stub loader.
  rm -f "$bundle"/libOpenCL.so*
  "$ndk"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded "$bundle"/llama-* "$bundle"/*.so 2>/dev/null || true

  tarball="$out/llama-runtime-$abi.tar.gz"
  # Reproducible: fixed order, owners and times.
  tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 -C "$bundle" -czf "$tarball" .
  sha=$(sha256sum "$tarball" | cut -d' ' -f1)
  size=$(stat -c %s "$tarball")
  echo "$abi: $(basename "$tarball") $size bytes $sha"
  ((first)) || manifest+=", "
  first=0
  manifest+="\"$abi\": {\"file\": \"$(basename "$tarball")\", \"sha256\": \"$sha\", \"bytes\": $size}"
done
manifest+="}}"
echo "$manifest" > "$assets/llama-runtime.json"
echo "wrote $assets/llama-runtime.json"
