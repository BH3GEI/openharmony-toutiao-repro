# 界面矩阵

板端 DAYU200 / OpenHarmony 6.1.0.31，1200x1920，真机实测原图。
采集脚本 [`scripts/wl_screens.sh`](../../scripts/wl_screens.sh)，
运行组合 `base.final14.apk` + `oh-adapter-runtime.all.jar`。

编号 35 以上是 2026-09-07 的最新一轮（一次跑完，全程 `alive=1`），
更早的编号是各阶段的历史证据，保留不动。

## 最新一轮（2026-09-07 14:24，全程 alive=1）

| 界面 | 文件 | 大小 | 状态 |
|---|---|---|---|
| 推荐频道流 | [`35-feed-recommend.jpeg`](35-feed-recommend.jpeg) | 219 KB | ✅ 真实内容 |
| 热榜频道流 | [`36-hotlist-channel.jpeg`](36-hotlist-channel.jpeg) | 78 KB | ⚠️ tab 切换成功，服务端空态 |
| 视频频道流 | [`37-video-channel.jpeg`](37-video-channel.jpeg) | 198 KB | ✅ 真实视频卡片 |
| 搜索主界面 | [`38-search-activity.jpeg`](38-search-activity.jpeg) | 44 KB | ✅ 上屏，内容区空态 |
| 返回后的信息流 | [`40-back-from-search-live-feed.jpeg`](40-back-from-search-live-feed.jpeg) | 219 KB | ✅ 销毁搜索页后信息流回到前台 |
| 详情页 | [`44-detail-activity-oh-terminated.jpeg`](44-detail-activity-oh-terminated.jpeg) | 38 KB | ❌ 未取得——三条路都查到底了，见下 |

热榜与搜索内容区为空是同一根因：`shared_prefs` 里没有 `device_id`/`install_id`，
`api.toutiaoapi.com` 一律 `400 invalid user`。**这两格的"空"是服务端返回的真实状态，
不是渲染缺陷**——热榜的 tab 确实切过去了（红色下划线在「热榜」上），
渲染的是应用自己的「网络异常，请稍后重试」空态页。我没有伪造内容。

## 单容器流转链路（2026-09-07 15:37，板端实测）

跨窗口那条路走不通，就不再依赖它：把目标挂进宿主 Activity 自己的
`android.R.id.content`（实测是 `androidx.appcompat.widget.ContentFrameLayout`），
`FragmentTransaction` 挂上去是一次页面切换，不离开已经在屏幕上的窗口。

| 步 | 画面 | 大小 | 证据 |
|---|---|---|---|
| 挂载前 | [`41-mount-before-feed.jpeg`](41-mount-before-feed.jpeg) | 219 KB | 完整信息流 |
| 挂载后 | [`42-mount-page-in-container.jpeg`](42-mount-page-in-container.jpeg) | 44 KB | 新页整屏覆盖，频道栏与底部导航都被盖住 |
| 物理返回后 | [`43-mount-after-back-feed.jpeg`](43-mount-after-back-feed.jpeg) | 219 KB | 信息流原样回来 |

```
[WL-MOUNT] mounted ...RecommendFragmentV4 as wl-mount-1 backStack=1
[WL-INPUT] key 4 -> ViewRootImpl(...)
[WL-MOUNT] pop ok backStack 1 -> 0
[WL-BACK]  key: popped a mounted page, activity kept
[WL-MOUNT] android.R.id.content = androidx.appcompat.widget.ContentFrameLayout children=2
[WL-MOUNT] fm=androidx.fragment.app.FragmentManagerImpl backStack=0
```

全程 `alive=1`，Activity 没有被 finish —— `injectKey` 先问 `unmountFragment()`，
有挂载页就弹页、没有才 finish，和真机上 FragmentManager 回退栈优先于
`Activity.finish()` 的顺序一致。

**42 号图要说清楚**：挂上去的是 `RecommendFragmentV4`——应用自己正在用的类，
新起一个实例。它渲染出来的是应用自己的「当前网络不可用，点击重试」空态，
因为这个新实例没有数据（还是 `device_id` 那条身份线）。
**这不是详情页**，是用应用真实的 UI 证明容器内切与物理返回这条链路通了。

## 详情页本身：挂得上去，但建不出视图

真正的文章详情 fragment 挂载时报的是：

```
[WL-MOUNT] mount com.ss.android.detail.feature.detail2.article.NewArticleDetailFragment
  failed: java.lang.ClassCastException:
  com.ss.android.article.news.activity.MainActivity cannot be cast to X.DQt
```

事务本身是成功的——`frags` 能看到它 `container=16908290`（即 `android.R.id.content`）
`added=true`，回退栈也涨到了 2。**卡在它的宿主契约**：`NewArticleDetailFragment`
会把 `getActivity()` 强转成 `X.DQt`，那是详情 Activity 实现的一个 9 方法接口
（`getLeftSlideContainer` / `showPgcLayout` / `onFavorBtnClicked` ...），MainActivity 没有。

所以详情页要在单容器里落地，得让宿主满足 `X.DQt`。这是给宿主补接口的活
（结构性 dex 改动，不是等宽补丁），不是适配层能从外面绕过去的。

## 详情页第三条路：`aa start` 详情 Activity —— OH 主动把它 terminate 了

