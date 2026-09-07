# 全界面验收画册

板端 DAYU200 / OpenHarmony 6.1.0.31，1200x1920。
一次自动化巡检跑出来的连续画面，脚本见 [`scripts/wl_matrix.sh`](../../scripts/wl_matrix.sh)。

运行组合：`base.final11.apk` + `oh-adapter-runtime.all.jar`
（输入泵 · 窗口按秩选 · 窗口焦点补投 · VelocityTracker · Theme AXML 兜底 ·
WebView 防崩代理 · SQLite shim · TLS 网关 · ALooper）。

## 巡检闭环实测（2026-09-07 11:54 一次跑完）

`推荐流 → 视频频道 → 回信息流 → 搜索页 → 返回 → 个人中心 → 回信息流`

| 步 | 界面 | 驱动 | 结果 | 画面 |
|---|---|---|---|---|
| 1 | 推荐信息流 | 冷启动 | ✅ 真实新闻流 | ![](01-feed-recommend.jpeg) |
| 2 | **视频频道** | `tap 560 213` | ✅ **真实视频流** | ![](02-video-channel.jpeg) |
| 3 | 回到推荐流 | `tap 190 213` | ✅ 正确返回 | ![](03-back-to-feed.jpeg) |
| 4 | 搜索页 | `aa start SearchActivity` | ✅ 窗口上屏（`wlwin=1`）| ![](04-search-activity.jpeg) |
| 5 | 从搜索页返回 | `key 4` | ⚠️ **Activity 已销毁，但底层没重绘** | ![](05-back-finished-blank.jpeg) |
| 6 | 个人中心 | `tap 1050 1870` | ❌ 未到达（画面仍空白）| ![](06-mine-tap-no-repaint.jpeg) |
| 7 | 回信息流 | `tap 150 1870` | ❌ 未到达 | ![](07-feed-tap-no-repaint.jpeg) |

**全程 `alive=1`，没有一次闪退**——包括第 5 步的 Activity 销毁。上一轮这里是
`alive=0`（进程当场死在 `onStop`）。

## 第 5 步这一轮修掉了什么

**返回键本身通了。** 根因是窗口焦点：AOSP 的 `ViewRootImpl.InputStage.deliver()`
第一件事就是 `shouldDropInputEvent()`，它在

```
!mAttachInfo.mHasWindowFocus && !event.isFromSource(SOURCE_CLASS_POINTER)
```

时直接丢事件——**没有焦点的窗口照收触摸，但把每一个按键都吞掉**。焦点本该由 WMS 经
`IWindow.windowFocusChanged` 送进来，而适配层的 `WindowSessionAdapter` 从不做这个调用
（`oh-adapter-runtime.jar` 里根本没有这个符号）。所以 `mHasWindowFocus` 全程为 `false`，
输入泵一直在往一个"契约上必须忽略按键"的窗口里投 KEYCODE_BACK。

板端实测的证据链：

```
[WL-FOCUS] [0] ViewRootImpl(...) hasWindowFocus=false windowVisibility=8  added=true stopped=true
[WL-FOCUS] [1] ViewRootImpl(...) hasWindowFocus=false windowVisibility=0  added=true stopped=true
[WL-FOCUS] [2] ViewRootImpl(...) hasWindowFocus=false windowVisibility=0  added=true stopped=false
[WL-FOCUS] ViewRootImpl(... LIGHT_STATUS_BAR ...) focus -> true (now true)
[WL-INPUT] key 4 -> ViewRootImpl(...) focus=true
[WL-BACK] key: com.android.bytedance.search.SearchActivity finishing after key dispatch
[WL-ACTS] ==== 1 record(s) ====
[WL-ACTS] com.ss.android.article.news.activity.MainActivity paused=true stopped=true
```

补投焦点（`ViewRootImpl.windowFocusChanged()`，即 WMS 少打的那一通回调）之后，
第一次按下就 finish 了搜索页，`mActivities` 从 2 条掉到 1 条——Activity 真的销毁了。

**销毁路上的第二颗雷也拆了。** 通了之后进程立刻死在：

```
java.lang.NoSuchMethodError: No static method getUidRxBytes(I)J in class
  Landroid/net/TrafficStats; (declaration ... adapter-mainline-stubs.jar)
  at X.46Y.h → X.46Y.c → X.46h.b → X.46e.a → X.46e.onActivityStopped
  at Application.dispatchActivityStopped → Activity.onStop
  at ActivityThread.handleStopActivity → StopActivityItem.execute
```

