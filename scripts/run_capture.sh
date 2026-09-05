#!/bin/sh
# Cold-start MainActivity and capture a frame series into /data/local/tmp/F_<t>.jpeg.
PKG=com.ss.android.article.news
ACT=$PKG.activity.MainActivity

# kill any live app child (the child keeps the parent's argv, so match on uid)
for p in $(ps -ef | grep AppSpawnX | grep -v grep | grep 20010057 | sed 's/  */ /g' | cut -d' ' -f2); do
    kill -9 "$p" 2>/dev/null
done
sleep 3

pkill -f keepawake.sh 2>/dev/null
nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &

power-shell wakeup >/dev/null 2>&1
sleep 1
# swipe up dismisses the keyguard; if already unlocked this is the "go home"
# gesture, which is harmless here because we launch the activity right after.
uinput -T -m 600 1600 600 500 400 >/dev/null 2>&1
sleep 3

rm -f /data/local/tmp/F_*.jpeg
aa start -a "$ACT" -b "$PKG" >/dev/null 2>&1

prev=0
for t in 20 40 60 80 90 100 110 120 140 160 180; do
    sleep $((t - prev)); prev=$t
    power-shell wakeup >/dev/null 2>&1
    snapshot_display -f "/data/local/tmp/F_$t.jpeg" >/dev/null 2>&1
    alive=$(ps -ef | grep AppSpawnX | grep -v grep | grep -c 20010057)
    size=$(stat -c %s "/data/local/tmp/F_$t.jpeg" 2>/dev/null)
    echo "t=${t}s alive=$alive size=$size"
done
