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
| 详情页 | [`39-detail-click-no-startactivity.jpeg`](39-detail-click-no-startactivity.jpeg) | 219 KB | ❌ 未取得，见下 |

热榜与搜索内容区为空是同一根因：`shared_prefs` 里没有 `device_id`/`install_id`，
`api.toutiaoapi.com` 一律 `400 invalid user`。**这两格的"空"是服务端返回的真实状态，
不是渲染缺陷**——热榜的 tab 确实切过去了（红色下划线在「热榜」上），
渲染的是应用自己的「网络异常，请稍后重试」空态页。我没有伪造内容。

## 详情页：这一轮把问题问清楚了

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
