#!/usr/bin/env bash
# Download the release artifacts that are too large for git, then verify them
# against prebuilts/SHA256SUMS.
#
#   scripts/fetch_prebuilts.sh [tag]        (default tag: v1.0-firstframe)
#
# Env:
#   REPO   owner/name to pull from   (default: BH3GEI/openharmony-toutiao-repro)
#
# Exits non-zero if anything is missing or fails checksum, so it is safe to
# chain:  scripts/fetch_prebuilts.sh && scripts/deploy_and_run.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DEST="$ROOT/prebuilts"
TAG="${1:-v1.0-firstframe}"
REPO="${REPO:-BH3GEI/openharmony-toutiao-repro}"
SUMS="$DEST/SHA256SUMS"

# Keep this list in sync with SHA256SUMS -- the verify step below checks every
# line of that file, so an asset missing here shows up as a hard failure.
ASSETS=(
    base.final6.apk
    base.final7.apk
    oh-adapter-runtime.jar
    oh-adapter-runtime.tls.jar
    libwestlake_stackgrow.so
    libwlicu.so
    libtttext_lite.patched.so
    toutiao_firstframe_success.tar.gz
)

mkdir -p "$DEST"
[ -f "$SUMS" ] || { echo "missing $SUMS -- incomplete checkout?" >&2; exit 1; }

echo "fetching $TAG from $REPO -> $DEST"
fail=0
for a in "${ASSETS[@]}"; do
    if [ -f "$DEST/$a" ]; then
        echo "  have     $a"
        continue
    fi
    echo "  download $a"
    if command -v gh >/dev/null 2>&1; then
        gh release download "$TAG" -R "$REPO" -p "$a" -D "$DEST" >/dev/null 2>&1 || fail=1
    else
        # The repo may be private; a bare curl only works for public releases.
        curl -fL --retry 3 --retry-all-errors -o "$DEST/$a" \
             "https://github.com/$REPO/releases/download/$TAG/$a" >/dev/null 2>&1 || fail=1
    fi
    [ -f "$DEST/$a" ] || { echo "    !! $a not downloaded"; fail=1; }
done

# ---- verify ----------------------------------------------------------------
# Pick one tool and run it exactly once; `shasum -c` / `sha256sum -c` both take
# the same BSD-ish "<hash>  <name>" format that SHA256SUMS uses.
if command -v shasum >/dev/null 2>&1;   then CHECK=(shasum -a 256 -c)
elif command -v sha256sum >/dev/null 2>&1; then CHECK=(sha256sum -c)
else
    echo
    echo "!! no shasum/sha256sum available -- cannot verify integrity" >&2
    exit 1
fi

echo
echo "verifying against $(basename "$SUMS") with ${CHECK[0]}"
if ( cd "$DEST" && "${CHECK[@]}" "$(basename "$SUMS")" ); then
    echo
    echo "all artifacts present and verified."
else
    echo
    echo "!! checksum verification FAILED" >&2
    fail=1
fi

echo
ls -l "$DEST"
exit "$fail"