单容器挂不上（宿主契约 `X.DQt`），点击不发起跳转（`startActivity` 计数 0），
剩下第三条：像搜索页那样让 OH 直接拉起详情 Activity
`com.ss.android.detail.feature.detail2.view.NewDetailActivity`。
截图 [`44-detail-activity-oh-terminated.jpeg`](44-detail-activity-oh-terminated.jpeg)（38 KB 空白窗口）。

**这一轮把这条路也查到底了，结论和以前记的不一样。** 适配层这边做得很完整：

```
[B47-SLA] ENTRY   ability=...NewDetailActivity recordId=11 wantJson.len=786
[B47-SLA] intent.component=ComponentInfo{.../NewDetailActivity}
          extrasKeys=17 activityInfo.theme=0x7f090002
[B47-SLA] BEFORE scheduleTransaction className=...NewDetailActivity
[B47-SLA] AFTER  scheduleTransaction OK
```

17 个 intent extra、Theme 兜底给出的 `0x7f090002` 都在，事务也投出去了。
**全日志里没有任何一条提到 detail2 的异常**，Mira 的 `handleException` 也没触发。

同一秒的 hilog 说明了原因——**是 OH 把这个 ability 终止掉了**：

```
[AMSI3514] Terminate ability come.
[ARR1093]  isForce: 1
[ARR1103]  terminate com.ss.android.detail.feature.detail2.view.NewDetailActivity
```

`Terminate ability come` 是一次显式的 TerminateAbility IPC，不是超时回收。
所以 Activity 记录始终没产生，不是因为事务失败，而是**主线程还没执行到那条
排队的 LaunchActivityItem，ability 就已经被 OH 拆掉了**。

以前记的「事务投递了却没产生 Activity 记录」只说对了一半，
漏掉了 OH 侧这个主动 terminate —— 之前没看 hilog，看不到。

### 一条具体线索：只有它声明了 `hardwareAccelerated=false`

三个 Activity 的 manifest 声明放一起看：

| Activity | `hardwareAccelerated` | `configChanges` | 结果 |
|---|---|---|---|
| MainActivity | `0xffffffff`（true） | 4016 | ✅ 正常 |
| SearchActivity | 未声明（继承 application） | 4016 | ✅ 正常上屏 |
| **NewDetailActivity** | **`0`（false）** | 1952 | ❌ 被 OH terminate |

被 terminate 的那一个，恰好是三个里唯一显式声明**关闭硬件加速**的。
本仓库 WHITEBOARD 早前几节记过这套适配层在软绘窗口上的老问题
（NativeWindow session 串号、EGL surface 被拖下水、子窗口摘 HW 加速引发的连锁）。
**这是相关性加一个可信的机制，不是已证实的因果**——需要平台侧确认
OH 侧对 `hardwareAccelerated=false` 的窗口走的是哪条路径、谁发的 TerminateAbility。

## 详情页跳转本身：这一轮把问题问清楚了

点击信息流卡片，监听器确实跑：

```
[WL-INPUT] click 400.0,620.0 handled by
  com.ss.android.article.base.feature.feed.widget.FeedItemRootLinerLayout (0 levels up)
```

但 `ActivityThread.mActivities` 不变。此前一直有两种可能：**应用调了
`startActivity` 而适配层丢了**，还是**应用根本没调**。之前分不清，是因为
`ActivityManagerAdapter.startActivity` 的日志

```java
Log.d("OH_AMAdapter", "[BRIDGED] startActivity -> OH IAbilityManager.StartAbility")
```

走的是 hilog，不是子进程 stderr——两边 grep 都查不到，等于没证据。

这一轮在点击前后抓 hilog（`hilog -b DEBUG`，同一进程同一时刻的
`[BRIDGED] getProcessesInErrorState` 密集刷屏，证明抓取是活的、debug 级别没被过滤）：

```
[wl-screens] startActivity in hilog: 0
```

**应用压根没有调用 `startActivity`。** 点击处理器跑完就结束了，导航根本没发起。
所以这不是适配层的路由丢失，而是应用自己在 `startActivity` 之前就放弃了——
下一步该查的是那个监听器里、发起跳转之前的前置条件（多半又是身份/`device_id`
那条线，或者被某个 SDK 的开关拦掉），而不是继续查 Activity 启动链路。

## WebView 承载的频道：下一个明确的靶子

热榜之后如果继续切到相邻频道，`ViewPager` 会顺手创建相邻页，而相邻的
`CategoryBrowserFragment` 是 WebView 承载的：

```
NPE: WebSettings.getUserAgentString() on null
  at MediaAppUtil.getWebViewDefaultUserAgent → BrowserFragment.initCustomUaIfNeed
  at BrowserFragment.onActivityCreated(:2225)
```

中和 `initCustomUaIfNeed` 之后，它前进到同一个方法的**下一行**：

```
NPE: WebSettings.setGeolocationEnabled(boolean) on null
  at BrowserFragment.onActivityCreated(:2230)
```

`onActivityCreated` 里是一整段 `getSettings().xxx()`，逐行中和是打地鼠。
真正的修法是**让 `WebView.getSettings()` 返回一个非 null 的惰性对象**——
现在的 WebView 防崩代理做不到，因为 `android.webkit.WebSettings` 是抽象类，
`java.lang.reflect.Proxy` 只能实现接口。要一个真正继承 `WebSettings`、
把所有抽象方法都实现掉的具体子类。这是详情页与 WebView 频道共同的下一层。
