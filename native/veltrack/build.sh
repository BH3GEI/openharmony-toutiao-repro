#!/usr/bin/env bash
# libwlveltrack.so -- supplies android.view.VelocityTracker's seven native
# methods, which this adapter's framework.jar declares but nothing implements.
# See wl_veltrack.c for why, and docs/INPUT_PATH_ANALYSIS.md for how it is found.
#
# OHOS_NDK   OpenHarmony native SDK root (contains llvm/ and sysroot/),
#            e.g. export OHOS_NDK=$HOME/ohsdk/linux/native-x/native
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${OHOS_NDK:?set OHOS_NDK to the OpenHarmony native SDK (…/native)}"
CC="$OHOS_NDK/llvm/bin/aarch64-unknown-linux-ohos-clang"
[ -x "$CC" ] || { echo "no cross clang at $CC" >&2; exit 1; }
mkdir -p "$HERE/build"
"$CC" --sysroot="$OHOS_NDK/sysroot" -shared -fPIC -O2 -Wall \
      -fvisibility=hidden \
      -o "$HERE/build/libwlveltrack.so" "$HERE/wl_veltrack.c"
"$OHOS_NDK/llvm/bin/llvm-nm" --dynamic --defined-only "$HERE/build/libwlveltrack.so" \
    | grep ' T Java_' | sed 's/^/  /'
ls -l "$HERE/build/libwlveltrack.so"
