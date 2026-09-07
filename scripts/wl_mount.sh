#!/bin/sh
# wl_mount.sh -- prove the single-container navigation loop on the board.
#
#   推荐流 -> 挂载一页 -> 物理返回 -> 回到推荐流
#
# Clicking a feed card never produces a second Activity here (hilog says the
# app does not even call startActivity), so the destination is mounted into the
# host Activity's own android.R.id.content instead.  BACK then has to mean
# "pop the mounted page" while one is up, and "finish the activity" when none
# is -- injectKey() asks unmountFragment() first.
#
# What gets mounted: this script does not guess a class name.  It asks the
# running app what it already has (`frags`) and mounts a *second instance of a
# class the app itself is using*, which is the honest way to exercise the
# mechanism without depending on arguments we cannot synthesise.  The article
# detail fragment is attempted separately and its failure is logged verbatim.
#
# Runs ON the board:
#   hdc shell "sh /data/local/tmp/wl_mount.sh"
#
# Output: /data/local/tmp/MT_<step>.jpeg + a summary per step.

C=/data/local/tmp/wl_input.cmd
D=/data/service/el1/public/appspawnx
PKG=com.ss.android.article.news
APPUID=20010057
WARMUP_MAX=${WARMUP_MAX:-260}
DETAIL=${DETAIL:-com.ss.android.detail.feature.detail2.article.NewArticleDetailFragment}

say() { echo "[wl-mount] $*"; }

kill_app() {
    for p in $(ps -ef | grep AppSpawnX | grep -v grep | grep $APPUID \
               | sed 's/  */ /g' | cut -d' ' -f2); do
        kill -9 "$p" 2>/dev/null
    done
}

dismiss_usb_dialog() { uinput -T -c 600 1185 >/dev/null 2>&1; }

shot() {
    power-shell wakeup >/dev/null 2>&1
    snapshot_display -f "/data/local/tmp/MT_$1.jpeg" >/dev/null 2>&1
    echo "$(stat -c %s "/data/local/tmp/MT_$1.jpeg" 2>/dev/null)"
}

# send <cmd> <settle> -- run a pump command and echo back what it logged
send() {
    mark=$(stat -c %s "$LOG" 2>/dev/null || echo 1)
    echo "$1" > $C
    sleep "${2:-8}"
    tail -c +$mark "$LOG" 2>/dev/null \
        | grep -o 'WL-MOUNT].\{0,120\}\|WL-BACK].\{0,110\}\|WL-INPUT].\{0,110\}\|WL-ACTS].\{0,90\}' \
        | head -20 | sed 's/^/[wl-mount]   /'
}

kill_app
sleep 3
pkill -f keepawake.sh 2>/dev/null
nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &
power-shell wakeup >/dev/null 2>&1
sleep 1
dismiss_usb_dialog
rm -f /data/local/tmp/MT_*.jpeg
: > $C

say "cold start MainActivity"
aa start -a $PKG.activity.MainActivity -b $PKG >/dev/null 2>&1

PID=""
n=0
while [ $n -lt 16 ]; do
    sleep 5; n=$((n+1))
    PID=$(ps -ef | grep AppSpawnX | grep -v grep | grep $APPUID \
          | sed 's/  */ /g' | cut -d' ' -f2 | head -1)
    [ -n "$PID" ] && break
done
[ -z "$PID" ] && { say "FAIL: app never spawned"; exit 1; }
LOG=$D/adapter_child_$PID.stderr
say "pid=$PID log=$LOG"

waited=0
while [ $waited -lt $WARMUP_MAX ]; do
    sleep 10; waited=$((waited + 10))
    s=$(shot probe)
    kill -0 "$PID" 2>/dev/null || { say "FAIL: died during startup at ${waited}s"; exit 1; }
    say "warmup ${waited}s size=$s"
    [ -n "$s" ] && [ "$s" -gt 100000 ] && break
done
[ "$s" -gt 100000 ] || { say "FAIL: no first frame within ${WARMUP_MAX}s"; exit 1; }

sleep 10
dismiss_usb_dialog
say "01-feed size=$(shot 01-feed)"

# ---- what does the host already have? ----
say "==== frags ===="
FMARK=$(stat -c %s "$LOG")
echo "frags" > $C
sleep 8
tail -c +$FMARK "$LOG" | grep -o 'WL-MOUNT].\{0,120\}' | head -25 | sed 's/^/[wl-mount]   /'

# Pick a fragment class the app itself is running.  Prefer one already attached
# to a container, and skip our own mounts.
CAND=${CAND_OVERRIDE:-$(tail -c +$FMARK "$LOG" \
       | sed -n 's/.*\[WL-MOUNT\]   \([a-zA-Z0-9_.$]*Fragment[a-zA-Z0-9_.$]*\) .*/\1/p' \
       | grep -v '\$' | head -1)}
say "candidate from live fragments: ${CAND:-<none>}"

# ---- the real target ----
# Its failure is on the record, but it poisons the FragmentManager: the failed
# fragment stays pending and its exception resurfaces inside the *next*
# transaction's executePendingTransactions.  So SKIP_DETAIL=1 to get a clean
# read on the mechanism itself.
if [ "${SKIP_DETAIL:-0}" != "1" ]; then
    say "==== mount article detail fragment ===="
    send "mount $DETAIL group_id=0 item_id=0 aggr_type=0 detail_source=click_headline" 20
    say "02-detail-attempt size=$(shot 02-detail-attempt) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"
fi

# ---- mechanism proof with a class the app is already using ----
if [ -n "$CAND" ]; then
    say "==== mount live app fragment: $CAND ===="
    send "mount $CAND" 20
    say "03-mounted size=$(shot 03-mounted) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"

    say "==== physical back ===="
    send "key 4" 20
    say "04-after-back size=$(shot 04-after-back) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"

    say "==== back stack after pop ===="
    send "frags" 8
else
    say "SKIP: no live fragment class to mount"
fi

say "done; frames in /data/local/tmp/MT_*.jpeg"
ls -l /data/local/tmp/MT_*.jpeg 2>/dev/null | sed 's/^/[wl-mount] /'
