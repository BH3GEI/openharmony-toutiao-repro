#!/bin/sh
# wl_detail.sh -- try to put the article detail page on screen.
#
# Two facts narrowed this down:
#   * clicking a feed card never calls startActivity (hilog debug capture:
#     "startActivity in hilog: 0"), so the in-app route does not fire at all;
#   * NewArticleDetailFragment casts getActivity() to X.DQt, and the only class
#     implementing X.DQt is com.ss.android.detail.feature.detail2.view.
#     NewDetailActivity -- so the fragment cannot be hosted by MainActivity.
#
# Which leaves the route that already works for SearchActivity: ask OH to start
# the detail Activity itself.  That was tried long ago and produced nothing, but
# every one of the reasons it produced nothing has since been fixed --
# Theme AXML back-fill, WebView guard, InertWebSettings, preCreateWebView,
# emoticon.conf, TrafficStats, AudioSystem -- and there is now a `resume`
# command for the "started but nobody resumed it" state that left the last
# attempt on a blank window.
#
#   hdc shell "sh /data/local/tmp/wl_detail.sh"
#
# Output: /data/local/tmp/DT_<step>.jpeg + a summary per step.

C=/data/local/tmp/wl_input.cmd
D=/data/service/el1/public/appspawnx
PKG=com.ss.android.article.news
DETAIL_ACT=${DETAIL_ACT:-com.ss.android.detail.feature.detail2.view.NewDetailActivity}
APPUID=20010057
WARMUP_MAX=${WARMUP_MAX:-260}

say() { echo "[wl-detail] $*"; }

kill_app() {
    for p in $(ps -ef | grep AppSpawnX | grep -v grep | grep $APPUID \
               | sed 's/  */ /g' | cut -d' ' -f2); do
        kill -9 "$p" 2>/dev/null
    done
}

dismiss_usb_dialog() { uinput -T -c 600 1185 >/dev/null 2>&1; }

shot() {
    power-shell wakeup >/dev/null 2>&1
    snapshot_display -f "/data/local/tmp/DT_$1.jpeg" >/dev/null 2>&1
    echo "$(stat -c %s "/data/local/tmp/DT_$1.jpeg" 2>/dev/null)"
}

send() {
    mark=$(stat -c %s "$LOG" 2>/dev/null || echo 1)
    echo "$1" > $C
    sleep "${2:-8}"
    tail -c +$mark "$LOG" 2>/dev/null \
        | grep -o 'WL-MOUNT].\{0,130\}\|WL-BACK].\{0,110\}\|WL-INPUT].\{0,110\}\|WL-ACTS].\{0,110\}\|WL-THEME].\{0,90\}' \
        | head -20 | sed 's/^/[wl-detail]   /'
}

kill_app
sleep 3
pkill -f keepawake.sh 2>/dev/null
nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &
power-shell wakeup >/dev/null 2>&1
sleep 1
dismiss_usb_dialog
rm -f /data/local/tmp/DT_*.jpeg
: > $C
hilog -b DEBUG >/dev/null 2>&1

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

say "==== aa start $DETAIL_ACT ===="
MARK=$(stat -c %s "$LOG")
aa start -a $DETAIL_ACT -b $PKG 2>&1 | sed 's/^/[wl-detail]   aa: /'
sleep 40
tail -c +$MARK "$LOG" \
    | grep -o 'WL-THEME].\{0,100\}\|WL-WIN] addToDisplay.\{0,50\}\|Unable to.\{0,110\}\|ClassCast.\{0,110\}' \
    | head -10 | sed 's/^/[wl-detail]   /'
say "02-detail-started size=$(shot 02-detail-started) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"
send "acts" 8

# Nothing in this stack resumes a freshly started Activity, so it can sit at
# paused=true stopped=true behind a blank window.  Ask the pump to resume
# whatever owns the top window.
say "==== resume ===="
send "resume" 12
dismiss_usb_dialog
say "03-detail-resumed size=$(shot 03-detail-resumed) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"
send "acts" 8
send "frags" 8

say "==== physical back ===="
send "key 4" 22
aa start -a $PKG.activity.MainActivity -b $PKG >/dev/null 2>&1
sleep 12
say "04-back-to-feed size=$(shot 04-back-to-feed) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"
send "acts" 8

say "done; frames in /data/local/tmp/DT_*.jpeg"
ls -l /data/local/tmp/DT_*.jpeg 2>/dev/null | sed 's/^/[wl-detail] /'
