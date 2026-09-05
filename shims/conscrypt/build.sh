#!/usr/bin/env bash
# classes22.dex -- a presence-only com.android.org.conscrypt.SSLParametersImpl.
#
# okhttp's Platform detection gates on that class existing:
#   Android10Platform.buildIfSupported() / AndroidPlatform.buildIfSupported()
#   both ClassLoaderHelper.findClass("com.android.org.conscrypt.SSLParametersImpl")
#   and findAndroidPlatform() throws NPE("No platform found on Android") if both
#   return null -- which killed every TTNet DoConnect, because this adapter's
#   boot classpath ships no conscrypt at all.
#
# It is looked up through Mira's ClassLoaderHelper, i.e. the *app* class loader,
# so an extra dex in base.apk is enough -- no boot classpath change.  okhttp only
# uses the Class as a reflection token (readFieldOrNull(..., "sslParameters")),
# which tolerates null.
#
# JAVA_HOME (JDK 11+), D8
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"; D8="${D8:-$(command -v d8)}"
rm -rf "$HERE/build"; mkdir -p "$HERE/build/classes"
"$JAVAC" --release 8 -d "$HERE/build/classes" $(find "$HERE/src" -name '*.java')
"$D8" --release --min-api 30 --output "$HERE/build" $(find "$HERE/build/classes" -name '*.class')
cp "$HERE/build/classes.dex" "$HERE/../../patches/prebuilt/classes22.dex"
ls -l "$HERE/../../patches/prebuilt/classes22.dex"