这是**适配层的平台缺口**：`android.net.TrafficStats` 少了 `getUidRxBytes(int)` /
`getUidTxBytes(int)`。它挂在 `onActivityStopped` 上，也就是**任何一次 Activity 销毁**
都会在主线程抛出、没人 catch、`ActivityThread.main` 直接退栈——返回键一通，进程必死。
应用侧先用 dex 补丁绕开（`X.46Y.h(Z)V` 整个方法 `return-void`，它是全 apk 唯一调用
这两个 API 的地方且返回 void），平台侧建议由西湖补齐这两个方法。

## 第 5 步还差什么

销毁完之后**没人把下面的 MainActivity 拉回前台**：真机上 AMS 这时会补发一条
`ResumeActivityItem`，这里没有，所以 MainActivity 停在 `paused=true stopped=true`、
decor 是 GONE，屏幕就是一张空白窗口（38 KB）。第 6、7 步的点击因此打在一个 stopped 的
Activity 上，画面不动。

代码已补：`resumeUnderlyingActivity()` 在 BACK 成功后自己发这条 `ResumeActivityItem`
（走 `ClientTransaction` + `ActivityThread.scheduleTransaction`，让 `TransactionExecutor`
自己走 restart→start→resume），并 `WindowManagerGlobal.setStoppedState(token,false)`
解开 `ViewRootImpl.mStopped`，最后把焦点交还给新的顶层窗口。
**这段尚未板端实测**——板子被并行的微信工作流占用，见下。

## 板端占用说明

`/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar` 是两条工作流共用的
同一个文件。本轮采集期间它被另一条流换成了另一支构建（带 `WL-TAP` / `WL-CONSENT` /
`WL-THEME-SYNC`，不含输入泵），我这边连续三次跑空——taps 没有任何 `[WL-INPUT]` 日志、
SearchActivity 因 Theme 没兜底而 `themeId:0xnull` 崩溃。上表这一次是**借用窗口**跑的：
先把对方的 jar 备份到板端 `oh-adapter-runtime.jar.s2-97a158f7` 和仓库
`prebuilts/oh-adapter-runtime.s2-97a158f7.jar`，跑完立即原样还回去（md5 已核对）。
两支 jar 的特性集互不包含，**二进制无法合并，需要对方的源码才能真正合流**。

## 深层页面导航（详情页 / 作者主页 / 评论区）

仍未打通。已剥掉的四层：

| 层 | 现象 | 状态 |
|---|---|---|
| 1 | `createWebView` NPE → `InflateException` → 进程死 | ✅ WebView 防崩代理 |
| 2 | `WebSettings.getUserAgentString()` on null（`preCreateWebView`）| ✅ dex 中和 classes21 |
| 3 | `emoticon/emoticon.conf` 缺失被 Mira 吞掉 | ✅ dex 中和 classes6 |
| 4 | 适配层 `scheduleTransaction OK`，但 `mActivities` 里始终没有该 Activity | ❌ |

第 4 层的证据很干净：`aa start` 详情页之后，
`[B47-SLA] BEFORE/AFTER scheduleTransaction OK`、
`activityInfo theme=0x7f090002`（Theme 兜底已生效）、
MainActivity 变成 `paused=true stopped=true`（OH 确实切了场景），
但 `ActivityThread.mActivities` **始终只有 MainActivity 一条**，
无异常、无崩溃。事务投递了却没有产生 Activity 记录。

信息流点击那条路同样：`click 400 620 handled by FeedItemRootLinerLayout`
（监听器确实跑了），但 `mActivities` 不变、`MainActivity.paused=false`——
连页面切换都没发起。

## 复现

```bash
hdc file send scripts/wl_matrix.sh /data/local/tmp/wl_matrix.sh
hdc shell "sh /data/local/tmp/wl_matrix.sh"
hdc file recv /data/local/tmp/WM_01-feed.jpeg ./
```

采集前**先重启板子**：冷启动 50–80 s 后进程自行消失与内存强相关
（重启后 5.0 GB 空闲时稳定，连跑数轮掉到 ~1.0 GB 即开始失败）。
重启刚完成的头一两分钟负载很高，这时起也会掉，等一分钟再跑。
另外 hdc 接上会弹系统「USB 连接方式」对话框盖住画面，脚本已自动点掉。
