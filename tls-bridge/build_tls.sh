#!/usr/bin/env bash
# Build out/wl-tls.jar -- the TLS stack the adapter is missing -- and the
# out/wl-cacerts.p12 truststore that goes with it.
#
# Contents: upstream BouncyCastle (bcprov + bcutil + bctls) plus the westlake.tls
# glue, dexed into one classes.dex.  Upstream BC lives under org.bouncycastle.*,
# while the copy already on the board's boot classpath is AOSP's
# com.android.org.bouncycastle.*, so the two never collide.
#
#   JAVA_HOME   a JDK 11+   D8   path to d8   BC_VERSION   default 1.78.1
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BC_VERSION="${BC_VERSION:-1.78.1}"
WORK="$ROOT/work/bc"
OUT="$ROOT/out"

if [ -n "${JAVA_HOME:-}" ]; then JAVAC="$JAVA_HOME/bin/javac"; else JAVAC="$(command -v javac)"; fi
D8="${D8:-$(command -v d8 || true)}"
[ -x "$JAVAC" ] || { echo "no javac (set JAVA_HOME)" >&2; exit 1; }
[ -x "$D8" ]    || { echo "no d8 (set D8)" >&2; exit 1; }

mkdir -p "$WORK" "$OUT"

echo "[1/5] fetch BouncyCastle $BC_VERSION"
BASE=https://repo1.maven.org/maven2/org/bouncycastle
for a in bcprov-jdk18on bcutil-jdk18on bctls-jdk18on; do
    f="$WORK/$a-$BC_VERSION.jar"
    [ -f "$f" ] || curl -sSL --max-time 300 -o "$f" "$BASE/$a/$BC_VERSION/$a-$BC_VERSION.jar"
done

echo "[2/5] unpack BC (dropping multi-release + module-info, which d8 rejects)"
rm -rf "$WORK/clean"; mkdir -p "$WORK/clean"
( cd "$WORK/clean"
  for a in bcprov-jdk18on bcutil-jdk18on bctls-jdk18on; do
      unzip -o -q "$WORK/$a-$BC_VERSION.jar" \
          -x 'META-INF/versions/*' 'module-info.class' 'META-INF/*.SF' \
             'META-INF/*.DSA' 'META-INF/*.RSA' 2>/dev/null || true
  done
  rm -f module-info.class )

echo "[3/5] compile westlake.tls"
BCCP="$WORK/bcprov-jdk18on-$BC_VERSION.jar:$WORK/bcutil-jdk18on-$BC_VERSION.jar:$WORK/bctls-jdk18on-$BC_VERSION.jar"
rm -rf "$ROOT/build/classes"; mkdir -p "$ROOT/build/classes"
find "$ROOT/src" -name '*.java' -exec "$JAVAC" --release 8 -nowarn -cp "$BCCP" \
    -d "$ROOT/build/classes" {} +

echo "[4/5] dex"
rm -rf "$ROOT/build/dex"; mkdir -p "$ROOT/build/dex"
find "$WORK/clean" -name '*.class'   >  "$ROOT/build/all.txt"
find "$ROOT/build/classes" -name '*.class' >> "$ROOT/build/all.txt"
echo "      $(wc -l < "$ROOT/build/all.txt") classes"
"$D8" --release --min-api 30 --output "$ROOT/build/dex" \
    ${JAVA_HOME:+--lib "$JAVA_HOME"} "@$ROOT/build/all.txt"

echo "[5/5] package"
rm -f "$OUT/wl-tls.jar"
( cd "$ROOT/build/dex" && zip -q -X "$OUT/wl-tls.jar" classes.dex )

# Bake the board's PEM roots into a PKCS12 the board can load directly: parsing
# 133 PEMs under -Xint is pure waste, and the app uid cannot write a cache itself.
if [ -d "$ROOT/work/cacerts" ]; then
    java -cp "$ROOT/build/classes:$BCCP" westlake.tls.TlsBootstrap \
        bake "$ROOT/work/cacerts" "$OUT/wl-cacerts.p12"
else
    echo "      (no work/cacerts -- skipping truststore bake)"
fi

echo
ls -l "$OUT"
shasum -a 256 "$OUT"/wl-tls.jar "$OUT"/wl-cacerts.p12 2>/dev/null || true
