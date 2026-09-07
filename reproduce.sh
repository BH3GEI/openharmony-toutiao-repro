#!/usr/bin/env bash
# =============================================================================
# reproduce.sh — OpenHarmony Android 双引擎兼容运行时 · 一键复现入口
#
# 今日头条 (com.ss.android.article.news)  +  微信 8.0.77 (com.tencent.mm)
#
# 用法:
#   ./reproduce.sh check      # 只做环境/板子连通性检查
#   ./reproduce.sh toutiao    # 部署共享运行时 + 启动头条 + 截图验收
#   ./reproduce.sh wechat     # 部署共享运行时 + 启动微信 + 截图验收
#   ./reproduce.sh all        # 依次跑头条与微信
#
# 设计原则:
#   - 代码是给人看的，只是机器恰好可以运行
#   - 每一步都打印清晰的人话，失败时告诉工程师下一步该做什么
#   - 不依赖本机已安装的 hdc：优先用仓库自带的 ./hdc-remote
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# ---- 可覆盖的环境变量 -------------------------------------------------------
# PREFERRED_SERIAL: 默认板子序列号；多板时优先选它，否则用第一台已连接设备
PREFERRED_SERIAL="${PREFERRED_SERIAL:-5ce1227d00000000000000000923012c}"
HDC_TARGET="${HDC_TARGET:-}"          # 若设置，强制 hdc -t <serial>
WAIT_TOUTIAO_S="${WAIT_TOUTIAO_S:-90}" # 头条冷启动等到首帧的秒数（解释器模式偏慢）
WAIT_WECHAT_S="${WAIT_WECHAT_S:-25}"  # 微信欢迎页通常 ~12s 命中

TOUTIAO_PKG=com.ss.android.article.news
TOUTIAO_ACT=com.ss.android.article.news.activity.MainActivity
TOUTIAO_UID=20010057

WECHAT_PKG=com.tencent.mm
WECHAT_ACT=com.tencent.mm.ui.LauncherUI
WECHAT_UID=20010058

# 产物优先级：本地合并 jar > TLS jar > 首帧 jar；APK 取最新 final*
pick_first_existing() {
  local f
  for f in "$@"; do
    if [ -f "$f" ]; then
      printf '%s' "$f"
      return 0
    fi
  done
  return 1
}

JAR="$(pick_first_existing \
  "$ROOT/prebuilts/oh-adapter-runtime-unified.jar"   "$ROOT/amr/build/oh-adapter-runtime.all.jar" \
  "$ROOT/prebuilts/oh-adapter-runtime.tls.jar" \
  "$ROOT/prebuilts/oh-adapter-runtime.jar" || true)"

APK="$(pick_first_existing \
  "$ROOT/prebuilts/base.final14.apk"   "$ROOT/prebuilts/base.final13.apk"   "$ROOT/prebuilts/base.final12.apk"   "$ROOT/prebuilts/base.final11.apk" \
  "$ROOT/prebuilts/base.final10.apk" \
  "$ROOT/prebuilts/base.final9.apk" \
  "$ROOT/prebuilts/base.final8.apk" \
  "$ROOT/prebuilts/base.final7.apk" \
  "$ROOT/prebuilts/base.final6.apk" || true)"

STACKGROW="$ROOT/prebuilts/libwestlake_stackgrow.so"
ICUSHIM="$ROOT/prebuilts/libwlicu.so"
TTTEXT="$ROOT/prebuilts/libtttext_lite.patched.so"
SQLITE="$ROOT/prebuilts/libwlsqlite.so"

# =============================================================================
# 小工具
# =============================================================================
banner()  { printf '\n\033[1m=== %s ===\033[0m\n' "$*"; }
info()    { printf '  · %s\n' "$*"; }
ok()      { printf '  OK  %s\n' "$*"; }
warn()    { printf '  !!  %s\n' "$*" >&2; }
die()     { printf '\nFATAL: %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '2,18p' "$0"
  exit "${1:-0}"
}

