#!/usr/bin/env bash
# Download the release artifacts that are too large for git.
#   scripts/fetch_prebuilts.sh [tag]      (default: v1.0-firstframe)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DEST="$ROOT/prebuilts"
TAG="${1:-v1.0-firstframe}"
REPO="${REPO:-BH3GEI/openharmony-toutiao-repro}"

mkdir -p "$DEST"
ASSETS=(base.final6.apk oh-adapter-runtime.jar libwestlake_stackgrow.so
        libwlicu.so libtttext_lite.patched.so)

if command -v gh >/dev/null 2>&1; then
    echo "downloading $TAG from $REPO via gh"
    for a in "${ASSETS[@]}"; do
        [ -f "$DEST/$a" ] && { echo "  have $a"; continue; }
        gh release download "$TAG" -R "$REPO" -p "$a" -D "$DEST" || \
            echo "  !! $a not in the release"
    done
else
    echo "gh not found; falling back to curl"
    for a in "${ASSETS[@]}"; do
        [ -f "$DEST/$a" ] && { echo "  have $a"; continue; }
        curl -fL -o "$DEST/$a" \
          "https://github.com/$REPO/releases/download/$TAG/$a" || echo "  !! $a failed"
    done
fi

echo
if [ -f "$ROOT/prebuilts/SHA256SUMS" ]; then
    ( cd "$DEST" && shasum -a 256 -c SHA256SUMS 2>/dev/null || \
      sha256sum -c SHA256SUMS 2>/dev/null || echo "(no checksum tool)" )
fi
ls -l "$DEST"
