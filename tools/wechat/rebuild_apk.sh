#!/usr/bin/env bash
# Rebuild the deployable WeChat APK from out/weixin-patched.apk by re-applying
# every dex neutralisation this bring-up depends on, in one deterministic pass.
#
# out/weixin-patched.apk already carries the first round (classes.dex: cp.v0 +
# NativeCrash; classes12.dex: cp.v0, NativeCrash, SignalAnrTracer).  Everything
# below is layered on top of it, so the result is reproducible from a clean tree
# instead of depending on scratch files under /private/tmp.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/out/weixin-patched.apk"
OUT="${1:-$ROOT/out/weixin-deploy.apk}"
W="${TMPDIR:-/tmp}/wl-apk-rebuild"
DEXPATCH="$ROOT/scripts/dexpatch_methods.py"
RETNULL="$ROOT/scripts/patch_retnull.py"
SPLASH="$ROOT/scripts/patch_splash_target.py"

rm -rf "$W"; mkdir -p "$W"
python3 - "$SRC" "$W" <<'PY'
import sys, zipfile
src, w = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
for n in ('classes12.dex', 'classes16.dex'):
    open(f"{w}/{n}", 'wb').write(z.read(n))
print(f"extracted classes12.dex classes16.dex")
PY

echo "== classes12: telephony / connectivity / GMS permissionToOp / splash target =="
python3 "$DEXPATCH" "$W/classes12.dex" "$W/c12.a" \
    'Lcom/tencent/mars/comm/NetworkSignalUtilImpl;->InitNetworkSignalUtil(Landroid/content/Context;)V' \
    'Lcom/tencent/mm/sdk/platformtools/i0;->k()V' \
    'Lcom/tencent/mm/sdk/platformtools/i0;->l()V' \
    'Lcom/tencent/mm/sdk/platformtools/i0;->m()V'
python3 "$RETNULL" "$W/c12.a" "$W/c12.b" 'Lz2/p;->d(Ljava/lang/String;)Ljava/lang/String;'
python3 "$SPLASH"  "$W/c12.b" "$W/c12.c"
# Skip only the activity's hand-off (the in-app startActivity OH AMS refuses) and
# let the activity show itself, so LauncherUI builds its UI in place.  The gate
# j.b() keeps its real value -- forcing it false also rewires Application.onCreate
# (WeChatSplash.a reads the same gate) and wedges startup in a ForkJoin wait.
if [ "${NOHANDOFF:-1}" = "1" ]; then
    python3 "$ROOT/scripts/patch_splash_nohandoff.py" "$W/c12.c" "$W/c12.d"
else
    echo "  NOHANDOFF=0: leaving the splash hand-off in place"
    cp "$W/c12.c" "$W/c12.d"
fi
python3 "$ROOT/scripts/patch_splash_visible.py" "$W/c12.d" "$W/c12.e"
# Let ActivityThread instantiate the class it actually asked for.  Off by default:
# rewriting newActivity in the dex makes the child fail to load (exit 123), so the
# adapter undoes the hack Instrumentation at runtime instead (see WL-INSTR).
if [ "${INSTR_DEX_PATCH:-0}" = "1" ]; then
    python3 "$ROOT/scripts/patch_splash_instrumentation.py" "$W/c12.e" "$W/c12.final"
else
    cp "$W/c12.e" "$W/c12.final"
fi

echo "== classes14: accessibility gate on the inflate path =="
# WeChat's LayoutInflater hook calls AccProviderFactory.onInflateRootAsync ->
# AccUtil.isAccessibilityEnabled(), which synchronously drives the plugin lifecycle
# transit and parks the main thread on a ForkJoin task -- the task in turn waits on a
# WCDB CSO native load that frequently never finishes here.  The window decor then
# never inflates.  There is no accessibility service on this board, so answer false
# and keep the inflate path off the plugin framework.
python3 - "$SRC" "$W" <<'PY2'
import sys, zipfile
src, w = sys.argv[1], sys.argv[2]
open(f"{w}/classes14.dex", 'wb').write(zipfile.ZipFile(src).read('classes14.dex'))
PY2
python3 "$ROOT/scripts/patch_retconst.py" "$W/classes14.dex" "$W/c14.final" \
    'Lcom/tencent/mm/accessibility/uitl/AccUtil;->isAccessibilityEnabled()Z=0' \
    'Lcom/tencent/mm/accessibility/uitl/AccUtil;->canPreDeal()Z=0'

