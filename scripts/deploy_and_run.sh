#!/usr/bin/env bash
# deploy_and_run.sh -- push the patched artifacts to a DAYU200 board, launch
# Toutiao's MainActivity and capture a frame series.
#
#   scripts/deploy_and_run.sh                     # deploy everything, then run
#   scripts/deploy_and_run.sh --run-only          # just launch + capture
#   scripts/deploy_and_run.sh --apk path/to.apk   # override the apk
#   scripts/deploy_and_run.sh --tls               # also push the TLS bridge payload
#   scripts/deploy_and_run.sh --all               # TLS gateway + ALooper + input pump
#
# Everything is relative to the repo; the only host assumption is that `hdc`
# talks to the board.  Override with:
#   HDC        hdc binary or wrapper        (default: hdc)
#   HDC_TARGET board serial -> `hdc -t …`   (default: unset, single board)
#   OUTDIR     where frames land locally    (default: out/)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
HDC="${HDC:-hdc}"
OUTDIR="${OUTDIR:-$ROOT/out}"

APK="${APK:-$ROOT/prebuilts/base.final6.apk}"
JAR="${JAR:-$ROOT/prebuilts/oh-adapter-runtime.jar}"
STACKGROW="${STACKGROW:-$ROOT/prebuilts/libwestlake_stackgrow.so}"
ICUSHIM="${ICUSHIM:-$ROOT/prebuilts/libwlicu.so}"
TTTEXT="${TTTEXT:-$ROOT/prebuilts/libtttext_lite.patched.so}"

RUN_ONLY=0
WITH_TLS=0
WITH_ALL=0
while [ $# -gt 0 ]; do
    case "$1" in
        --run-only) RUN_ONLY=1; shift ;;
        --tls)      WITH_TLS=1; shift ;;
        --all)      WITH_ALL=1; shift ;;
        --apk)      APK="$2"; shift 2 ;;
        --jar)      JAR="$2"; shift 2 ;;
        -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

# --tls needs the apk that carries classes23.dex
if [ "$WITH_TLS" = 1 ]; then
    # --tls needs both the apk carrying classes23.dex and the jar carrying the
    # TLS gateway (ActivityManagerRouting.hijackTlsShim).
    [ "$APK" = "$ROOT/prebuilts/base.final6.apk" ] && APK="$ROOT/prebuilts/base.final7.apk"
    [ "$JAR" = "$ROOT/prebuilts/oh-adapter-runtime.jar" ] && JAR="$ROOT/prebuilts/oh-adapter-runtime.tls.jar"
fi

# --all is the merged adapter: first frame + TLS gateway + ALooper shim + input
# pump.  Built locally rather than shipped as a Release artifact, because it is
# the piece under active development.  It implies --tls: the TLS gateway needs
# classes23.dex in the apk and the wl-tls payload on the board.
if [ "$WITH_ALL" = 1 ]; then
    ALL_JAR="$ROOT/amr/build/oh-adapter-runtime.all.jar"
    if [ ! -f "$ALL_JAR" ]; then
        echo "missing $ALL_JAR
build it first:
  JAVA_HOME=<jdk11+> \\
  SRC=amr/src/adapter/activity/ActivityManagerRouting.all.java \\
  OUT=amr/build/oh-adapter-runtime.all.jar amr/build_amr.sh" >&2
        exit 1
    fi
    JAR="$ALL_JAR"
    WITH_TLS=1
    [ "$APK" = "$ROOT/prebuilts/base.final6.apk" ] && APK="$ROOT/prebuilts/base.final7.apk"
fi

hdc() { if [ -n "${HDC_TARGET:-}" ]; then "$HDC" -t "$HDC_TARGET" "$@"; else "$HDC" "$@"; fi; }
sh_() { hdc shell "$@"; }

PKG=com.ss.android.article.news
BUNDLE=/data/app/el1/bundle/public/$PKG
LIBDIR=$BUNDLE/android/lib/arm64-v8a
LIBDIR2=/data/app/el2/100/base/$PKG/app_lib

banner() { printf '\n=== %s ===\n' "$*"; }