# 解析 hdc 二进制：仓库里的 hdc-remote 优先（跨机 USB 中继），否则系统 hdc
resolve_hdc() {
  if [ -n "${HDC:-}" ]; then
    printf '%s' "$HDC"
    return
  fi
  if [ -x "$ROOT/hdc-remote" ]; then
    printf '%s' "$ROOT/hdc-remote"
    return
  fi
  if command -v hdc >/dev/null 2>&1; then
    command -v hdc
    return
  fi
  die "找不到 hdc。请安装 OpenHarmony hdc，或把 hdc-remote 放到仓库根目录。"
}

HDC_BIN="$(resolve_hdc)"

# 统一包装：自动带 -t <serial>（hdc-remote 自己会丢掉多余的 -t）
hdc() {
  if [ -n "${HDC_TARGET:-}" ]; then
    "$HDC_BIN" -t "$HDC_TARGET" "$@"
  else
    "$HDC_BIN" "$@"
  fi
}

sh_board() { hdc shell "$@"; }

# =============================================================================
# 设备解析（check / toutiao / wechat 共用）
# =============================================================================
ensure_device() {
  local raw serial
  raw="$(hdc list targets 2>/dev/null || true)"
  if [ -z "$raw" ]; then
    die "hdc list targets 无输出。请确认：
  - USB 已连 DAYU200 / 或 hdc-remote 的 SSH 中继 (yao-win) 可达
  - 板子已开机且 hdc 守护进程在跑"
  fi
  if [ -n "${HDC_TARGET:-}" ]; then
    return 0
  fi
  if printf '%s' "$raw" | grep -q "$PREFERRED_SERIAL"; then
    serial="$PREFERRED_SERIAL"
  else
    serial="$(printf '%s\n' "$raw" | tr ' \t' '\n' | grep -E '^[0-9a-fA-F]{8,}$' | head -1 || true)"
  fi
  [ -n "$serial" ] || die "未能解析出设备序列号。原始输出:
$raw"
  HDC_TARGET="$serial"
}

# =============================================================================
# check — 连通性与产物齐全性
# =============================================================================
cmd_check() {
  banner "1/3  hdc 工具"
  info "使用: $HDC_BIN"
  ok "hdc 可执行"

  banner "2/3  板子连接"
  ensure_device
  hdc list targets 2>/dev/null | sed 's/^/    /' || true
  ok "选用设备: $HDC_TARGET"

  if ! sh_board "echo hdc-ok" 2>/dev/null | grep -q hdc-ok; then
    die "无法在设备上执行 shell。检查 hdc 权限 / USB 调试 / 中继。"
  fi
  ok "shell 冒烟通过"

  banner "3/3  本地产物"
  local missing=0
  for label_path in \
    "adapter-jar:$JAR" \
    "stackgrow:$STACKGROW" \
    "icu-shim:$ICUSHIM" \
    "tttext:$TTTEXT"
  do
    local label="${label_path%%:*}" path="${label_path#*:}"
    if [ -n "$path" ] && [ -f "$path" ]; then
      ok "$label = $(basename "$path")"
    else
      warn "$label 缺失"
      missing=1
    fi
  done
  if [ -n "${APK:-}" ] && [ -f "$APK" ]; then
    ok "toutiao-apk = $(basename "$APK")"
  else
    warn "头条 APK 不在 prebuilts/（可先跑 scripts/fetch_prebuilts.sh）"
  fi

  if sh_board "ls /data/app/el1/bundle/public/$WECHAT_PKG/android/base.apk" >/dev/null 2>&1; then
    ok "板端已有微信 base.apk"
  else
    warn "板端未见微信安装槽（wechat 子命令会给出手工部署指引）"
  fi

  if [ "$missing" = 1 ]; then
    warn "部分共享产物缺失。头条完整复现请先: scripts/fetch_prebuilts.sh"
  fi

  cat <<EOF

检查完成。下一步:
  ./reproduce.sh toutiao   # 信息流 + 视频频道
  ./reproduce.sh wechat    # 欢迎/登录页
  ./reproduce.sh all       # 两个都跑
EOF
}

