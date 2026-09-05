#!/usr/bin/env bash
# wl_input.sh -- drive the app's UI through the wl-input-pump.
#
#   scripts/wl_input.sh tap 400 250
#   scripts/wl_input.sh swipe 600 1400 600 500 400
#   scripts/wl_input.sh key 4                    # KEYCODE_BACK
#   scripts/wl_input.sh dump                     # in-process view tree to stderr
#
# OH multimodal input never delivers pointer events to the app -- the adapter
# only implements the consumer half of its own input path (the full teardown is
# in docs/INPUT_PATH_ANALYSIS.md).  ActivityManagerRouting runs a daemon thread
# that reads this command file and synthesises MotionEvents straight into the
# window's WindowInputEventReceiver, which is where liboh_android_runtime's
# OH_InputMotionWorker would have handed them over.
#
# Requires an adapter jar built with the pump:
#   JAVA_HOME=... SRC=amr/src/adapter/activity/ActivityManagerRouting.all.java \
#       OUT=amr/build/oh-adapter-runtime.all.jar amr/build_amr.sh
#   scripts/deploy_and_run.sh --all
#
# Also needs libwlveltrack.so in the app's native lib dir (native/veltrack/),
# or the first touch on any scrolling container throws UnsatisfiedLinkError.
#
# Env:
#   HDC        hdc binary or wrapper      (default: hdc)
#   HDC_TARGET board serial -> `hdc -t …` (default: unset, single board)
set -euo pipefail

HDC="${HDC:-hdc}"
CMD_FILE=/data/local/tmp/wl_input.cmd

[ $# -ge 1 ] || { sed -n '2,10p' "$0"; exit 2; }

hdc() { if [ -n "${HDC_TARGET:-}" ]; then "$HDC" -t "$HDC_TARGET" "$@"; else "$HDC" "$@"; fi; }

case "$1" in
    tap)   [ $# -eq 3 ] || { echo "usage: $0 tap <x> <y>" >&2; exit 2; } ;;
    swipe) [ $# -eq 5 ] || [ $# -eq 6 ] || { echo "usage: $0 swipe <x1> <y1> <x2> <y2> [ms]" >&2; exit 2; } ;;
    key)   [ $# -eq 2 ] || { echo "usage: $0 key <keycode>" >&2; exit 2; } ;;
    dump)  [ $# -eq 1 ] || { echo "usage: $0 dump" >&2; exit 2; } ;;
    *)     echo "unknown command: $1 (tap|swipe|key|dump)" >&2; exit 2 ;;
esac

# The pump deletes the file once it has read it, so one write is one gesture.
hdc shell "echo '$*' > $CMD_FILE"
echo "sent: $*"

# Give the pump a poll cycle plus dispatch, then show what it did.  Everything
# the pump prints goes to the app's stderr, which run_capture.sh tees.
sleep 1
echo "(look for [WL-INPUT] in the adapter child stderr log)"
