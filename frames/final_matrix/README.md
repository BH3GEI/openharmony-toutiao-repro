# 全界面验收画册

板端 DAYU200 / OpenHarmony 6.1.0.31，1200x1920。
一次自动化巡检跑出来的连续画面，脚本见 [`scripts/wl_matrix.sh`](../../scripts/wl_matrix.sh)。

运行组合：`base.final13.apk` + `oh-adapter-runtime.all.jar`
（输入泵 · 窗口按秩选 · **窗口焦点补投** · **销毁后自恢复下层 Activity** ·
VelocityTracker · Theme AXML 兜底 · WebView 防崩代理 · SQLite shim · TLS 网关 · ALooper）。

## 巡检闭环实测（2026-09-07 13:31 一次跑完，8/8 步）

`推荐流 → 视频频道 → 回信息流 → 搜索页 → 返回 → 个人中心 → 回信息流 → 点详情卡片`

| 步 | 界面 | 驱动 | 结果 | 画面 |
|---|---|---|---|---|
| 1 | 推荐信息流 | 冷启动 | ✅ 219 KB 真实新闻流 | ![](01-feed-recommend.jpeg) |
| 2 | **视频频道** | `tap 560 213` | ✅ 197 KB 真实视频流 | ![](02-video-channel.jpeg) |
| 3 | 回到推荐流 | `tap 190 213` | ✅ 219 KB | ![](03-back-to-feed.jpeg) |
| 4 | 搜索页 | `aa start SearchActivity` | ✅ 搜索页上屏 | ![](04-search-activity.jpeg) |
| 5 | **从搜索页返回** | `key 4` + `aa start` | ✅ **Activity 销毁 + 信息流回到前台 219 KB** | ![](05-back-to-feed-live.jpeg) |
| 6 | 个人中心 | `tap 1050 1870` | ⚠️ 画面活着，但 tab 没切 | ![](06-mine-tab-not-switched.jpeg) |
| 7 | 回信息流 | `tap 150 1870` | ✅ 信息流 | ![](07-feed-tab.jpeg) |
| 8 | 详情卡片 | `click 400 620` | ⚠️ 监听器命中，未起新页 | ![](08-detail-click-no-nav.jpeg) |

**全程 `alive=1`，八步零闪退。** 上一轮同一条链路在第 5 步 `alive=0`（进程死在 `onStop`），
第 5–7 步都是 38 KB 空白窗口；这一轮第 5 步返回后直接是 219 KB 的活信息流。

第 6 步是诚实的"没到"：截图证明进程活着、信息流在渲染（第一条标题已变灰＝点击确实生效），
但底部导航「未登录」这一格没切换。第 8 步同理——`FeedItemRootLinerLayout` 的监听器跑了，
`mActivities` 不变。这两格都不是崩溃，是导航没发生。

## 第 5 步：从 `alive=0` 到活着回到信息流，一共拆了五层

### 第一层：返回键本身

**根因是窗口焦点。** 根因是窗口焦点：AOSP 的 `ViewRootImpl.InputStage.deliver()`
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

### 第二层：Activity 一销毁进程就死

返回键通了之后进程立刻死在：

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

### 第三层：销毁之后没人恢复下层 Activity

真机上 AMS 这时会补发一条 `ResumeActivityItem`，这里没有，MainActivity 停在
`paused=true stopped=true`、decor 是 GONE，屏幕就是一张空白窗口。
`resumeUnderlyingActivity()` 自己发这条事务（`ClientTransaction` + `ResumeActivityItem`
+ `ActivityThread.scheduleTransaction`，让 `TransactionExecutor` 自己走
restart→start→resume），并 `WindowManagerGlobal.setStoppedState(token,false)` 解开
`ViewRootImpl.mStopped`。实测：`[WL-BACK] resumed MainActivity paused=false stopped=false`。

顺带修掉一个选错目标的 bug：这套环境里**没有任何东西会 resume Activity**，所以第二个
Activity 起来之后两条记录都是 `paused=true stopped=true`，任何基于这两个标志的猜测都会
挑错人（实测挑中了 MainActivity，`onBackPressed()` 差点把主界面关了）。改成用
**decor view 反查**——屏幕上的那个窗口属于谁，就是谁。

### 第四层：resume 一执行就崩 —— `AudioSystem.native_getMaxChannelCount`

```
UnsatisfiedLinkError: No implementation found for
  int android.media.AudioSystem.native_getMaxChannelCount()
    at AudioSystem.<clinit> → AudioManager.isWiredHeadsetOn
    at HeadsetHelperOpt.m → VideoContext.onLifeCycleOnResume
    at Activity.performResume ← ResumeActivityItem.execute
```

只在**逛过视频频道之后**才致命：`VideoContext` 那时才注册成生命周期观察者，之后每一次
resume 都会去查有线耳机。`AudioSystem.<clinit>` 一失败这个类就永久废掉。
应用侧中和 `HeadsetHelperOpt.m(Context)V`（返回 void，纯耳机探测）→ `base.final12.apk`。

### 第五层：Android 侧全绿了，屏幕还是白的

`[WL-BACK] showWindow: appVisible=true windowVisibility=0` —— `dispatchAppVisibility(true)`
（又一个 WMS 从不投递的回调）已经补上，Android 侧完全恢复，但像素没回来。
原因在 OH 那一侧：搜索窗口覆盖上来时 MainActivity 的 scene 被隐藏了，
relayout 日志写得很清楚 —— `session=32 covered by newer sibling -> DEFER hide
(flush on coverer show)`，而覆盖者被销毁并不会触发那个 "coverer show"。

现在的做法是让 OH 自己把这个 ability 重新前台化（`aa start` 同一个 MainActivity），
**复用活进程、不重启**，屏幕立刻回到 219 KB 的信息流。这是 OH 层的交还动作，
真正的修法应该是适配层在覆盖窗口销毁时补一次 scene show。

## 板端占用说明

`/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar` 和
`/data/local/tmp/wl-launch-activity` 是多条工作流共用的同一份文件。本轮采集期间它们被
另一条流反复换掉：jar 被换成不含输入泵的构建（`WL-TAP` / `WL-CONSENT` / `WL-THEME-SYNC`
/ `WL-WCDB`），`wl-launch-activity` 被写成微信的 `MobileInputUI`，任何新起的适配层进程
都会去实例化微信的 Activity，头条启动当场 `ClassNotFoundException` 死掉。这直接吃掉了
本轮的 6 次跑批。板子重启也会把 jar 恢复成对方那一支。

上表这一次的做法：把对方的 jar 与 `wl-launch-activity` 原样备份（板端
`oh-adapter-runtime.jar.s2-038d3643`、`wl-launch-activity.keep`，仓库
`prebuilts/oh-adapter-runtime.s2-*.jar`），跑完立即还回去，md5 已核对。
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