# =============================================================================
# 部署共享运行时（jar + native shim）—— 头条与微信共用
# =============================================================================
deploy_shared_runtime() {
  banner "部署共享适配层运行时"

  [ -n "${JAR:-}" ] && [ -f "$JAR" ] || die "缺少 oh-adapter-runtime*.jar
请准备 amr/build/oh-adapter-runtime.all.jar 或 prebuilts/oh-adapter-runtime*.jar"

  info "jar → $(basename "$JAR")"
  hdc file send "$JAR" /data/local/tmp/oh-adapter-runtime.jar
  # 写 portable 源，避免 runtime-recover 把改动冲掉
  sh_board "T=/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar;
            [ -f \$T.orig ] || cp \$T \$T.orig;
            cp \$T \$T.bak 2>/dev/null || true;
            cp /data/local/tmp/oh-adapter-runtime.jar \$T && chmod 644 \$T;
            chcon u:object_r:system_file:s0 \$T 2>/dev/null || true;
            md5sum \$T 2>/dev/null | head -1"
  ok "adapter jar 已就位"

  if [ -f "$STACKGROW" ]; then
    info "libwestlake_stackgrow.so"
    hdc file send "$STACKGROW" /data/local/tmp/libwestlake_stackgrow.so
    sh_board "for T in /system/android/lib64 /data/pr03-74e6-portable/android/lib64; do
                [ -d \$T ] || continue
                cp /data/local/tmp/libwestlake_stackgrow.so \$T/
                chmod 644 \$T/libwestlake_stackgrow.so
                chcon u:object_r:system_file:s0 \$T/libwestlake_stackgrow.so 2>/dev/null || true
              done"
    ok "stackgrow"
  fi

  if [ -n "${SQLITE:-}" ] && [ -f "$SQLITE" ]; then
    info "libwlsqlite.so"
    hdc file send "$SQLITE" /data/local/tmp/libwlsqlite.so
    sh_board "for T in /data/app/el1/bundle/public/com.tencent.mm/android/lib/arm64-v8a /data/app/el1/bundle/public/com.ss.android.article.news/android/lib/arm64-v8a; do
                [ -d \$T ] || continue
                cp /data/local/tmp/libwlsqlite.so \$T/
                chmod 755 \$T/libwlsqlite.so
                chcon u:object_r:data_app_el1_file:s0 \$T/libwlsqlite.so 2>/dev/null || true
              done; rm -f /data/local/tmp/libwlsqlite.so"
    ok "libwlsqlite.so"
  fi

  # TLS 载荷（若仓库里有就推；没有也不致命——旧板端可能已有）
  local tls_jar="$ROOT/tls-bridge/prebuilt/wl-tls.jar"
  local tls_ca="$ROOT/tls-bridge/prebuilt/wl-cacerts.p12"
  if [ -f "$tls_jar" ] && [ -f "$tls_ca" ]; then
    info "TLS bridge payload"
    hdc file send "$tls_jar" /data/local/tmp/wl-tls.jar
    hdc file send "$tls_ca"  /data/local/tmp/wl-cacerts.p12
    ok "wl-tls.jar + wl-cacerts.p12"
  else
    warn "跳过 TLS payload（tls-bridge/prebuilt/ 不完整）"
  fi
}

# 部署头条 apk + ICU / tttext 私有 so
deploy_toutiao_app() {
  banner "部署今日头条 APK / native"
  [ -n "${APK:-}" ] && [ -f "$APK" ] || die "缺少头条 APK。请先: scripts/fetch_prebuilts.sh"

  local bundle="/data/app/el1/bundle/public/$TOUTIAO_PKG"
  local libdir="$bundle/android/lib/arm64-v8a"
  local libdir2="/data/app/el2/100/base/$TOUTIAO_PKG/app_lib"

  hdc file send "$APK" /data/local/tmp/base.toutiao.apk
  sh_board "B=$bundle/android/base.apk;
            cp /data/local/tmp/base.toutiao.apk \$B &&
            chown installs:installs \$B && chmod 644 \$B &&
            chcon u:object_r:data_app_el1_file:s0 \$B 2>/dev/null || true &&
            rm -f /data/local/tmp/base.toutiao.apk && echo apk-ok"

  if [ -f "$ICUSHIM" ] && [ -f "$TTTEXT" ]; then
    hdc file send "$ICUSHIM" /data/local/tmp/libwlicu.so
    hdc file send "$TTTEXT"  /data/local/tmp/libtttext_lite.so
    sh_board "for D in $libdir $libdir2; do
                [ -d \$D ] || continue
                cp /data/local/tmp/libwlicu.so \$D/libwlicu.so
                cp /data/local/tmp/libwlicu.so \$D/libwlic18n.so
                chmod 755 \$D/libwlicu.so \$D/libwlic18n.so
              done
              cp /data/local/tmp/libtttext_lite.so $libdir/libtttext_lite.so
              chmod 755 $libdir/libtttext_lite.so
              rm -f /data/local/tmp/libwlicu.so /data/local/tmp/libtttext_lite.so
              echo icu-tttext-ok"
  fi

  if [ -f "$ROOT/scripts/grant_internet.sh" ]; then
    hdc file send "$ROOT/scripts/grant_internet.sh" /data/local/tmp/grant_internet.sh
    sh_board "sh /data/local/tmp/grant_internet.sh" || warn "grant_internet 失败（若板端已授权可忽略）"
  fi
  ok "头条应用产物就绪"
}

