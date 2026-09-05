#!/bin/sh
# input_matrix.sh -- cold-start MainActivity, wait for the first frame, then walk
# the UI through wl-input-pump, capturing a frame after every gesture.
#
# Runs ON the board (pushed by the caller).  Needs an adapter jar built from
# ActivityManagerRouting.all.java -- see docs/INPUT_PATH_ANALYSIS.md for why
# uinput cannot do this.
PKG=com.ss.android.article.news
ACT=$PKG.activity.MainActivity
CMD=/data/local/tmp/wl_input.cmd
OUT=/data/local/tmp

# Startup runs in the ART interpreter, so the first frame lands around t=80-110s.
WARMUP=${WARMUP:-115}
# Let the tapped screen actually build before snapshotting -- also interpreted.
SETTLE=${SETTLE:-12}

for p in $(ps -ef | grep AppSpawnX | grep -v grep | grep 20010057 | sed 's/  */ /g' | cut -d' ' -f2); do
    kill -9 "$p" 2>/dev/null
done
sleep 3

pkill -f keepawake.sh 2>/dev/null
nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &

power-shell wakeup >/dev/null 2>&1
sleep 1
uinput -T -m 600 1600 600 500 400 >/dev/null 2>&1
sleep 3

rm -f "$OUT"/M_*.jpeg "$CMD"
aa start -a "$ACT" -b "$PKG" >/dev/null 2>&1

echo "waiting ${WARMUP}s for the first frame"
sleep "$WARMUP"
power-shell wakeup >/dev/null 2>&1
snapshot_display -f "$OUT/M_00-baseline.jpeg" >/dev/null 2>&1
echo "baseline alive=$(ps -ef | grep AppSpawnX | grep -v grep | grep -c 20010057) size=$(stat -c %s "$OUT/M_00-baseline.jpeg" 2>/dev/null)"

shot() {   # shot <name> <command...>
    name=$1; shift
    echo "$*" > "$CMD"
    sleep "$SETTLE"
    power-shell wakeup >/dev/null 2>&1
    snapshot_display -f "$OUT/M_$name.jpeg" >/dev/null 2>&1
    echo "$name  <- '$*'  alive=$(ps -ef | grep AppSpawnX | grep -v grep | grep -c 20010057) size=$(stat -c %s "$OUT/M_$name.jpeg" 2>/dev/null)"
}

# Coordinates are the measured ones for this 1200x1920 panel: the channel row
# sits at y=213 and the bottom bar at y=1855, not the nominal 250/1850.
shot 01-tab-video    tap 560 213
shot 02-tab-hot      tap 320 213
shot 03-nav-mine     tap 1050 1855
shot 04-nav-video    tap 450 1855
shot 05-nav-home     tap 150 1855
shot 06-scroll-feed  swipe 600 1400 600 700 400
shot 07-search-box   tap 400 112
shot 08-back         key 4

echo "done"
