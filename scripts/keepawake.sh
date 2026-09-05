#!/bin/sh
# The board blanks the screen ~40s after the last input and then raises the
# keyguard, which occludes the app window mid-capture.  `power-shell timeout -o`
# does not stick on this build, so just re-assert AWAKE on a timer.
# Run detached:  nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &
while true; do
    power-shell wakeup >/dev/null 2>&1
    sleep 20
done