# =============================================================================
# 启停 / 存活 / 截图
# =============================================================================
kill_app_by_uid() {
  local uid="$1"
  sh_board "for p in \$(ps -ef | grep AppSpawnX | grep -v grep | grep $uid | sed 's/  */ /g' | cut -d' ' -f2); do
              kill -9 \$p 2>/dev/null
            done" || true
}

ensure_awake() {
  # keepawake + 上滑解锁，避免截到锁屏
  if [ -f "$ROOT/scripts/keepawake.sh" ]; then
    hdc file send "$ROOT/scripts/keepawake.sh" /data/local/tmp/keepawake.sh
    sh_board "pkill -f keepawake.sh 2>/dev/null; nohup sh /data/local/tmp/keepawake.sh >/dev/null 2>&1 &" || true
  fi
  sh_board "power-shell wakeup >/dev/null 2>&1;
            uinput -T -m 600 1600 600 500 400 >/dev/null 2>&1;
            sleep 1" || true
}

launch_ability() {
  local act="$1" pkg="$2"
  info "aa start -a $act -b $pkg"
  sh_board "aa start -a '$act' -b '$pkg'" || warn "aa start 返回非零（有时仍会拉起，继续观察）"
}

app_alive() {
  local uid="$1"
  # 打印存活进程数（0/1/...）
  sh_board "ps -ef | grep AppSpawnX | grep -v grep | grep -c $uid || true" | tr -d '\r' | tail -1
}

capture_frame() {
  # 从板端拉一张验收截图到 frames/verified_<app>_<timestamp>.jpeg
  local app="$1"
  local ts
  ts="$(date +%Y%m%d-%H%M%S)"
  local remote="/data/local/tmp/verified_${app}.jpeg"
  local local_path="$ROOT/frames/verified_${app}_${ts}.jpeg"

  mkdir -p "$ROOT/frames"
  sh_board "power-shell wakeup >/dev/null 2>&1;
            snapshot_display -f $remote >/dev/null 2>&1;
            ls -l $remote" || true
  hdc file recv "$remote" "$local_path" >/dev/null 2>&1 || die "拉截图失败: $remote"
  local sz
  sz="$(wc -c < "$local_path" | tr -d ' ')"
  info "截图 $local_path  ($sz bytes)"
  # ~38KB 通常是纯白/空窗；有内容的欢迎页/信息流一般 >70KB
  if [ "$sz" -lt 50000 ]; then
    warn "体积偏小，可能仍是白屏。多等一会儿再跑，或查 hilog。"
  else
    ok "体积看起来像真实 UI"
  fi
  printf '%s' "$local_path"
}

wait_alive() {
  local uid="$1" seconds="$2" label="$3"
  local i=0 alive=0
  info "等待 $label 进程存活（最多 ${seconds}s）…"
  while [ "$i" -lt "$seconds" ]; do
    alive="$(app_alive "$uid")"
    alive="${alive:-0}"
    if [ "$alive" -ge 1 ] 2>/dev/null; then
      ok "$label 存活 (uid=$uid, t≈${i}s)"
      return 0
    fi
    sleep 5
    i=$((i + 5))
    info "t=${i}s alive=${alive}"
  done
  warn "$label 在 ${seconds}s 内未见存活进程"
  return 1
}

