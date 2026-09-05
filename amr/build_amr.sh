#!/usr/bin/env bash
# Build oh-adapter-runtime.jar with our ActivityManagerRouting patched in.
#
# The adapter injects IActivityManager reflectively from oh-adapter-runtime.jar
# (AppSpawnXInit.installActivityManagerStub -> Class.forName("adapter.activity.
# ActivityManagerAdapter")).  That jar is NOT on the boot classpath, so we can
# replace the implementation without touching the boot image: the dex string
# "adapter.activity.ActivityManagerAdapter" was rewritten in place to
# "adapter.activity.ActivityManagerRouting" (same 39 bytes, and it still sorts
# between its neighbours, so the dex string table stays ordered).
#
# That pre-rewritten dex is classes-retarget.dex, shipped next to this script.
#
# Toolchain -- override any of these, no absolute paths are baked in:
#   JAVA_HOME             a JDK 11+ (needs javac and jar)
#   D8                    path to d8            (default: `which d8`)
#   OUT                   output jar            (default: build/oh-adapter-runtime.jar)
#   SRC                   routing class source  (default: src/.../ActivityManagerRouting.java)
#                         .all.java = first frame + TLS gateway + ALooper + input pump
#   BASE_JAR              jar whose classes.dex we replace
#                         (default: prebuilt/oh-adapter-runtime.base.jar)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$HERE/build"
# Intermediates live in their own subdir: OUT usually sits in build/ too, and
# wiping the whole thing would delete the jar a previous variant just produced.
OBJ="$BUILD/obj"
OUT="${OUT:-$BUILD/oh-adapter-runtime.jar}"
SRC="${SRC:-$HERE/src/adapter/activity/ActivityManagerRouting.java}"
BASE_JAR="${BASE_JAR:-$HERE/prebuilt/oh-adapter-runtime.base.jar}"
RETARGET_DEX="${RETARGET_DEX:-$HERE/prebuilt/classes-retarget.dex}"

if [ -n "${JAVA_HOME:-}" ]; then
    JAVAC="$JAVA_HOME/bin/javac"; JAR="$JAVA_HOME/bin/jar"
else
    JAVAC="$(command -v javac)"; JAR="$(command -v jar)"
fi
D8="${D8:-$(command -v d8 || true)}"

for t in "$JAVAC" "$JAR" "$D8"; do
    [ -x "$t" ] || { echo "missing tool: ${t:-<unset>}  (set JAVA_HOME / D8)" >&2; exit 1; }
done
for f in "$BASE_JAR" "$RETARGET_DEX" "$SRC"; do
    [ -f "$f" ] || { echo "missing $f -- run scripts/fetch_prebuilts.sh" >&2; exit 1; }
done

rm -rf "$OBJ"; mkdir -p "$OBJ/stubs" "$OBJ/src" "$OBJ/gen/adapter/activity"

# javac wants the file name to match the public class, so the variants
# (.tls.java, .all.java) have to be staged under the canonical name first.
GEN="$OBJ/gen/adapter/activity/ActivityManagerRouting.java"
cp "$SRC" "$GEN"

echo "[1/4] compile stubs"
# Hand written, compile-only stand-ins for the adapter/framework types we touch.
# We never ship them: d8 only sees the routing class, they just satisfy javac.
find "$HERE/stubs" -name '*.java' -print0 | xargs -0 "$JAVAC" --release 11 -nowarn -d "$OBJ/stubs"

echo "[2/4] compile $(basename "$SRC") + shipped src/"
# Everything under src/ ships, not just the routing class: src/android/net/ssl/
# SSLSockets.java is a stand-in for a class this adapter's framework.jar lacks.
# The ActivityManagerRouting.<variant>.java files are alternatives to each other,
# so they are excluded here -- $GEN is whichever one SRC selected.
SRCS=("$GEN")
while IFS= read -r j; do
    case "$(basename "$j")" in
        ActivityManagerRouting.java|ActivityManagerRouting.*.java) continue ;;
    esac
    SRCS+=("$j")
done < <(find "$HERE/src" -name '*.java')
"$JAVAC" --release 11 -nowarn -encoding UTF-8 \
    -cp "$OBJ/stubs" -d "$OBJ/src" \
    "${SRCS[@]}"

echo "[3/4] d8 merge with classes-retarget.dex"
( cd "$OBJ" && "$JAR" cf stubs.jar -C stubs . )
# No mapfile: macOS still ships bash 3.2.  Anonymous inner classes mean the
# file list is not a fixed size, so collect it portably.
CLASSES=()
while IFS= read -r c; do CLASSES+=("$c"); done < <(find "$OBJ/src" -name '*.class')
[ "${#CLASSES[@]}" -gt 0 ] || { echo "no classes compiled" >&2; exit 1; }
"$D8" --release --min-api 30 \
    --classpath "$OBJ/stubs.jar" --lib "$OBJ/stubs.jar" \
    --output "$OBJ" \
    "${CLASSES[@]}" "$RETARGET_DEX"

echo "[4/4] repack jar"
mkdir -p "$(dirname "$OUT")"
python3 - "$BASE_JAR" "$OBJ/classes.dex" "$OUT" <<'PY'
import sys, zipfile, os
base, dex, out = sys.argv[1:4]
new = open(dex, 'rb').read()
zin = zipfile.ZipFile(base)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for zi in zin.infolist():
        z.writestr(zi, new if zi.filename == 'classes.dex' else zin.read(zi.filename))
print(f"  {out}  {os.path.getsize(out)} bytes  (classes.dex {len(new)})")
PY

echo
echo "built: $OUT"
echo "deploy with: scripts/deploy_and_run.sh --jar $OUT"
