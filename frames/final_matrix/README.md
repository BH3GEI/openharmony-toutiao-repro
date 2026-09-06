# 全界面验收画册

板端 DAYU200 / OpenHarmony 6.1.0.31，1200x1920。
一次自动化巡检跑出来的连续画面，脚本见 [`scripts/wl_matrix.sh`](../../scripts/wl_matrix.sh)。

运行组合：`base.final10.apk` + `oh-adapter-runtime.all.jar`
（输入泵 · 窗口按秩选 · VelocityTracker · Theme AXML 兜底 · WebView 防崩代理 ·
SQLite shim · TLS 网关 · ALooper）。

## 巡检闭环实测

`推荐流 → 视频频道 → 回信息流 → 搜索页 → 返回 → 个人中心 → 回信息流`

| 步 | 界面 | 驱动 | 结果 | 画面 |
|---|---|---|---|---|
| 1 | 推荐信息流 | 冷启动 | ✅ 真实新闻流 | ![](01-feed-recommend.jpeg) |
| 2 | **视频频道** | `tap 560 213` | ✅ **真实视频流** | ![](02-video-channel.jpeg) |
| 3 | 回到推荐流 | `tap 190 213` | ✅ 正确返回 | ![](03-back-to-feed.jpeg) |
| 4 | 搜索页 | `aa start SearchActivity` | ✅ 窗口上屏（`wlwin=1`）| ![](04-search-activity.jpeg) |
| 5 | 从搜索页返回 | `key 4` | ❌ **未返回** | ![](05-after-mine-tap-still-search.jpeg) |
| 6 | 个人中心 | `tap 1050 1870` | ❌ 未到达（仍在搜索页）| 同上 |
| 7 | 回信息流 | `tap 150 1870` | ❌ 未到达 | 同上 |

**全程 `alive=1`，没有一次闪退。** 这是本轮最实在的结果：以前视频频道点一下就
`InflateException … PullToRefreshSSWebView` 打死进程，现在整条链路跑完进程都活着。

## 逐条说明

**第 2 步是本轮的突破。** 视频频道从"点了必崩"到"渲染出真实视频流"：
两条视频卡片带作者（我爱水电维修 / 心雨分享）、缩略图、
`29.9万次播放`、`01:02` 时长、`287` 评论、`2705` 赞。197 KB，
比推荐流还大——此前该频道的空态截图只有 76–93 KB。

**第 5 步是闭环的断点。** `key 4` 确实派发到了主窗口
（日志 `[WL-INPUT] key 4 -> ViewRootImpl(type=1 …)`），但搜索 Activity 没有 finish，
后续两步因此都停在搜索页上。**闭环走通 1→4，断在返回。**

**深层页面导航（详情页 / 作者主页 / 评论区）仍未打通。** 已剥掉的四层：

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
另外 hdc 接上会弹系统「USB 连接方式」对话框盖住画面，脚本已自动点掉。