# =============================================================================
# toutiao / wechat
# =============================================================================
cmd_toutiao() {
  banner "复现 · 今日头条"
  ensure_device
  ok "设备 $HDC_TARGET"

  deploy_shared_runtime
  deploy_toutiao_app

  banner "冷启动头条 MainActivity"
  kill_app_by_uid "$TOUTIAO_UID"
  sleep 2
  ensure_awake
  launch_ability "$TOUTIAO_ACT" "$TOUTIAO_PKG"
  wait_alive "$TOUTIAO_UID" "$WAIT_TOUTIAO_S" "头条" || true

  # 再给解释器一点时间把主界面画出来
  info "额外等待渲染…"
  sleep 20
  local shot
  shot="$(capture_frame toutiao)"

  cat <<EOF

────────────────────────────────────────
头条复现完成
  验收截图: $shot
  对照黄金帧:
    frames/screens/30-feed-recommend-final.jpeg   (推荐信息流)
    frames/screens/34-video-channel-live.jpeg     (视频频道直播)
下一步:
  · 若截图仍是白屏，把 WAIT_TOUTIAO_S 调到 120+ 再跑
  · 交互注入见 scripts/wl_input.sh / scripts/wl_matrix.sh
  · 架构说明 docs/ARCHITECTURE_BLUEPRINT.md
────────────────────────────────────────
EOF
}

cmd_wechat() {
  banner "复现 · 微信 8.0.77 欢迎/登录页"
  ensure_device
  ok "设备 $HDC_TARGET"

  deploy_shared_runtime

  if ! sh_board "ls /data/app/el1/bundle/public/$WECHAT_PKG/android/base.apk" >/dev/null 2>&1; then
    cat <<EOF >&2

FATAL: 板端没有微信安装槽 ($WECHAT_PKG)。

微信 APK (~280MB+) 不进 git。请先按 docs/wechat/PROGRESS.md 完成一次板端部署:
  1. tools/wechat/rebuild_apk.sh     # 重建 patched apk
  2. tools/wechat/sanitize_elf.py    # MUSL init_array 消毒
  3. tools/wechat/elf_weaken.py     # 弱化/改名冲突符号
  4. 用 stub 模型 bm install + swap base.apk（见 PROGRESS.md）

元数据旁路文件: tools/wechat/wl-metadata.properties
EOF
    exit 1
  fi
  ok "板端微信 base.apk 存在"

  banner "冷启动微信 LauncherUI → Welcome/Login"
  kill_app_by_uid "$WECHAT_UID"
  sleep 2
  ensure_awake
  # 适配层读此标志，把 LauncherUI 重定向到 WelcomeActivity（含主题）
  sh_board "echo 'com.tencent.mm.plugin.account.ui.WelcomeActivity 0x7f1102b6' > /data/local/tmp/wl-launch-activity" || true
  launch_ability "$WECHAT_ACT" "$WECHAT_PKG"
  wait_alive "$WECHAT_UID" "$WAIT_WECHAT_S" "微信" || true

  info "等待 WelcomeSelectView 上屏…"
  sleep 15
  local shot
  shot="$(capture_frame wechat)"

  cat <<EOF

────────────────────────────────────────
微信复现完成
  验收截图: $shot
  对照黄金帧:
    frames/wechat/wechat_login_ready.jpeg   (蓝色地球欢迎页 + 登录/注册)
    frames/wechat/wechat_firstframe.jpeg
  排雷全记录: docs/wechat/PROGRESS.md
  ELF 工具:   tools/wechat/
────────────────────────────────────────
EOF
}

cmd_all() {
  cmd_toutiao
  cmd_wechat
}

# =============================================================================
# main
# =============================================================================
CMD="${1:-}"
case "$CMD" in
  check)   cmd_check ;;
  toutiao) cmd_toutiao ;;
  wechat)  cmd_wechat ;;
  all)     cmd_all ;;
  -h|--help|help|"") usage 0 ;;
  *) echo "未知命令: $CMD" >&2; usage 2 ;;
esac
