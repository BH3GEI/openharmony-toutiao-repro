#!/bin/sh
# wl_matrix.sh -- one automated pass over the app's main screens, capturing each.
#
# Runs ON the board.  Push it with `hdc file send`, then:
#   hdc shell "sh /data/local/tmp/wl_matrix.sh"
#
# The loop is: 推荐流 -> 视频频道 -> 搜索页 -> 个人中心 -> 回到推荐流.
# Everything but the search page is driven through wl-input-pump; the search page
# has no reachable entry point (tapping the search bar starts nothing -- see
# docs/INPUT_PATH_ANALYSIS.md), so it is launched with `aa start` instead.
#
# Output: /data/local/tmp/WM_<step>.jpeg, plus a one-line summary per step.
#
# Requires on the board:
#   - adapter jar built from ActivityManagerRouting.all.java (input pump, window
#     ranking, VelocityTracker loader, theme back-fill, WebView guard)
#   - base.final10.apk (or newer) as the app's base.apk
#   - libwlveltrack.so in the app's native lib dir
#
# Env: SETTLE (seconds to wait after each gesture, default 30)
#      WARMUP_MAX (max seconds to wait for the first frame, default 260)

C=/data/local/tmp/wl_input.cmd
D=/data/service/el1/public/appspawnx
PKG=com.ss.android.article.news
SETTLE=${SETTLE:-30}
WARMUP_MAX=${WARMUP_MAX:-260}

# Toutiao runs as uid 20010057.  Other apps share this board, so never pick the
# newest log blindly -- pin the pid instead.
APPUID=20010057

say() { echo "[wl-matrix] $*"; }

kill_app() {
    for p in $(ps -ef | grep AppSpawnX | grep -v grep | grep $APPUID \
               | sed 's/  */ /g' | cut -d' ' -f2); do
        kill -9 "$p" 2>/dev/null
    done
}

# The board raises a system "USB connection mode" dialog when hdc attaches, and
# it sits on top of everything.  Dismiss it before capturing anything.
dismiss_usb_dialog() { uinput -T -c 600 1185 >/dev/null 2>&1; }

shot() {   # shot <name>
    power-shell wakeup >/dev/null 2>&1
    snapshot_display -f "/data/local/tmp/WM_$1.jpeg" >/dev/null 2>&1
    echo "$(stat -c %s "/data/local/tmp/WM_$1.jpeg" 2>/dev/null)"
}

step() {   # step <name> <pump-command> <settle>
    name=$1; cmd=$2; wait=${3:-$SETTLE}
    mark=$(stat -c %s "$LOG" 2>/dev/null || echo 1)
    echo "$cmd" > $C
    sleep "$wait"
    size=$(shot "$name")
    alive=$(kill -0 "$PID" 2>/dev/null && echo 1 || echo 0)
    say "$name  '$cmd'  size=$size alive=$alive"
    tail -c +$mark "$LOG" 2>/dev/null \
        | grep -o 'WL-INPUT].\{0,90\}\|WL-WIN] addToDisplay.\{0,40\}' | head -2
}

kill_app
sleep 3
pkill -f keepawake.sh 2>/dev/null
nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &
power-shell wakeup >/dev/null 2>&1
sleep 1
dismiss_usb_dialog
rm -f /data/local/tmp/WM_*.jpeg
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

# Startup runs in the ART interpreter; the first frame lands around 80-110s.
# A frame under ~60 KB is the empty window, over that is the rendered feed.
waited=0
while [ $waited -lt $WARMUP_MAX ]; do
    sleep 10; waited=$((waited + 10))
    s=$(shot probe)
    kill -0 "$PID" 2>/dev/null || { say "FAIL: died during startup at ${waited}s"; exit 1; }
    say "warmup ${waited}s size=$s"
    [ -n "$s" ] && [ "$s" -gt 100000 ] && break
done
[ "$s" -gt 100000 ] || { say "FAIL: no first frame within ${WARMUP_MAX}s"; exit 1; }

sleep 12
say "01-feed size=$(shot 01-feed)"
grep -o 'WL-WEBVIEW] guard.\{0,60\}\|WL-VELTRACK] self-test.\{0,30\}' "$LOG" | head -2

# 推荐流 -> 视频频道
step 02-video-channel "tap 560 213"

# 视频频道 -> 推荐流（搜索页要从信息流拉起，先回来）
step 03-back-to-feed  "tap 190 213"

# 搜索页：没有可用的界面入口，直接拉起 Activity
mark=$(stat -c %s "$LOG")
say "aa start SearchActivity"
aa start -a com.android.bytedance.search.SearchActivity -b $PKG >/dev/null 2>&1
sleep 45
dismiss_usb_dialog
say "04-search size=$(shot 04-search) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0) wlwin=$(tail -c +$mark "$LOG" | grep -c 'WL-WIN')"

# 搜索页 -> 返回
step 05-back-from-search "key 4" 25

# 个人中心
step 06-mine "tap 1050 1870"

# 回到信息流
step 07-back-to-feed "tap 150 1870"

say "done; frames in /data/local/tmp/WM_*.jpeg"
ls -l /data/local/tmp/WM_*.jpeg 2>/dev/null | sed 's/^/[wl-matrix] /'
