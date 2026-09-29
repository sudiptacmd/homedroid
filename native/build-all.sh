#!/usr/bin/env bash
# Builds every bundled binary into app/src/main/jniLibs/. Pass ABIs to limit the build:
#   native/build-all.sh arm64-v8a
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
"$here/build.sh" "$@"
"$here/build-proot.sh" "$@"
"$here/build-ffmpeg.sh" "$@"