echo "== classes16: Cronet network-change autodetect + library init =="
# Cronet cannot come up on this board at all.  Its ALooper_* imports do not exist in
# OHOS's libandroid.so (only 6 of the NDK entry points are implemented and none of
# ALooper), and android.net.ConnectivityManager$OnNetworkActiveListener is missing from
# the adapter's framework.jar, so Chromium's init runs into its own CHECK and the
# CronetInit thread takes the process down with SIGTRAP.  Stubbing the ALooper PLT
# entries only moved the failure further in.  The library still has to *load* -- WeChat's
# own loader throws UnsatisfiedLinkError out of the plugin transit if the dlopen fails,
# which kills MobileInputUI -- so leave the .so alone and neutralise the Java entry
# points that would initialise it.
python3 "$DEXPATCH" "$W/classes16.dex" "$W/c16.a" \
    'Lorg/chromium/net/NetworkChangeNotifierAutoDetect;->register()V' \
    'Lorg/chromium/net/impl/CronetLibraryLoader;->ensureInitialized(Landroid/content/Context;Lorg/chromium/net/impl/CronetEngineBuilderImpl;)V' \
    'Lorg/chromium/net/impl/CronetLibraryLoader;->ensureInitializedOnInitThread()V' \
    'Lorg/chromium/net/impl/CronetLibraryLoader;->ensureInitializedFromNative()V'
# ensureInitializedOnInitThread is what opens CronetLibraryLoader's sWaitForLibLoad
# ConditionVariable.  With it neutralised nothing ever opens it, and the *main* thread
# deadlocks: loading a mars library runs its JNI_OnLoad, which calls back into
# getBaseFeatureOverrides(), which blocks on that variable forever -- MobileInputUI then
# sits with a decor view and contentChildren=0.  Answer without blocking instead --
# with an *empty* array, not null: the native side treats the result as a real array and
# CHECKs on it, so null just moves the failure to a SIGTRAP on a plugin thread.
python3 "$ROOT/scripts/patch_ret_empty_bytes.py" "$W/c16.a" "$W/c16.final" \
    'Lorg/chromium/net/impl/CronetLibraryLoader;->getBaseFeatureOverrides()[B'

python3 - "$SRC" "$W/dexed.apk" "$W/c12.final" "$W/c16.final" "$W/c14.final" <<'PY'
import sys, zipfile, os
src, out, c12, c16, c14 = sys.argv[1:6]
patched = {'classes12.dex': c12, 'classes16.dex': c16, 'classes14.dex': c14}
zin = zipfile.ZipFile(src, 'r'); zout = zipfile.ZipFile(out, 'w')
for it in zin.infolist():
    data = open(patched[it.filename], 'rb').read() if it.filename in patched else zin.read(it.filename)
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = it.compress_type
    zi.external_attr = it.external_attr
    zi.internal_attr = it.internal_attr
    zout.writestr(zi, data)
zout.close(); zin.close()
print(f"wrote {out} ({os.path.getsize(out)} bytes)")
PY

echo "== native: bionic-only pthread cleanup symbols across every arm64 library =="
# WeChat re-extracts app_recovery_lib from the APK on every launch, so the archived
# copies are the ones that matter.  Sweep them all: libwechatxlog pulls in
# libmarscomm, and any other bionic-built library importing these dies the same way.
python3 "$ROOT/scripts/patch_apk_natives.py" "$W/dexed.apk" "$W/natives.apk"

echo "== native: NDK entry points OHOS's libandroid.so does not implement =="
python3 "$ROOT/scripts/weaken_apk_ndk.py" "$W/natives.apk" "$OUT" \
    "$ROOT/refs/ld-musl.so" "$ROOT/refs/libandroid.so" "$ROOT/refs/libbionic_compat.so"
