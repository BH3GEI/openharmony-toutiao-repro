#!/usr/bin/env bash
# libwestlake_stackgrow.so -- LD_PRELOADed into every adapter app child
# (see /system/etc/init/appspawn_x.cfg: LD_PRELOAD=...:libwestlake_stackgrow.so).
#
# Does four things, all documented inline in wl_stackgrow.c:
#   1. pre-touch the main thread stack up to RLIMIT_STACK (musl grows lazily)
#   2. interpose pthread_getattr_np so ART sees the ffrt coroutine stack
#   3. capture ART fatals (libart's LOG(FATAL) reaches no log on this adapter)
#   4. bypass musl's fdsan on close()
#
# OHOS_NDK -- OpenHarmony native SDK root (contains llvm/ and sysroot/)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${OHOS_NDK:?set OHOS_NDK to the OpenHarmony native SDK (…/native), e.g. export OHOS_NDK=\$HOME/ohsdk/linux/native-x/native}"
CC="$OHOS_NDK/llvm/bin/aarch64-unknown-linux-ohos-clang"
[ -x "$CC" ] || { echo "no cross clang at $CC" >&2; exit 1; }
mkdir -p "$HERE/build"
"$CC" --sysroot="$OHOS_NDK/sysroot" -shared -fPIC -O2 -Wall \
      -fno-omit-frame-pointer -fno-optimize-sibling-calls \
      -o "$HERE/build/libwestlake_stackgrow.so" "$HERE/wl_stackgrow.c"
ls -l "$HERE/build/libwestlake_stackgrow.so"