if [ "$RUN_ONLY" = 0 ]; then
    banner "preflight"
    for f in "$APK" "$JAR" "$STACKGROW" "$ICUSHIM" "$TTTEXT"; do
        [ -f "$f" ] || { echo "missing $f
run scripts/fetch_prebuilts.sh first, or build from source (see README)" >&2; exit 1; }
    done
    sh_ "cat /data/service/el1/public/appspawnx/pr03-boot-recovery.txt 2>/dev/null | tail -3"

    banner "push app apk ($(basename "$APK"))"
    hdc file send "$APK" /data/local/tmp/base.final6.apk
    sh_ "B=$BUNDLE/android/base.apk;
         cp /data/local/tmp/base.final6.apk \$B &&
         chown installs:installs \$B && chmod 644 \$B &&
         chcon u:object_r:data_app_el1_file:s0 \$B &&
         rm -f /data/local/tmp/base.final6.apk && echo '  apk ok'"

    banner "push adapter runtime jar"
    hdc file send "$JAR" /data/local/tmp/oh-adapter-runtime.jar
    # /system/android/framework is a bind mount from the pr03 portable tree;
    # write through the source so a runtime-recover run does not undo it.
    # Keep the jar that is currently on the board: it is the only copy of
    # whatever the last person deployed, and rolling back matters more than
    # disk.  .bak is overwritten each time; .orig is written once, ever.
    sh_ "T=/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar;
         [ -f \$T.orig ] || cp \$T \$T.orig;
         cp \$T \$T.bak;
         cp /data/local/tmp/oh-adapter-runtime.jar \$T && chmod 644 \$T &&
         chcon u:object_r:system_file:s0 \$T 2>/dev/null;
         md5sum \$T /system/android/framework/oh-adapter-runtime.jar"

    banner "push native shims"
    hdc file send "$STACKGROW" /data/local/tmp/libwestlake_stackgrow.so
    sh_ "for T in /system/android/lib64 /data/pr03-74e6-portable/android/lib64; do
           cp /data/local/tmp/libwestlake_stackgrow.so \$T/ ; done
         chmod 644 /system/android/lib64/libwestlake_stackgrow.so
         chcon u:object_r:system_file:s0 /system/android/lib64/libwestlake_stackgrow.so 2>/dev/null
         echo '  stackgrow ok'"

    hdc file send "$ICUSHIM"  /data/local/tmp/libwlicu.so
    hdc file send "$TTTEXT"   /data/local/tmp/libtttext_lite.so
    sh_ "for D in $LIBDIR $LIBDIR2; do
           [ -d \$D ] || continue
           cp /data/local/tmp/libwlicu.so \$D/libwlicu.so
           cp /data/local/tmp/libwlicu.so \$D/libwlic18n.so
           chmod 755 \$D/libwlicu.so \$D/libwlic18n.so
         done
         cp /data/local/tmp/libtttext_lite.so $LIBDIR/libtttext_lite.so
         chmod 755 $LIBDIR/libtttext_lite.so
         rm -f /data/local/tmp/libwlicu.so /data/local/tmp/libtttext_lite.so
         echo '  icu shim + patched tttext ok'"

    if [ "$WITH_TLS" = 1 ]; then
        banner "push TLS bridge payload"
        # The adapter has no Java TLS at all; tls-bridge/ carries BouncyCastle's
        # pure-Java JSSE plus a prebaked 133-root trust store.  These are *new*
        # files -- nothing on the board is overwritten.  Needs an apk built with
        # --tls (classes23.dex) and an adapter jar containing the TLS gateway.
        for f in "$ROOT/tls-bridge/prebuilt/wl-tls.jar" "$ROOT/tls-bridge/prebuilt/wl-cacerts.p12"; do
            [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
            hdc file send "$f" "/data/local/tmp/$(basename "$f")"
        done
        sh_ "ls -l /data/local/tmp/wl-tls.jar /data/local/tmp/wl-cacerts.p12"
    fi

    banner "grant INTERNET via AccessToken"
    # The adapter's installer maps no permissions at all, so BMS/ATM report the
    # app as having none and appspawn installs a seccomp filter that fails
    # socket(AF_INET) with EPERM.  Insert the grant the way every other app has it.
    hdc file send "$HERE/grant_internet.sh" /data/local/tmp/grant_internet.sh
    sh_ "sh /data/local/tmp/grant_internet.sh"
fi

banner "launch + capture"
hdc file send "$HERE/keepawake.sh" /data/local/tmp/keepawake.sh
hdc file send "$HERE/run_capture.sh" /data/local/tmp/run_capture.sh
sh_ "sh /data/local/tmp/run_capture.sh" || true

banner "pull frames"
mkdir -p "$OUTDIR"
for t in 20 40 60 80 90 100 110 120 140 160 180; do
    hdc file recv "/data/local/tmp/F_$t.jpeg" "$OUTDIR/F_$t.jpeg" >/dev/null 2>&1 || true
done
ls -l "$OUTDIR" 2>/dev/null | tail -n +2 || true

cat <<'EOT'

A frame around 38 KB is the empty (white) window; ~70 KB is the rendered
MainActivity.  Expect white until roughly t=60s and the main interface from
t=80s on -- the whole startup runs in the ART interpreter (APPSPAWNX_FORCE_INT=1),
so it is slow but it does get there.

Without --tls the feed stays empty by construction: the adapter's SSLContext is a
construct-only stub, so no article can be fetched.  With --tls the handshakes do
succeed (see tls-bridge/README.md) but the feed is still empty -- TTNet cancels
its own connections.  Either way the acceptance for this repo is the first frame:
search bar, channel tabs and bottom nav rendered.

Note: OH multimodal input never delivers touch to the app on this adapter, so
uinput cannot drive the UI -- see docs/INPUT_PATH_ANALYSIS.md.  Deploy with
--input and use scripts/wl_input.sh to drive it from the shell instead.
EOT
