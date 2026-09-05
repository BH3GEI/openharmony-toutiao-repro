#!/usr/bin/env bash
# libwlicu.so -- unversioned/_74 ICU entry points forwarding to the adapter's _72.
#
# libtttext_lite.so derives the ICU version by scanning /system/usr/icu and
# parsing icudt<NN>l.dat; on this board that directory holds OpenHarmony's
# icudt74l.dat, so it dlsym()s ubrk_open_74 -- but the adapter's Android side
# ICU is version 72.  Every lookup returns NULL and the first call jumps to 0.
#
# Each entry is a bare `b <name>_72` tail branch, so arguments, return value and
# varargs pass through untouched and no ICU headers are needed.
#
# OHOS_NDK   OpenHarmony native SDK root
# ICUUC      the board's /system/android/lib64/libicuuc.so (pull it with hdc)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${OHOS_NDK:?set OHOS_NDK to the OpenHarmony native SDK (…/native)}"
ICUUC="${ICUUC:-$HERE/prebuilt/libicuuc.so}"
[ -f "$ICUUC" ] || { echo "need the board's libicuuc.so; pull it with:
  hdc file recv /system/android/lib64/libicuuc.so $HERE/prebuilt/libicuuc.so" >&2; exit 1; }
CC="$OHOS_NDK/llvm/bin/aarch64-unknown-linux-ohos-clang"
mkdir -p "$HERE/build"
"$CC" --sysroot="$OHOS_NDK/sysroot" -shared -fPIC -O2 \
      -o "$HERE/build/libwlicu.so" "$HERE/wl_icu_shim.c" "$ICUUC"
"$OHOS_NDK/llvm/bin/llvm-nm" --dynamic --defined-only "$HERE/build/libwlicu.so" | grep -c ' T ' \
  | xargs echo "exported symbols:"
