#!/bin/sh
# wl_screens.sh -- capture the per-channel screen matrix, and answer one
# question about the detail page that the app-side logs cannot.
#
# Runs ON the board.  Push it with `hdc file send`, then:
#   hdc shell "sh /data/local/tmp/wl_screens.sh"
#
# Screens: 推荐频道流 / 热榜频道流 / 视频频道流 / 搜索主界面 / 详情页(尝试).
#
# The detail-page probe
# ---------------------
# Clicking a feed card runs the app's listener (proven: the pump reports
# "handled by FeedItemRootLinerLayout") but no Activity ever appears in
# ActivityThread.mActivities.  Two very different explanations:
#
#   a) the app calls startActivity and the adapter drops it, or
#   b) the app never calls startActivity at all.
#
# ActivityManagerAdapter.startActivity logs
#   Log.d("OH_AMAdapter", "[BRIDGED] startActivity -> OH IAbilityManager.StartAbility")
# before bridging to OH's StartAbility -- and that goes to hilog, not to the
# child's stderr, which is why grepping the app log said nothing either way.
# So capture hilog across the click.  Needs debug level:  hilog -b DEBUG
#
# Output: /data/local/tmp/SC_<name>.jpeg + a one-line summary per screen.

C=/data/local/tmp/wl_input.cmd
D=/data/service/el1/public/appspawnx
PKG=com.ss.android.article.news
SETTLE=${SETTLE:-30}
WARMUP_MAX=${WARMUP_MAX:-260}
APPUID=20010057

say() { echo "[wl-screens] $*"; }

kill_app() {
    for p in $(ps -ef | grep AppSpawnX | grep -v grep | grep $APPUID \
               | sed 's/  */ /g' | cut -d' ' -f2); do
        kill -9 "$p" 2>/dev/null
    done
}

dismiss_usb_dialog() { uinput -T -c 600 1185 >/dev/null 2>&1; }

shot() {
    power-shell wakeup >/dev/null 2>&1
    snapshot_display -f "/data/local/tmp/SC_$1.jpeg" >/dev/null 2>&1
    echo "$(stat -c %s "/data/local/tmp/SC_$1.jpeg" 2>/dev/null)"
}

screen() {  # screen <name> <pump-command> [settle]
    name=$1; cmd=$2; wait=${3:-$SETTLE}
    mark=$(stat -c %s "$LOG" 2>/dev/null || echo 1)
    echo "$cmd" > $C
    sleep "$wait"
    size=$(shot "$name")
    alive=$(kill -0 "$PID" 2>/dev/null && echo 1 || echo 0)
    say "$name  '$cmd'  size=$size alive=$alive"
    tail -c +$mark "$LOG" 2>/dev/null \
        | grep -o 'WL-INPUT].\{0,100\}' | head -2 | sed 's/^/[wl-screens]   /'
}

kill_app
sleep 3
pkill -f keepawake.sh 2>/dev/null
nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &
power-shell wakeup >/dev/null 2>&1
sleep 1
dismiss_usb_dialog
rm -f /data/local/tmp/SC_*.jpeg
: > $C

hilog -b DEBUG >/dev/null 2>&1
hilog -r >/dev/null 2>&1

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

sleep 12
dismiss_usb_dialog
say "01-feed-recommend size=$(shot 01-feed-recommend)"

# 视频频道先走：热榜留到最后。ViewPager 会顺手创建相邻页，而相邻的
# CategoryBrowserFragment 是 WebView 承载的，onActivityCreated 里一整段
# getSettings().xxx() 会在 getSettings() 为 null 时打死进程（见下方说明），
# 所以先把不受影响的界面都取到手。
screen 02-video-channel "tap 560 213"
screen 03-back-to-feed  "tap 190 213"

# 搜索页：没有可用的界面入口，直接拉起 Activity，再补一条 resume
# （这套环境里没有任何东西会 resume 一个新起的 Activity）。
say "aa start SearchActivity"
aa start -a com.android.bytedance.search.SearchActivity -b $PKG >/dev/null 2>&1
sleep 40
echo "resume" > $C
sleep 6
dismiss_usb_dialog
say "04-search size=$(shot 04-search) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"

# 返回信息流：按键销毁 + 让 OH 把 ability 重新前台化
echo "key 4" > $C
sleep 22
aa start -a $PKG.activity.MainActivity -b $PKG >/dev/null 2>&1
sleep 14
say "05-back-to-feed size=$(shot 05-back-to-feed) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"

# ---- 详情页探针 ----
say "==== detail probe ===="
HL=/data/local/tmp/sc_hilog.txt
rm -f $HL
nohup hilog > $HL 2>&1 &
HLPID=$!
sleep 2
mark=$(stat -c %s "$LOG")
echo "click 400 620" > $C
sleep 35
kill -9 $HLPID 2>/dev/null
say "06-detail size=$(shot 06-detail) alive=$(kill -0 $PID 2>/dev/null && echo 1 || echo 0)"
tail -c +$mark "$LOG" 2>/dev/null \
    | grep -o 'WL-INPUT].\{0,120\}\|WL-ACTS].\{0,90\}' | head -6 | sed 's/^/[wl-screens]   /'
say "hilog OH_AMAdapter lines across the click:"
grep 'OH_AMAdapter' $HL 2>/dev/null | tail -12 | cut -c1-170 | sed 's/^/[wl-screens]   /'
say "startActivity in hilog: $(grep -c 'BRIDGED. startActivity\|StartAbility' $HL 2>/dev/null)"

# 热榜最后取：它是这一轮唯一会打死进程的界面，放最后就不会影响其它格。
screen 07-hotlist "tap 320 213"

say "done; frames in /data/local/tmp/SC_*.jpeg"
ls -l /data/local/tmp/SC_*.jpeg 2>/dev/null | sed 's/^/[wl-screens] /'
