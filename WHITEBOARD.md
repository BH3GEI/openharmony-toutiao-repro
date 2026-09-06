# A2OH Toutiao 攻坚协同白板 (WHITEBOARD)

> 更新时间: 2026-09-04 11:05 (Supervisor 初始建立)
> 协同模式: Supervisor (Cursor) & Worker (Cindy) 双 Agent 通宵协同

---

## 一、 当前目标与最高优先级 (Supervisor 指令)

**终极交付验收标准**: 越过开屏广告，成功渲染 `ArticleMainActivity` 首帧信息流，并在 `~/toutiao-repro/frames/` 留存全彩截图与控件树。

### 阶段 1: 立即解决应用网络权限 (Priority 1)
- **事实基线**:
  1. 板子 WiFi (`ly` / `llllyyyy`) 已连通，外网 ping/DNS 完全正常。
  2. `permfix/entry.hap.internet` 已经制作完毕，包含 `ohos.permission.INTERNET` 等 3 项声明。
- **免清空重装/无损打通方案**:
  - **方案 A (无损覆写 HAP)**:
    - 备份原目录: `cp -a /data/app/el1/bundle/public/com.ss.android.article.news /data/local/tmp/news_bundle_backup`
    - 不要无脑 `bm install -r`，可以尝试仅把 `entry.hap.internet` 覆写到原 bundle 路径，或者 `bm install -r -p ...` 之后若发现 `android/base.apk` 或 native 缺失，直接从备份中 `rsync -a` 拷回。
  - **方案 B (底层注入 GID 3003)**:
    - Linux 内核 socket(AF_INET) 仅检查进程所属的 supplementary groups 是否包含 `inet` (GID 3003)。
    - 可在 `appspawn-x` fork 出来的子进程或其启动脚本中，确保加上 GID 3003。
- **验收**: 启动头条后，子进程不再报 `create socket failed for family: 2, errno: 1 (EPERM)`。

---

### 阶段 2: 突破 ColorMatrix 阻断 (Priority 2)
- **事实基线**:
  1. `colormatrix/adapter-mainline-stubs.cmfix.jar` 已由 d8 精确修复生成（190 classes，单 dex，双 set 方法齐备）。
  2. Worker 已采用更精妙的 DEX 补丁方案：在头条 `classes6.dex` 中对 `AsyncImageView.<clinit>` 做指令级中和（置空 `mNightColorFilter`），验证已不再抛出 `NoSuchMethodError: ColorMatrix.set`。

---

### 阶段 3: 穿透应用初始化阻塞，贯通真正 UI 绘制 (已完成 5 项根因拆除)
- 详见后文「六、通宵结果汇总」，已攻克 5 大致命缺陷（网络权限、ColorMatrix、OkHttp、ICU SIGSEGV、NPTH 栈冲突）。

---

### 阶段 4: 击穿 ~80s 隐藏主线程崩溃，释放真实首帧渲染 (当前最高优先级 Priority 1)
- **战果流水记录**:
  1. **Lancet 崩溃清零**: Worker 已按 Supervisor 方案打入 `classes20.dex` 补丁，置空 `PrivateApiLancetImpl.<clinit>` 开头指令，`checkAccessibilityService` 彻底通过（0 崩溃）！
  2. **音频桩崩溃清零**: 随后在 `delayInitNew` 中暴露的 `AudioPortEventHandler.native_setup` 缺失，已通过 `classes8.dex` 将 `HeadsetHelperOpt.p()` 空转中和（`base.final5.apk`，PID 10433 验证通过，音频崩溃归零）！
- **随后暴露的衍生 SDK 崩溃**:
  - 在 PID 10433 运行至 80s 超时时，`delayInitNew` 后续流程中的 `LuckyDogSDK` / `LuckyServiceSDK` 尝试初始化：
    ```text
    CHILD_CK J_invokeStaticMain_main_threw: java.lang.NullPointerException: getAppName(...) must not be null
        at X.82v.getExtraConfig(SourceFile:459074)
        at X.7z4.getExtraConfig(SourceFile:262177)
        at com.bytedance.ug.sdk.luckydog.api.manager.LuckyDogApiConfigManager.init(SourceFile:33947745)
        at com.ss.android.article.base.feature.main.ArticleMainActivity.delayInitNew(SourceFile:591074)
        at com.ss.android.article.base.feature.main.ArticleMainActivity.delayInit(SourceFile:196625)
        at com.ss.android.article.base.feature.main.ArticleMainActivity.onWaitFeedTimeout(SourceFile:196625)
    ```
- **核心架构洞察：根除打地鼠，直接关闭 delayInit 总闸门 (Master Fix)**:
  - 为什么会在 `onWaitFeedTimeout` 后面连续爆出 3~4 个 SDK 崩溃？
    因为 `delayInit` 是头条为了避免启动卡顿，将所有非核心插件（LuckyCat 抢红包、各种广告 SDK、延迟监控等 1200 多行指令）推迟到网络超时或信息流加载完成后的**延迟初始化任务**！
  - **这些延迟初始化任务与主界面核心视图绘制毫无关系**！
  - `ArticleMainActivity.delayInit()V` 是调用 `delayInitNew()` 的唯一总入口：
    ```text
    ArticleMainActivity.delayInit()V:
      0011: invoke-virtual ArticleMainActivity->delayInitNew()V
      0014: return-void
    ```
  - **总闸门修法 (classes15.dex 顶级根治)**:
    - 目标类: `Lcom/ss/android/article/base/feature/main/ArticleMainActivity;`
    - 目标 DEX: `classes15.dex`
    - 目标方法: `delayInit()V`（代码偏移 `code_off=0x7d63ec`）。
    - 指令替换: 入口 `sget-object changeQuickRedirect`（4 字节）直接改为 `return-void ; nop` (`0e 00 00 00`)。
    - **预制成品**: 已在 `yao-win` 上预烘焙完成 `C:\Users\yao\wlbuild\base.final6.apk`。
  - **终极预期效果**:
    `onWaitFeedTimeout` 触发时，`delayInit` 瞬间安全返回，彻底斩断后续所有未适配 SDK 的崩溃链路！主线程专心致志交付 MainActivity 离线首帧与导航栏控件渲染！

---

## 二、 关键资产与路径索引
- **板上通信**: `~/toutiao-repro/hdc-remote` (通过 yao-win relay 连接开发板 `5ce1227d00000000000000000923012c`)
- **双端并行比对工具箱 (极力推荐)**:
  `yao-win` 上同时连着同款芯片的原生安卓真机与鸿蒙板，可随时双端对照头条行为：
  - 鸿蒙端: `./hdc-remote shell ...` (目标板: `5ce1227d00000000000000000923012c`)
  - 原生安卓真机端: `ssh yao-win "C:\Users\yao\platform-tools\adb.exe -s N100CU025C18D000458 shell ..."`
  - 安卓 14 模拟器: `ssh yao-win "C:\Users\yao\platform-tools\adb.exe -s emulator-5554 shell ..."`
  - **建议对照点**:
    1. 进程权限与组比对: `cat /proc/<pid>/status | grep -E 'Uid|Gid|Groups'` (直接确认官方环境下头条所分配的 supplementary groups)
    2. 开屏时序与跳转比对: `logcat -s SplashActivity:V ArticleMainActivity:V ActivityTaskManager:V` (观察原生环境下开屏在断网/连网下的超时跳转逻辑)
    3. 主界面布局与控件树: `uiautomator dump` (提取原生头条 MainActivity 真实控件树，作为鸿蒙端渲染对照基准)
- **待烘/待装补丁**:
  - 完整 ColorMatrix jar: `~/toutiao-repro/colormatrix/adapter-mainline-stubs.cmfix.jar`
  - 带网络权限 HAP: `~/toutiao-repro/permfix/entry.hap.internet`
  - 烘焙原始命令行: `~/toutiao-repro/colormatrix/bake/ORIGINAL-DEX2OAT-CMDLINE.txt`
- **交付成果目录**: `~/toutiao-repro/frames/` (目标截图) 与 `~/toutiao-repro/toutiao_firstframe_success.tar.gz`

---

## 三、 执行记录与 Worker 状态更新区
*(Cindy 可直接追加写本区，记录最新尝试与结论)*
- `[11:02]` Worker 完成 `adapter-mainline-stubs.cmfix.jar` 与 `entry.hap.internet` 制作与归档。

## 四、 Supervisor 自动巡检流水
- `[10:45:19]` 总行数: 9115 | 最新角色: user | 最新执行工具: Bash: cd ~/toutiao-repro && python3 - <<'PY'
import io
def sub(p, pairs):
    s=io.open(p,encoding='utf-8').read()
    for a,b | 工具返回: ok README.md
ok scripts/wl_input.sh
ok scripts/deploy_and_run.sh
syntax OK
f7b4c82 文档：--input 已改名为 --all，补上 libwlveltrac | 最新说明: [Request interrupted by user]
- `[10:42:03]` 总行数: 9114 | 最新角色: user | 最新执行工具: Bash: cd ~/toutiao-repro && python3 - <<'PY'
import io
def sub(p, pairs):
    s=io.open(p,encoding='utf-8').read()
    for a,b | 工具返回: ok README.md
ok scripts/wl_input.sh
ok scripts/deploy_and_run.sh
syntax OK
f7b4c82 文档：--input 已改名为 --all，补上 libwlveltrac | 最新说明: [Request interrupted by user]


## 九、Boot Image 重烘尝试（用宿主机 Android 设备）

### 9.1 关键发现：原始 dex2oat  | 工具返回: -rw-r--r--@ 1 mac  staff   342K Sep  4 11:02 toutiao_firstframe_success.tar.gz
b3ec8aea4d220bb390550aef420a89ba9dbf988cb
- `[04:21:00]` 总行数: 6364 | 最新角色: user | 最新执行工具: Bash: cd /tmp/cv2/openharmony-toutiao-repro && cp /tmp/base.orig.apk /tmp/cv2/vendor.apk && echo "=== 无网重建两个 apk（只用仓库内资产）==="  | 工具返回: === 无网重建两个 apk（只用仓库内资产）===

[VERIFY] all 24801 entries identical to prebuilts/base.final6.apk  ✓

[VERIFY] all 24802 ent | 最新说明: Final self-contained verification from a clean clone.
- `[04:18:14]` 总行数: 6348 | 最新角色: assistant | 最新执行工具: Bash: cd ~/toutiao-repro/prebuilts && gh release upload v1.0-firstframe --repo BH3GEI/openharmony-toutiao-repro --clobber oh-a | 工具返回: 12dddbe 整合 TLS 桥接资产 + 真机界面验收矩阵
pushed
Shell cwd was reset to /Users/mac/Library/Application Support/CindyGlobal/owners/a | 最新说明: Committing and publishing the new assets.
- `[04:15:10]` 总行数: 6304 | 最新角色: user | 最新执行工具: Bash: cd ~/toutiao-repro && mkdir -p frames/screens


## 13. ✅ MainActivity 首帧达成（2026-09-05 16:31）

`frames/mai | 工具返回: -rw-r--r--@ 1 mac  staff  2241799 Sep  5 01:39 toutiao_firstframe_success.tar.gz
03f5b6e2ee553461c8a6
=== board final st | 最新说明: **主界面出来了。** 🎉
- `[01:39:19]` 总行数: 5783 | 最新角色: user | 最新执行工具: Bash: cd ~/toutiao-repro && ./hdc-remote file recv /data/local/tmp/MAIN_final.jpeg frames/night2/MAINACTIVITY-firstframe.jpeg  | 工具返回:      120 frames/mainactivity/MAINACTIVITY-viewtree.txt
total 312
drwxr-xr-x@  5 mac  staff    160 Sep  5 01:38 .
drwxr-x | 最新说明: Full control tree captured. Finalizing the deliverables.
- `[01:36:16]` 总行数: 5761 | 最新角色: assistant | 最新执行工具: Bash: cd ~/toutiao-repro/amr && scp -q yao-win:'C:/Users/yao/classes-merged.dex' /tmp/classes-merged.dex && python3 - <<'PY'
i | 工具返回: probe now also dumps the view tree
BUILD-OK
Shell cwd was reset to /Users/mac/Library/Application Support/CindyGlobal/ow | 最新说明: All fix markers confirmed: 13 sub-window interceptions (vs 2 before), `SURFACE_CHANGED(2) -> 0` on t
- `[01:33:16]` 总行数: 5727 | 最新角色: assistant | 最新执行工具: Bash: cd ~/toutiao-repro && ./hdc-remote shell "ps -ef | grep -c '[r]un_final.sh'" 2>&1 | tr -d '\r'; ./hdc-remote shell "sh / | 工具返回: (eval):1: no matches found: /tmp/bsh.*.sh


## 12. 第三轮（09:00–10:00）：Lancet 线索验证 + 死因定案

### 又拆掉两颗雷（都在 | 工具返回: -rw-r--r--@ 1 mac  staff  2011450 Sep  4 18:53 toutiao_firstframe_success.tar.gz
b795bf794a66d7fcdcd18fa7
Shell cwd was  | 最新说明: Supervisor 的定位是对的，补丁生效了 —— 但它不是最后一颗雷。**主界面截图仍然没拿到**，不过死因这次定案了。
- `[18:51:11]` 总行数: 5618 | 最新角色: user | 最新执行工具: Bash: sleep 115; cat "/private/tmp/claude-501/-Users-mac-Library-Application-Support-CindyGlobal-owners-ae98ca1d7b2b6ae48f15-d | 工具返回: deployed

[exited with code 0]
t=20s alive=1 size=38679
t=40s alive=1 size=38672
t=60s alive=1 size=38672
t=80s alive=0  | 最新说明: Two things learned: my substitution fired but on a **sub-window** (`type=1000`, PopupWindow), where
- `[18:48:09]` 总行数: 5610 | 最新角色: assistant | 最新执行工具: Bash: cd ~/toutiao-repro/amr && scp -q yao-win:'C:/Users/yao/classes-merged.dex' /tmp/classes-merged.dex && python3 - <<'PY'
i | 工具返回: patched


## 六、通宵结果汇总（截至 05:00，Worker）

### 今晚新修好的 5 个根因（全部有确定性证据，且都不需要重烘 boot image）

| # | 根因 | 修法 | 落点 |
|---|---|---|---|
| 1 | 应用无网络权限：`socket(AF_INET)` EPERM。机制是 OH appspawn 的 `SetInternetPermission` 按 **AccessToken** 判定，装包器一条权限都没映射 | 直接往 `access_token.db` 补两行 `permission_state_table`（`grant_state=0`/`grant_flag=4`），重启 `accesstoken_service` | 系统 DB，**未重装 bundle** |
| 2 | `ColorMatrix.set([F)V` 缺失 → `AsyncImageView.<clinit>` 挂 → 整个信息流布局无法 inflate | 改 App dex：`new-instance`→`const/4 v1,#0`+`nop`，`invoke-direct`→3×`nop`，字段存 null（该字段只是夜间灰度滤镜） | `base.apk/classes6.dex` |
| 3 | `okhttp3.internal.platform.Platform.<clinit>` 抛 `No platform found on Android`（BCP 无 conscrypt） | 追加 `classes22.dex`，内含空的 `com.android.org.conscrypt.SSLParametersImpl`（okhttp 只当反射 token 用；它走 Mira 的 App 类加载器，**不需要动 BCP**） | `base.apk` |
| 4 | 每 18s 必崩 SIGSEGV：`libtttext_lite.so` 的 `ICUWrapper` 按 `/system/usr/icu` 里的 `icudt74l.dat` 推出 ICU=74，去 dlsym `ubrk_open_74`，而适配层 Android 侧 ICU 是 **72** | `libwlicu.so`：15 个符号 `b <name>_72` 裸尾跳转（同时导出无后缀与 `_74`）；等长字节改 tttext 的两个 dlopen 名 | App lib 目录 |
| 5 | ~25s 必崩：`libnpth.so` 注册 bionic 形状的 fdsan 回调，musl `close()`→`fdsan_close_with_tag` 调它时撞 `__stack_chk_fail` | 从 base.apk 里删掉 `lib/arm64-v8a/libnpth.so`（改名没用：适配层的 `app_librarian` 每次启动从 apk 重新解包） | `base.apk` |

外加一个 App 侧小补丁：`X/4Li.a()` 里 `if-nez v0,+90`（`TextUtils.isEmpty(appName)` 为真就抛）改成两个 `nop`，
绕过 `IllegalArgumentException: appName is empty`——它在越过隐私弹窗后成为 `MainActivity.onCreate` 的硬崩点。

### 现在的真实状态（有主线程栈为证，不是猜测）

进程稳定存活 60s+，`ColorMatrix / okhttp Platform / socket EPERM` 计数全部为 **0**。
把探针改到**非主线程**后（主线程 Handler 探针在关键时刻正好被自己卡住），拿到四次主线程栈：

```
pass1(15s) RUNNABLE  MainActivity.onCreate -> SplashMainActivity.onCreateNetworkAllow
                     -> startAppListThread -> ArticleServiceImpl.startExecuteAppListStr
pass2(30s) RUNNABLE  MainActivity.doOnResume -> InitTaskDispatcher.d   (已进 onResume！)
pass3(50s) RUNNABLE  同上
pass4(70s) RUNNABLE  同上，仍在 InitTaskDispatcher
```

**结论：主线程没有死锁，也没有抛异常——它在纯解释执行下慢。** `onCreate` 已经跑完，
`onResume` 里 ByteDance Lego 的 `InitTaskDispatcher` 串行跑一长串 init task，70s 还没跑完，
所以第一帧一直没排上，窗口保持白屏。

### 已排除的两条"看起来像"的解释

- **不是 AMS 生命周期超时**：`persist.sys.abilityms.timeout_unit_time_ratio` 从 20 调到 400
  （并改了 recover 脚本让它持久化）后，死亡时间点没有改变。
- **不是渲染管线坏了**：开屏（SplashActivity）能画出完整彩色帧；HWUI 的
  `drawRenderNode called on a context with no surface` 整个进程只出现 1 次，且和白屏不同步。

### 明确的平台级缺口（今晚无法在 App 侧解决，需要适配层介入）

1. **适配层没有 TLS**。日志原文：
   `BaseException{errorCode=1000, errorMsg='[d-ex]:DoConnect-java.lang.UnsupportedOperationException: TLS shim: no real networking (construct-only SSLContext on OH)'}`
   即使网络权限打通、okhttp 能初始化，**任何 HTTPS 请求都发不出去**，信息流不可能有内容。
2. **PopupWindow 拿不到 OH session**。隐私弹窗是 PopupWindow(type=2)，
   `createSession ... tokenAddr=0x0` → 退化成 `session=1`，既没有 surface 也没有输入通道。
   它的 `LayoutParams.token` 是 `android.os.Binder`（ViewRootImpl 的窗口 token），
   不是适配层 session map 的键（Activity token）。我在 `ActivityManagerRouting` 里做了
   token 替换的尝试（代码在 `fixupNullToken`/`currentActivityToken`），但 Activity 自己的窗口
   根本不走 `IWindowSession.addToDisplay*`（只有 PopupWindow 走），所以没有可用的替换来源。
3. **JIT 在本适配层不可用**。把 `appspawn_x.cfg` 的 `APPSPAWNX_NO_JIT`/`APPSPAWNX_FORCE_INT`
   改成 0 后，App 20s 内即死。已还原为 1。**这条是关键**：既然只能解释执行，
   Lego init 的耗时就是当前首帧的主要障碍。

### 给白天的三条建议（按性价比排序）

1. **让 JIT 能用**，或给 App 侧 dex 做 AOT。这是首帧的主要瓶颈，其它都已经让路了。
2. **补 TLS**（把 conscrypt 接进适配层，或让 TTNet 的 cronet 原生栈可用），否则信息流永远是空的。
3. **修 PopupWindow 的 session 映射**（用 ViewRootImpl 的 window token 反查 Activity token），
   否则所有用 PopupWindow 的弹窗都是隐形且点不到的。

### 板子当前状态

已还原到可工作基线并验证：`FORCE_INT/NO_JIT=1`、AMR jar 为 `bak-preview` 版、
mount_count=7 / state=READY、WiFi 192.168.3.56、开屏正常、三项错误计数为 0。
`timeout_unit_time_ratio` 保留为 400（recover 脚本已同步改，原脚本备份在 `.bak-ratio20`）。

---

## 七、Supervisor 的 Lancet 线索 —— 已验证并继续往下追（09:00–10:00）

### ✅ Supervisor 的定位完全正确，补丁已生效

坐标逐项复核无误：`classes20.dex` / `Lcom/bytedance/bdauditsdkbase/privacy/hook/PrivateApiLancetImpl;`
/ `<clinit>` `code_off=0x70ba5c`（脚本里做了 assert，**MATCH**）。

反汇编看清了它为什么必炸：`<clinit>` 要建两张隐私拦截名单，中间连续读 **8 个** MediaStore 字段
（`Images/Audio/Video/Downloads` × `EXTERNAL/INTERNAL_CONTENT_URI`），stub 里一个都没有。
逐个 NOP 太碎且名单终归是残的，而该类**现在本来就整体 clinit 失败**，所以按 Supervisor 说的
把入口改 `return-void` 是严格不劣的。实现上把 4 字节的 `sget-object` 换成 `return-void; nop`，
**指令数与所有偏移/跳转目标逐字节不变**（原方法在 0x0011 已有一个 `return-void` 快速出口）。

**结果：`PrivateApiLancetImpl` 失败计数 0，主线程沿同一条路径又往前走了一步。**

### ✅ 顺手拆掉了同一条路径上的下一颗雷

```
UnsatisfiedLinkError: android.media.AudioPortEventHandler.native_setup
  at AudioManager.getDevices <- HeadsetHelperOpt.p <- MetaSDK.a
  <- ArticleMainActivity.delayInitNew <- onWaitFeedTimeout
```
`HeadsetHelperOpt.p()V` 同样是 `sget-object changeQuickRedirect` 开头、本身返回 void、
且已有早退 `return-void`，同样手法置空即可（板子没有耳机，跳过耳机探测无副作用）。
为此写了通用脚本 `night2/neutralize.py`（`<dex>:<code_off>[:label]`，可一次处理多个）。

**结果：AudioPort 致命消失，主线程不再抛任何 Java 异常。**

### ⚠️ 一个必须自我纠正的发现：我的探针污染了昨晚的测量

`wl_artfatal.log` 涨到了 **1.26 GB**。原因是我把日志 tee 的阈值设成了 WARN 以上，
而这套 libart 在**每次类加载**都打一堆 ERROR 级 `class_linker` 诊断
（`[VTLEN]` / `[IFACE-BITS]` / `[IFACE-RAW]`…），于是类加载热路径上被我塞进了
一次「open + write + close 到一个几百 MB 的文件」。已改成只 tee FATAL（阈值 7）。

**因此昨晚那句「主线程只是解释执行太慢」是被我自己的探针放大过的，不能全信。**
不过改掉之后死亡时间点没有实质变化，所以慢不是唯一因素——真正的死因见下。

### 🎯 真正杀死进程的东西已经确定（不再是推测）

appspawn 报的是 `exit with code:134`（**不是 signal**），我的 abort 钩子没触发，
说明是适配层自己的 abort hijack 直接退出。子进程日志末尾每次都是同一段：

```
[OH_WSA-relayout] session=1  requestedWH=0x1920  visibility=0
[OH_WSA-relayout] session=1  SURFACE_CHANGED (firstCreate=false wasHidden=true)
                             -> ViewRootImpl drains RT + rebuilds BBQ
[WESTLAKE-GONW]   session=36                      <- 切回 MainActivity 主窗
[G2.14bf stub] ReliableSurface::ReliableSurface   <- 刚建
[G2.14bf stub] ReliableSurface::~ReliableSurface  <- 立刻析构
ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE
               msg=drawRenderNode called on a context with no surface!
abort() hwui hijack — caller_lr=...
```

即：**那个拿不到 OH session 的 PopupWindow（`session=1`）做 relayout 时，
适配层把 MainActivity 自己的窗口 surface 拆掉了，下一帧 drawRenderNode 撞空指针，
整个进程 exit(134)。** 兜底首帧正是在这一刻被打断的。

### 两次尝试及其否定结论（省得后面重复）

1. **给 PopupWindow 换 Activity token —— 无效，且方向错。**
   加了 `WL-WSDIAG` 才看清：popup 是 `type=1000`（TYPE_APPLICATION_PANEL，子窗口），
   它携带的 `ViewRootImpl$W` **本来就是正确的** token（子窗口就该带父窗口的 IWindow）。
   替换成 Activity token 后 `createSession` 的 `tokenAddr` 确实非 0 了，
   但 QID 仍然落到 `session=1`。已把替换限制为 `type < 1000`。
2. **给子窗口摘掉 `FLAG_HARDWARE_ACCELERATED` —— 钩子点太晚。**
   日志确认标志位清掉了，但 assert 照旧：`ViewRootImpl` 在 `setView()` 就按 attrs 决定
   要不要 HardwareRenderer，等我们在 `IWindowSession.addToDisplay*` 看到时已经晚了。
   要生效得在 `WindowManagerGlobal.addView` 之前动手。

### 结论：剩下的是适配层内部问题

`OH_WSA-relayout` / `WESTLAKE-GONW` / `ReliableSurface` 这套逻辑在
`liboh_android_runtime.so` 里，Java 侧和 App 侧都够不着。**需要适配层改两件事之一：**
- 让子窗口（PopupWindow / TYPE_APPLICATION_PANEL）能拿到真正的 OH scene session；**或**
- 让 `session=1` 的 relayout **不要**去 drain/rebuild 其它窗口的 surface
  （至少不要让一个没有 surface 的窗口把主窗口的 EGL surface 拖下水）。

第二条应该更简单，而且只要它不再 abort，MainActivity 的兜底首帧就有机会画出来。

### 板子当前部署（best-known 状态，建议保留）

| 位置 | 内容 |
|---|---|
| `base.apk` | = `base.final5.apk`：ColorMatrix + conscrypt shim(classes22) + appName 守卫 + 删 libnpth + **Lancet clinit** + **HeadsetHelperOpt.p** |
| `oh-adapter-runtime.jar` | `rt-swpopup.jar`：非主线程主线程栈探针 + WSDIAG + token 修正(限 type<1000) + 子窗口摘 HW 加速 |
| `libwestlake_stackgrow.so` | FATAL-only tee 版（**不要再用 WARN 阈值那版**） |
| 回滚点 | `/data/local/tmp/oh-adapter-runtime.jar.bak-preview`、`appspawn_x.cfg.bak-forceint`、`pr03-runtime-recover.sh.bak-ratio20` |

---

## 八、Supervisor 深度架构诊断与定案：EGL_NO_SURFACE 根因及 AMR 拦截方案

### 1. 核心现场复核与死因定案
Worker 对死因现象的捕获完全准确：头条在进入 `MainActivity` ~71s 时，因 `PopupWindow`（`type=1000`，`session=1`）发生 `relayout`，触发 `ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE`，适配层 abort hijack 调用 `_Exit(134)` 导致全进程退出。

### 2. 揭示深层物理根因：SurfaceControl 会话串号（Session ID Aliasing）
深入排查 hilog 与 Native C++ 源码后，发现并非子窗口「拆掉」了主窗口 surface，而是**适配层底层发生了 NativeWindow 串号借调**：
1. **源码证据**（`android_view_SurfaceControl.cpp:239`）：
   `SC_nativeCreate` 在通过 Builder 构建 SurfaceControl 时，其绑定的会话 ID 依赖 `resolve_effective_session(0)`。该函数内部通过 `dlsym` 读取全局变量 `oh_wm_get_last_session()`。
2. **串号形成**：
   PopupWindow（`session=1`）在创建时，WMC `CreateWindow` 返回 1005 走了降级分支，**未更新**全局 `g_lastAttachedSession`。因此全局变量中依然残留着主窗口的 `session 38`。
3. **hilog 确凿实证**：
   ```text
   09:48:48.461 I OH_SurfaceControl: SC.create 'OH_Surface_1' ... sessionId=38
   09:48:48.474 I OH_GfxShim: sc_to_oh_native_window: sc=... scSessionId=38 ... -> nw=0x7f083be2e0
   09:48:48.482 I OH_EglHijack: eglCreateWindowSurface: detected ANW shim 0x7f083be2e0 ... window=0x7efbc16330
   09:48:48.482 E OpenGLWrapper: egl.eglCreateWindowSurface error.
   09:48:48.482 I OH_EglHijack: ... -> EGLSurface=0x0
   ```
4. **致命碰撞**：
   主窗口已经在 `09:48:47.879` 对 NativeWindow `0x7f083be2e0` 创建了合法的 `EGLSurface (0x7f06b67040)`。
   当 `session 1` 变为 VISIBLE 触发 relayout 时，ViewRootImpl 带着错误标记为 `sessionId=38` 的 SurfaceControl 重新构建 BBQ，BBQ 把主窗口的 NativeWindow 塞给了 RenderThread。
   RenderThread 尝试对同一个 NativeWindow 再次调用 `eglCreateWindowSurface`。EGL 驱动规范严禁对已绑定的 NativeWindow 重复建表，返回 `EGL_NO_SURFACE (0x0)`。
   Skia 在 `drawRenderNode` 遇到 `mEglSurface == EGL_NO_SURFACE` 立即触发 fatal assert，导致整个应用进程退出。

### 3. 为何 Java / AMR 侧完全能够拦截（关键认知修正）
日志中的 `[OH_WSA-relayout]` 并不是在 C++ `.so` 里打的，而是在 `WindowSessionAdapter.java` 第 580 行！
`ActivityManagerRouting.java` 已经通过 `FlagMaskHandler` 将 `sWindowSession`（即 `WindowSessionAdapter` 实例）完整动态代理。
**所有 `IWindowSession.relayout(...)` 调用都会经过 `FlagMaskHandler.invoke`！**

### 4. 推荐修复方案：在 ActivityManagerRouting 中三重切断崩溃

在 `ActivityManagerRouting.java` 的 `FlagMaskHandler.invoke` 中针对子窗口（`type >= 1000`）实施三维彻底防御：

```java
        private static int getLayoutParamsType(Object arg) {
            if (arg == null || !"android.view.WindowManager$LayoutParams".equals(arg.getClass().getName())) {
                return -1;
            }
            try {
                Field typeF = findField(arg.getClass(), "type");
                if (typeF != null) {
                    typeF.setAccessible(true);
                    return typeF.getInt(arg);
                }
            } catch (Throwable ignored) {}
            return -1;
        }

        private static void neutralizeSubWindow(Object window, Object layoutParams, String where) {
            int ty = getLayoutParamsType(layoutParams);
            if (ty < 1000) return;
            try {
                // 通过 ViewRootImpl$W.mViewAncestor 找到 ViewRootImpl，彻底销毁其 HardwareRenderer
                if (window != null) {
                    Field vf = findField(window.getClass(), "mViewAncestor");
                    if (vf != null) {
                        vf.setAccessible(true);
                        Object wr = vf.get(window);
                        if (wr instanceof java.lang.ref.WeakReference) {
                            Object vri = ((java.lang.ref.WeakReference<?>) wr).get();
                            if (vri != null) {
                                Method dm = findMethod(vri.getClass(), "destroyHardwareRenderer");
                                if (dm != null) {
                                    dm.setAccessible(true);
                                    dm.invoke(vri);
                                    System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                            + " -> destroyed HardwareRenderer on ViewRootImpl!");
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println("[WL-AMR] neutralizeSubWindow failed: " + t);
            }
        }
```

在 `FlagMaskHandler.invoke` 中应用：
```java
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String mName = method.getName();
            Object windowArg = (args != null && args.length > 0) ? args[0] : null;
            Object attrsArg = (args != null && args.length > 1) ? args[1] : null;

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    maskFlags(args[i], mName);
                    fixupNullToken(args[i], mName);
                    dropHwAccelForSubWindow(args[i], mName);
                }
            }

            // 1. 在调用前将子窗口的 HardwareRenderer 销毁，强制降级软绘
            neutralizeSubWindow(windowArg, attrsArg, mName);

            Object result;
            try {
                result = method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }

            // 2. 在 relayout 之后剥离 SURFACE_CHANGED 并释放 outSurfaceControl
            if ("relayout".equals(mName) && result instanceof Integer) {
                int ty = getLayoutParamsType(attrsArg);
                if (ty >= 1000) {
                    int original = (Integer) result;
                    result = Integer.valueOf(original & ~0x1); // 剥离 0x1 (RELAYOUT_RES_SURFACE_CHANGED)
                    System.err.println("[WL-AMR] relayout: sub-window type=" + ty
                            + " -> stripped SURFACE_CHANGED (" + original + " -> " + result + ")");

                    // 释放 outSurfaceControl (args[10])，确保 isValid()==false，避免 BBQ 错误绑定主窗 NativeWindow
                    if (args.length > 10 && args[10] != null) {
                        try {
                            Method releaseM = findMethod(args[10].getClass(), "release");
                            if (releaseM != null) {
                                releaseM.setAccessible(true);
                                releaseM.invoke(args[10]);
                                System.err.println("[WL-AMR] relayout: sub-window type=" + ty
                                        + " -> released outSurfaceControl!");
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            return result;
        }
```

```java
    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
```

### 5. 立即执行建议（一键脚本与手动流程均已就绪）
Supervisor 已将上述补丁逻辑与全链路编译/推送命令封装为一键流水线：
- **方案 A（一键自动编译部署，强烈推荐）**：
  直接在 Mac 端执行：
  ```bash
  bash /Users/mac/toutiao-repro/amr/rebuild_and_deploy.sh
  ```
  该脚本会自动运行 `patch_relayout.py`、打包传往 WSL 编译、生成 `classes.dex` 并组装 JAR，最后自动推送至板端 `/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar`。
- **方案 B（手动合入）**：
  在 `ActivityManagerRouting.java` 中手动应用上述代码，按常规流程编译打包部署。

部署完成后：
执行 `sh /data/local/tmp/run_final.sh`，观察 70s-90s 区间，确认主窗口不再被 EGL_NO_SURFACE 崩溃打断，直取 MainActivity 首帧全彩截图。

---

## 九、AMR 进阶修复：彻底静默子窗口绘制，清除 IllegalStateException

### 1. 现象复盘与反汇编深挖
在销毁子窗口的 `HardwareRenderer` 后，原先致命的 Native 层 `EGL_NO_SURFACE`（Exit code 134）已**100% 解决**。但随后子窗口（PopupWindow，`type=1000`）降级到软绘流程（`drawSoftware`），在 `Surface.unlockSwCanvasAndPost` 中抛出：
`java.lang.IllegalStateException: Surface was not locked`

### 2. 【核心破案】为什么第二次 relayout 拦截会漏掉？（attrs == null 陷阱）
对比板端日志 `adapter_child_24100.stderr` 与 AOSP 字节码反汇编，发现了关键细节：
- **第一次 relayout（71s）**：带完整 `attrs`（`type=1000`），我们的 `neutralizeSubWindow` 成功触发，返回 0。
- **第二次 relayout（72s，visibility 改变）**：AOSP `ViewRootImpl.relayoutWindow` 为节省 IPC 开销，在非全量重排时传入的 `attrs` 是 **null**（`params = null`）！
- 这导致 `getLayoutParamsType(layoutParams)` 遇到 `null` 直接返回 `-1`（`< 1000`）！
- 拦截函数误以为不是子窗口，直接放行：适配层返回了 `SURFACE_CHANGED (2)`，且未释放 `mSurface` 和 `outSurfaceControl`！
- 结果：`ViewRootImpl` 收到 `SURFACE_CHANGED`，发起重绘，进入 `drawSoftware()` 抛出 `Surface was not locked` 崩溃！

### 3. 终极解法：建立子窗口全生命周期注册表（sSubWindows）
不能仅仅依赖当次调用的 `layoutParams` 是否为 null！
1. 维护全局集合：`private static final Set<Object> sSubWindows = Collections.synchronizedSet(new HashSet<>());`
2. 在 `addToDisplay` / `addToDisplayAsUser` 或首次 `relayout` 时（此时 `attrs` 必有 `type=1000`），将 `window` 及其 `asBinder()` 永久登记到 `sSubWindows`。
3. 即使 `layoutParams == null`，只要 `window` 命中 `sSubWindows`，或通过 `window.mViewAncestor -> ViewRootImpl.mWindowAttributes.type >= 1000` 探测到是子窗口：
   - 彻底拦截 `relayout`：返回值清零（`return 0`）。
   - 释放 `outSurfaceControl`。
   - 反射释放 `ViewRootImpl.mSurface`（使其 `isValid() == false`，阻断 `draw()`）。
   - 反射调用 `ViewRootImpl.setWindowStopped(true)`，并将 `mReportNextDraw = false`。
   
这样无论是第 1 次还是第 N 次（`attrs == null`）的 relayout，子窗口都将 100% 被永久静默！

---

## 十、攻坚全面突破：今日头条主界面全彩首帧成功上屏

### 1. 验证结果汇总
在部署 `sSubWindows` 注册表与二次 `relayout` 彻底拦截方案后，实测指标如下：
- **子窗口拦截命中数**：由原先仅 2 次大幅提升至 13 次，所有后续 `attrs == null` 的 `relayout` 均被精准识别并静默（返回值从 2 转为 0）。
- **崩溃指标**：`Surface was not locked: 0`，`ASSERT FAILED: 0`，全生命周期无任何崩溃发生。
- **进程状态**：应用在 180s 全程保持 `alive=1`，持续稳定运行。
- **界面渲染产物**：
  - `t=20s ~ 60s`：启动屏（约 38KB）。
  - `t=80s ~ 120s`：成功自然过渡至今日头条 MainActivity 主界面（约 72KB）。
  - 截图已完整采集（`frames/night2/MAIN_now.jpeg`、`F_120.jpeg`）。

### 2. 首帧图像审计
经对截图详细审计，今日头条主界面整体骨架已完美呈现：
- **顶部系统与搜索区域**：红色背景顶栏，时间/电量/Wi-Fi 图标完整；搜索框内提示语「搜你想看的」，右侧「发布」与「头条AI」功能图标清晰完整。
- **分类导航栏**：横向栏目「关注、推荐、热榜、本地、视频、畅听、问答、娱乐、科技」完整上屏，红色下划线默认停留在「推荐」频道。
- **底部主导航栏**：「头条、视频、放映厅、未登录」四大 Tab 清晰呈现，且当前选中「头条」高亮。
- **内容主视口**：底板容器渲染正常，居中带有今日头条浅灰水印，等待信息流内容注入。

### 3. 当前协作重点与下一步
Cindy 正在执行控件树（View-Tree）层级采集，以进一步核实主界面各层级 View 状态。核心突破目标已圆满达成，整个渲染流水线已彻底打通。




---

## 十、✅ 收敛完成：MainActivity 首帧已渲染（2026-09-05 16:31）

### sSubWindows 注册表按第九节实现，一次通过

Supervisor 对 `attrs == null` 的判断完全正确 —— 我先只加了「post-invoke 释放
`ViewRootImpl.mSurface`」，日志里**一条 `released ViewRootImpl.mSurface` 都没打**，
正是因为第二次 relayout 的 `attrs` 为 null，`getLayoutParamsType` 返回 -1 直接漏掉。

实现要点（`ActivityManagerRouting.FlagMaskHandler`）：
- `sSubWindows`：`Collections.synchronizedSet(newSetFromMap(new WeakHashMap<>()))`
  —— 用弱键，popup 消失后不会把它的 ViewRootImpl 钉住。
- `subWindowType(window, layoutParams)`：三级判定并在首次识别时登记
  1. 本次调用带 `attrs` 且 `type>=1000` → 登记并返回
  2. `sSubWindows.contains(window)` → 命中
  3. 兜底探测 `window.mViewAncestor -> ViewRootImpl.mWindowAttributes.type`
- `neutralizeSubWindow` / relayout 返回值清零 / `invalidateSubWindowSurface`
  三处判定**全部换成 `subWindowType`**，不再看当次 attrs。
- 补上关键的一环：**post-invoke 释放 `ViewRootImpl.mSurface`**。
  `setWindowStopped(true)` 挡不住已经排上的 traversal，但 AOSP 的
  `ViewRootImpl.draw()` 开头恒有 `if (!surface.isValid()) return false;` ——
  释放 mSurface 就是打这个闸，绘制在 lock/unlock 之前就被短路，
  于是 `drawSoftware()` 的 `Surface was not locked` 不再有机会抛出。
  顺带把 `mReportNextDraw` 清掉，堵住 `(!mStopped || mReportNextDraw)` 那条缝。

### 验收结果

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 子窗口拦截次数 | 2（只有带 attrs 的那两次） | **13** |
| 第二次 relayout | 放行，返回 `SURFACE_CHANGED(2)` | **`neutralized relayoutResult (2 -> 0)`** |
| `ASSERT FAILED ... EGL_NO_SURFACE` | 每次必现 → exit 134 | **0** |
| `IllegalStateException: Surface was not locked` | 每次必现 → exit 1 | **0** |
| 进程存活 | 60–80s 必死 | **180s 全程 alive=1** |
| 截图 | 38 KB 纯白 | **71–72 KB 主界面** |

### 首帧内容（`frames/mainactivity/`）

红色顶栏 +「搜你想看的」搜索框 + 发布 / 头条AI；
频道栏 关注 / **推荐**(选中) / 热榜 / 本地 / 视频 / 畅听 / 问答 / 娱乐 / 科技；
底部导航 头条(选中) / 视频 / 放映厅 / 未登录。
信息流区域为空并显示「今日头条」占位水印 —— 这是**预期**的：适配层的 TLS 仍是
`construct-only SSLContext`，拉不到文章。**但这已经是 MainActivity 的真实首帧渲染。**

控件树（`MAINACTIVITY-viewtree.txt`，120 行）关键链路：
```
DecorView -> ... -> SSTabHost
  -> StreamViewPager -> FeedCommonRefreshView
       -> SSAdLoadingLayout (INVISIBLE)
       -> FeedCommonRecyclerView  VISIBLE 1200x1567
  -> HomePageSearchBar  VISIBLE 1200x254
  -> LoadingFlashView / TTLoadingView  VISIBLE 360x180   <- 空信息流的加载动画
```
注：控件树只能由**进程内探针**拿到，`uitest dumpLayout` 只看得到 SceneBoard 的 ArkUI 树。

### 今天这一轮一共拆掉 4 颗雷

1. `PrivateApiLancetImpl.<clinit>`（Supervisor 定位）—— 8 个 MediaStore 字段缺失
2. `HeadsetHelperOpt.p()` —— `AudioPortEventHandler.native_setup` 无实现
3. HWUI `EGL_NO_SURFACE` abort（Supervisor 修）
4. 子窗口软绘 `Surface was not locked`（Supervisor 定位 attrs==null + 本次注册表收敛）

### 仍然存在的平台缺口（不影响首帧，影响内容）

- **TLS 未实现** → 信息流为空。这是唯一挡在"有内容的主界面"前面的东西。
- PopupWindow 仍拿不到真正的 OH scene session，现在是被我们**静默**掉的；
  真正要让弹窗可见可点，还得适配层给子窗口分配 session。
- JIT 不可用（`JitCompiler::ParseCompilerOptions` 空指针），只能解释执行。

---

## 第十节：触控事件投递链路 —— 根因已定位（S1，2026-09-05）

**结论：适配层只造了输入链路的消费端，生产端整段不存在。**
不是焦点没注册，不是窗口 ID 落到 SceneBoard，也不是坐标区域没上报——
**适配层从头到尾没有向 MMI 订阅过任何事件**，MMI 投给谁都没用。

完整证据链见 `docs/INPUT_PATH_ANALYSIS.md`（全部离线静态分析，
板端只做了两次 `hdc file recv`，未启动 App、未改动任何板上文件）。

四处断点，每一处单独就足以让触控失效：

1. **`nativeRegisterInputChannel` 是 no-op。** JNI（`liboh_adapter_bridge.so` @ `0x1615b8`）
   做的是 `GetMethodID(InputChannel, "getFd", "()I")`，而这块板子 framework 的
   `android.view.InputChannel` **没有 `getFd`**（只有 `dup`/`getToken`/`copyTo`…）。
   于是打一行 `"no-op, getFd not available"` 就返回，session 表从未被填过。
   → 这解释了 `step3a createInputChannelPair OK` 与「事件收不到」为什么并存：
   **该 JNI 不抛异常，它安静地什么都没做。**

2. **`OHInputBridge::registerOHInputFd()` 零调用点。** 监听线程要 poll 的那个 fd
   字段只有它会写；`registerInputChannel()` 反而把该字段显式清零。

3. **`OHInputBridge::subscribeMmi(int)` 是一条 `ret`。** `OHWindowManagerClient::createSession()`
   调了它两次，函数体只有存参数 + 返回。`startTapControlChannel()` /
   `startTextControlChannel()` 同为空壳。

4. **适配层根本没有 MMI 订阅能力。** `liboh_adapter_bridge.so` 虽然
   `DT_NEEDED libmmi-client.z.so`，但从中导入的 **全部 11 个符号**都是设备枚举
   （`GetDeviceIds` / `GetDevice` / `InputDevice::GetName|GetType|…`）。
   没有 `SetWindowInputEventConsumer`、没有 `AddMonitor`、没有 `SubscribeKeyEvent`。
   `liboh_android_runtime.so` 一个 MMI 符号都不导入。

附带：就算前三处都修好，`monitorOHInputEvents()` 读到数据后的完整处理是
`__android_log_print("OH input event received: session=%d, %zd bytes")` —— 然后丢弃。

**消费端是完整的**：`liboh_android_runtime` 的 `OH_InputMotionWorker` 线程
`poll`+`recv` → 构造 MotionEvent → `InputEventBridge.dispatchOnMainThread(receiver, seq, ev)`
→ `InputEventReceiver.dispatchInputEvent` → ViewRootImpl 正常派发。

### 已交付的规避手段：`wl-input-pump`

从消费端重新接入。`ActivityManagerRouting` 新增守护线程，读命令文件合成 MotionEvent，
直接交给 `dispatchOnMainThread`：

```bash
hdc shell "echo 'tap 400 250' > /data/local/tmp/wl_input.cmd"
scripts/wl_input.sh swipe 600 1400 600 500 400
```

产物 `amr/build/oh-adapter-runtime.input.jar`，部署用 `scripts/deploy_and_run.sh --input`。
无命令文件时线程每 150 ms 空转，**对既有行为零影响**。

**尚未部署** —— 板端正在给 S2 攻 Cronet，本改动要覆盖
`/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar`，
会动到当前稳定基线。请总师安排板端窗口后再推。

### 要真正打通硬件触控

1. 给 `android.view.InputChannel` 补 `getFd()I` → 断点 1 消失，
   `nativeInjectTouchEvent` 这条已写好的注入路径立刻可用（但硬件触控仍不通）。
2. **实现 `OHInputBridge::subscribeMmi()`**（`SetWindowInputEventConsumer` /
   `AddMonitor` → 回调里调 `writeMotionEvent`）—— 这是唯一能让硬件触控贯通的改动，
   属于适配层源码工作，二进制补丁覆盖不了。


---

## 11. 总师统筹与夜间板端调度裁决（2026-09-05 12:00）

### 1. 战场态势通报：S2 已攻下真实新闻流上屏！
- S2（桥接层特种兵）已成功通过 C++ 补全 SQLite JNI 桥（编译 libwlsqlite.so），跑通了头条的 10 个本地数据库。
- 头条网络请求全面打通，真实拉取到了字节跳动线上新闻流！
- 凭据已落盘：`~/toutiao-bridge-dev/artifacts/wl-feed.jpeg`（1200×1920，包含央视新闻、新华社、环球网等真实资讯与封面图）。

### 2. 碰撞裁决与资源划归：S1 即刻独占真机板端
- 你之前主动停手并上报冲突的做法完全正确，体现了极高的工程纪律。
- 即刻起，真机板端资源完全移交给 S1 独占！S2 已完成本轮板端探针清理与现场恢复，转入纯离线 R8 裁剪。
- 不会再有任何人覆盖你的 `/data/pr03-74e6-portable/.../oh-adapter-runtime.jar`。

### 3. S1 的夜间攻坚重点（多页面导航、子窗口与全界面矩阵截屏）
1. 统一运行时合流：
   - 将 S2 的 loadNativeShims()（加载 libwlsqlite.so）与 startTlsBootstrap() 干净合入你的 ActivityManagerRouting.java。
   - 确保你的 VelocityTracker、输入泵（wl-input-pump）、窗口排序（topInputTarget）与 S2 的 SQLite/TLS 协同工作。
2. 验证新 Activity 启动与 Theme 修复（P0）：
   - 将你的 f0d251c（ActivityInfo.theme 恒为 0 导致启动即崩的 AXML 兜底修复）在板端真机实测。
   - 验证通过 aa start 或点击启动 com.android.bytedance.search.SearchActivity，确认不再崩溃并能正常渲染界面。
3. 真实内容点击与多页面穿透（P1）：
   - 配合已经上屏的真实新闻流，通过 scripts/wl_input.sh 模拟点击真实新闻卡片，观察是否能拉起详情页或弹窗。
   - 针对不同频道（关注、推荐、热榜、视频、小视频），收集多界面的真实高清渲染截图，存放至 frames/screens/。
4. 子窗口与弹窗解禁预研（P2）：
   - 回顾之前为了防崩溃而拦截的 sSubWindows（type >= 1000）。
   - 评估在新 Activity 跑通后，如何区分纯粹的浮层 Popup 与全屏 Dialog，逐步恢复交互。

---

## 第十一节：合流 S2 SQLite/TLS，真实信息流上屏，Theme 兜底真机验证（S1，2026-09-06）

### 1. 运行时合流完成（一个 jar，四套成果共存）

`git merge-file` 三方合并 S2 最新版（`loadNativeShims` = SQLite shim + probe + ALooper）
与我的分支，**只有一处冲突**（S2 把 `loadAlooperShim()` 重构成 `loadNativeShims()`），
解成 `loadVelocityTrackerShim(); loadNativeShims();`。

产物 `oh-adapter-runtime.all.jar`（`8887b54c…`，67207 B），真机日志确认四套同时在跑：

```
[WL-INPUT]    pump armed, watching /data/local/tmp/wl_input.cmd
[WL-VELTRACK] self-test OK: obtain/compute/recycle, xVelocity=0.0
[WL-SQLITE]   JNI_OnLoad: CursorWindow=OK SQLiteConnection=OK
[WL-THEME]    parsed …/base.apk: 780 activity themes, application theme=0x7f0900…
```

板上 S2 原 jar 已备份为 `oh-adapter-runtime.jar.s2-sqlite`。

### 2. 真实新闻流上屏（首批验收证据）

`frames/screens/20-feed-recommend-live.jpeg`，**182 KB**（此前空页只有 71 KB）：
置顶三条（央视新闻 137 评论 / 新华社 135 / 新华社 94）、环球网 1142 赞、
人民网 1024 赞、图文卡片带真实配图。

**但只有「推荐」频道有内容。** 关注 / 热榜 / 本地实测仍是
「网络异常，请稍后重试」空态（截图 22 / 21 / 23）——各频道走不同接口，属内容层，
不是输入或渲染问题。

### 3. Theme 兜底（f0d251c）真机验证：**生效，但撞到下一层**

`[WL-THEME] parsed … 780 activity themes` —— AXML 解析在板上跑通。
再 `aa start com.android.bytedance.search.SearchActivity`：

- **`Theme.AppCompat` 崩溃消失了**（此前必现的
  `themeId:0xnull` / `You need to use a Theme.AppCompat theme` 一次都没出现）
- 启动**推进到了** `SearchFragment.onCreate`，死在另一处：

```
java.lang.NoClassDefFoundError: X.BdA
  at SearchHostImpl.onSearchFragmentCreate
  at com.android.bytedance.search.SearchFragment.onCreate
Caused by: java.lang.ExceptionInInitializerError
  at com.ss.android.ad.init.PreloadTask.run          ← 广告 SDK 预加载，后台线程
Caused by: java.lang.ArrayIndexOutOfBoundsException: length=0; index=0
```

`X.BdA.<clinit>`（classes20.dex, code_off=0x5fc9f8）第二条就是
`invoke-direct BdA->c()` → `sput b:Ljava/io/File;`；`c()`（0x5fccc4）先调
`X.3CD->a(AbsApplication.getInst())` 拿基准目录再 `new File(base,"search_preload")`。
**AIOOBE 出在 `X.3CD.a` 里** —— 典型是 `getExternalFilesDirs()[0]` /
`getExternalCacheDirs()[0]` 这类适配层返回**空数组**的 API。

关键点：`<clinit>` 一旦抛异常，该类被永久标记为 erroneous，
**之后任何使用都变成 `NoClassDefFoundError`**。所以真凶是那个**后台广告预加载任务**，
搜索页只是第一个撞上尸体的人。

### 4. 视频/畅听频道崩溃：WebView

`tap 560 213`（视频频道）会打死进程：

```
android.view.InflateException: layout/crp:
  Error inflating class com.ss.android.article.common.PullToRefreshSSWebView
  at BaseBrowserFragment.realInflateView <- ArticleBrowserFragment.onCreateView
```

这两个频道是 WebView 承载的，适配层没有 WebView。**采样时要避开视频与畅听。**

### 5. 详情页

信息流已是真内容，`tap 400 590` 点条目：无新窗口（`WL-WIN` 计数 0）、无异常、
进程存活、像素几乎不变。与「未登录 / 搜索框」同一形态，**未定位**。

### 6. **Theme 兜底真机验收通过：新 Activity 第一次真正上屏**

`aa start …TeenSettingActivity`（纯原生设置页，无 WebView 无广告）：

| | 修复前 | 修复后 |
|---|---|---|
| `[WL-WIN] addToDisplay` | 0 | **1**（`type=1 BASE_APPLICATION`）|
| 进程 | `alive=0` 启动即崩 | **`alive=1` 全程存活** |
| 屏幕 | 81 KB 桌面 | **69.5 KB 完整设置页** |

截图 `frames/screens/24-second-activity-settings.jpeg`：返回箭头、标题「设置」、
六个条目、底部热线电话与版权行全部渲染。**跨 Activity 跳转链路打通。**

搜索页拿不到，是因为它额外撞上 `X.BdA` 那具尸体（见上）；
详情页多为 WebView 承载，撞的是 WebView 缺失。两者都不是 Theme 问题了。

### 下一步建议（按性价比）

1. **查 `X.3CD.a` 的空数组来源**并在适配层补非空返回——它同时挡着搜索页和广告 SDK，
   而且是平台级缺口（一个空数组毒死一个类，波及面不可预测）。
2. 其余频道空态归内容层（S2）。
3. WebView 缺失是独立的大缺口，不建议在本阶段展开。

---

## 第十二节：SearchActivity 打通（S1，2026-09-06 补）

### 根因链（三层）

```
SearchActivity 起不来
  └─ NoClassDefFoundError: X.BdA              ← 类被永久标记 erroneous
       └─ ExceptionInInitializerError          ← <clinit> 曾经失败过
            经 com.ss.android.ad.init.PreloadTask（广告 SDK，后台线程）触发
            └─ ArrayIndexOutOfBoundsException: length=0; index=0
                 X.BdA.<clinit> -> c() -> X.3CD.a() -> X.4Yx.c()
                 = Environment.getExternalStorageState()
                 = AOSP 内部 getExternalDirs()[0]
                 ← 这块板子没有任何外部存储卷，数组为空
```

**关键机理**：`<clinit>` 一旦抛异常，ART 把该类标记为 erroneous 且**永不重试**，
之后任何使用都变成 `NoClassDefFoundError`。所以真凶是那个后台广告预加载任务，
搜索页只是第一个撞上尸体的人——从搜索页的栈上完全看不出来源。

### 修法：让它别去问 Environment（第 12 号补丁）

`X.3CD.a()` **自带正确兜底**：状态不是 `"mounted"` 就走 `Context.getCacheDir()`。
等宽替换 8 字节（classes15.dex @0x538C28）：

```
invoke-static X.4Yx.c ; move-result-object v1     71 00 20 51 00 00 0c 01
    ->  const-string v1, "" ; nop ; nop            1a 01 00 00 00 00 00 00
```

`"".equals("mounted")` 为假 → 现成分支直达 `getCacheDir()`。
产物 `prebuilts/base.final8.apk`（`5b2a9be9…`）。

### 真机验收

| | 修复前 | 修复后 |
|---|---|---|
| `[WL-WIN] addToDisplay` | 0 | **1**（`type=1 BASE_APPLICATION`, `adjust=pan`）|
| 进程 | `alive=0` 启动即崩 | **`alive=1` 全程** |
| 屏幕 | 81 KB 桌面 | **43.5 KB 真实搜索页** |

截图 `frames/screens/25-search-activity.jpeg`：返回箭头、搜索输入框（光标 +
「搜你想看的」）、右上角红色「搜索」按钮。

### 视频频道：条件崩溃

- 信息流**为空**时切过去 → 正常渲染空态、不崩（截图 26-channel-video）
- 信息流**已加载真实内容**时切过去 → 打死进程：
  `InflateException … Error inflating class …PullToRefreshSSWebView`

`android.webkit.WebView` 类在 framework.jar 里**是存在的**（classes.dex/2/3/4 都有），
所以不是"没有 WebView 类"这么简单，失败点更深（很可能是
`WebViewFactory.getProvider()` 找不到 provider）。**未定位，留给后续。**

### 信息流内容是不稳定的

同一套代码，有的轮次 141–182 KB 真实新闻，有的轮次全程 71 KB 空页。
采样时要看 size 判断，不能假定必然有内容。属内容层（S2）。

---

## 第十三节：TLS 裁剪版 / ANDROID_ID / WebView 三线结果（S1，2026-09-06）

### 1. S2 极速 TLS 裁剪版：已在位，无需我部署

板上 `/data/local/tmp/wl-tls.jar` 的 md5 已经等于 `out/wl-tls-min.jar`
（`897411f9…`，652 978 B）——S2 自己推过了。实测冷启动 **首帧 84 s**，
信息流 140–180 KB 真实内容。

### 2. ANDROID_ID：假设成立，但**不是**热榜/关注空态的原因

不靠猜，加了 `fingerprint` 探针命令直接问（`echo fingerprint > wl_input.cmd`）：

```
Settings.Secure android_id  -> null          ← 确认为空
Build.SERIAL / MODEL / BRAND / DEVICE / FINGERPRINT / MANUFACTURER  -> 均有值
TelephonyManager.getDeviceId / getSubscriberId / getSimSerialNumber
    / getNetworkOperator / getSimOperator                            -> 均有值不抛
```

注入点选**已经属于我们的** `IActivityManager.getContentProvider()`：
`Settings.Secure` 走 "settings" authority，provider 正是从这里取的。
把返回的 `ContentProviderHolder.provider` 用 `Proxy` 包一层，拦 `call()`——
按**载荷**匹配（参数里出现 `"android_id"`）而不是按参数位置，
因为 `IContentProvider.call` 的签名跨 API 级别变过好几次。
返回固定合法 16 位十六进制 `a1b2c3d4e5f60718`（必须稳定，device_register 不接受每次变的值）。

验证：

```
[WL-FP] settings provider wrapped for android_id
[WL-PROBE] Settings.Secure android_id -> OK: a1b2c3d4e5f60718
```

**但热榜 77401 B、关注 80049 B，与注入前逐字节量级相同，仍是「网络异常」空态。**
所以 ANDROID_ID 为空是真的、修好了也是真的，**但它不是这两个频道空态的原因**。
下一步要查的是这两个频道各自的接口返回，而不是继续补指纹。

### 3. WebView：**不是缺 provider**，是头条自带的 TTWebView 内核没起来

加 `webview` 探针命令直接问框架（`echo webview > wl_input.cmd`）：

```
WebViewFactory.getProvider() -> OK: com.bytedance.lynx.webview.glue.TTWebProviderWrapper
new WebView(context)        -> NullPointerException
  Attempt to invoke interface method 'android.webkit.WebViewProvider …'
  at com.bytedance.lynx.webview.glue.TTWebProviderWrapper.createWebView
  at android.webkit.WebView.ensureProviderCreated
  at android.webkit.WebView.setOverScrollMode
  at android.view.View.<init>
```

关键：**`getProvider()` 是成功的**。framework.jar 里是完整的 AOSP `WebViewFactory`
（`getProvider` / `getUpdateService` / `loadWebViewNativeLibraryFromPackage` 全在），
而它解析出来的 provider 是**头条自己塞进去的 TTWebView**
（`com.bytedance.lynx.webview.glue.TTWebProviderWrapper`）。
NPE 出在 TTWeb 自己的 `createWebView` 里——它包的那层真实内核是 null，
即 **TTWebView 的 chromium 核没初始化成功**。

板端佐证：没有任何 Android WebView provider 包
（`/system/app/*WebView*`、`/data/app/*webview*` 全空），
只有 OH 自己的 ArkWeb（`/system/lib64/libwebview_ani.z.so`、`libwebview_common.z.so`）。

**所以「提供极简 WebViewProvider 门面桩」这条路对不上症**：门面已经有了（TTWeb 占着），
坏的是它下面那一层。要么让 TTWeb 的内核起来（要查它 dlopen 什么、缺什么符号），
要么把 `WebViewFactory` 的 provider 顶替成我们自己的实现——但那意味着要实现
`WebViewProvider` 的整套接口（几十个方法），性价比很低。

**结论：优先打通非 WebView 承载的二级页面**（如已验收的 SearchActivity、设置页），
WebView 这条线建议单独立项。


---

## 第十二节：四靶标专项修复（S1，2026-09-06）

### 靶标 D —— WebView 闪退：**已修复**

`WebViewFactory` 门面层加保护性代理。板端探针先确认了症状不是"缺 provider"：

```
[WL-PROBE] WebViewFactory.getProvider() -> OK: com.bytedance.lynx.webview.glue.TTWebProviderWrapper
[WL-PROBE] new WebView(context) -> NullPointerException at TTWebProviderWrapper.createWebView
```

`getProvider()` 是成功的，为 null 的是 TTWeb **内部**的 chromium 内核。NPE 从
`View.<init>` 逃出来，所以任何含 `PullToRefreshSSWebView` 的布局一 inflate 就把进程带走。

做法：把 provider 包一层 `Proxy`，`createWebView` 抛异常时改为交回一个惰性
`WebViewProvider`；接口类型的返回值递归代理，避免把 NPE 推迟到
`getViewDelegate()` / `getScrollDelegate()`。

**一个实现细节值得记**：guard 必须在**主线程**安装。后台线程调
`WebViewFactory.getProvider()` 会抛，主线程调同一个方法正常返回。

实测对比（日志按 pid 钉死——板上同时跑着别的应用，`ls -t` 会取到
uid 20010058 的日志）：

| 动作 | 修复前 | 修复后 |
|---|---|---|
| `new WebView(context)` | NPE | **OK: `android.webkit.WebView{51f3024 VFE.HV...}`** |
| `tap 560 213`（视频频道）| `InflateException` → `alive=0` | 画面 179774→**76707 真正切换**，`alive=1` |
| `tap 400 590`（详情卡片）| 闪退 | `alive=1` |

截图：`26_fixed_webview_video.jpeg` / `26_fixed_webview_detail.jpeg`。

### 靶标 A —— 图片灰框：**是加载时序，不是测量失真**

同一张卡片的前后对比即可证明：27 号图里环球网/人民网台标是灰圆、郭富城卡片是大灰框；
`20_fixed_image.jpeg` 里**台标与真实照片都完整加载**。给足时间灰框自行消失，
连续 4 次采样帧稳定在 179 KB（灰框态是 141 KB）。

**未观察到"横向挤压变形"**——照片人物比例正常。所以没有去改 DecorView 的
DisplayMetrics 注入：那会是在没有缺陷的地方动刀。总师已确认冷启动 28 秒
导致的 CDN 握手超时将由启用 JIT 从系统级解决。

### 靶标 B / C —— 搜索页死白 与 频道"网络异常"：**同一个根因**

搜索页现在能正常拉起并上屏（`wlwin=1`、`alive=1`、无 `X.BdA`），控件树说明了一切：

```
SearchAutoCompleteTextView  VISIBLE  898x80  abs=[167,68][1065,148]   ← 顶栏正常
RecyclerView                INVISIBLE        abs=[0,162][1200,1920]   ← 建议/热搜列表，无数据
TTLoadingViewV2             GONE
TTNoButtonErrorViewV2       GONE                                      ← 连错误态都没显示
```

列表尺寸 1200x1758 是对的，**不是测量问题，是数据从未到达**。

根因在设备身份：

```
shared_prefs 中 device_id / install_id：一条都没有（设备从未注册成功）
api.toutiaoapi.com 响应：{"base_resp":{"status_code":400,"status_message":"invalid user"}}
```

没有有效 did/iid，所有实时内容接口一律 400。**推荐频道之所以有内容，是因为它渲染的是
`news_article.db`（909 KB）里的缓存**，不是实时拉取——这一轮里整个进程只建立了
2 条 TLS 连接，却渲染出了完整信息流。

所以 B 和 C 都不是 UI 层能修的：要么把设备注册打通（网络/指纹层），
要么承认它们是缓存之外的能力边界。我没有在 UI 层做假修复。

**补充铁证（响应体是 gzip，之前只读到了那条未压缩的 65 字节）**：把一轮完整抓包
解压后，`api.toutiaoapi.com` 在同一次会话里连续返回 5 次

```
{"base_resp":{"status_code":400,"status_message":"invalid user"}}
```

而同一进程里 `isaas.ecombdapi.com` 返回 `{"status_code":0,"status_message":"success"}`。
**网络栈与 TLS 本身没问题**，是头条内容接口按身份拒绝。该轮连接的域名分布：
`api.toutiaoapi.com` ×33、`gecko.zijieapi.com` ×14、`abtest-ch.snssdk.com` ×7、
`lf-webcast-gr-sourcecdn` ×5、`isaas.ecombdapi.com` ×5、`ib.snssdk.com` ×2。

### 板端稳定性观察（影响采样效率，非本次改动引入）

反复出现"冷启动到 50–80 秒后进程自行消失"。与内存高度相关：重启后空闲内存 5.0 GB
时启动稳定，连跑数轮后掉到 0.95 GB 就开始失败。faultlog 里最新的是
`processdump` 自身崩溃（转储器在转储时挂了），所以真正的崩溃栈没留下来。
**规避办法：采样前重启板子**（`reboot` 后 `pr03-boot-recovery.txt` 会回到
`state=READY`，无需手工恢复挂载）。

### 详情页

带 guard 从有内容的推荐流点条目，两次尝试都因上述进程自行消失而未能取到画面。
**未取得详情页截图，如实记录。**

### 靶标 C 补充铁证：响应体是 gzip，之前只读到了那条未压缩的 65 字节

把一轮完整抓包解压后，`api.toutiaoapi.com` 在同一次会话里连续返回 5 次：

```
{"base_resp":{"status_code":400,"status_message":"invalid user"}}
```

而同一进程里 `isaas.ecombdapi.com` 返回 `{"status_code":0,"status_message":"success"}`。
**网络栈与 TLS 本身没问题**，是头条内容接口按身份拒绝。该轮域名分布：
`api.toutiaoapi.com` ×33、`gecko.zijieapi.com` ×14、`abtest-ch.snssdk.com` ×7、
`lf-webcast-gr-sourcecdn` ×5、`isaas.ecombdapi.com` ×5、`ib.snssdk.com` ×2。

### 板端稳定性观察（影响采样效率，非本次改动引入）

反复出现「冷启动到 50–80 秒后进程自行消失」。与内存高度相关：重启后空闲内存
5.0 GB 时启动稳定，连跑数轮掉到 0.95 GB 就开始失败。faultlog 里最新的是
`processdump` **自身**崩溃（转储器在转储时挂了），所以真正的崩溃栈没留下来。
规避办法：**采样前重启板子**（`reboot` 后 `pr03-boot-recovery.txt` 自动回到
`state=READY`，不需要手工恢复挂载）。

### 详情页：未取得

带 WebView guard 从有内容的推荐流点条目，两次尝试都因上述进程自行消失而没能取到画面。
**如实记录：详情页截图未取得。**

---

## 第十三节：交互泵命中测试修复 + 五界面归档（S1，2026-09-06）

### 1. `click` 命令：绕开触摸层直接 `performClick()`

信息流条目此前无论怎么点都不导航。新增 `click x y`：在视图树里找命中点下的
可点击视图，直接调 `performClick()`。

**第一版有个我自己的 bug**，值得记下来：`SSTabHost` 的**最后一个子节点是一个
全屏、无子节点的空 `FrameLayout`**，覆盖整个信息流。按"最后一个子节点优先"
下探的朴素命中测试撞上它就停了，永远到不了下面的条目。命中链日志坐实：

```
[0] android.widget.FrameLayout clk=false @0,0 1200x1920   ← 空浮层，没有子节点
[1] SSTabHost  [2] LinearLayout  …  [7] DecorView
```

改成**全子树收集所有包含该点的视图（深度优先）、再优先挑可点击的**之后：

```
[WL-INPUT] click 400.0,620.0 handled by
           com.ss.android.article.base.feature.feed.widget.FeedItemRootLinerLayout (0 levels up)
```

**条目根其实是 clickable 的**——此前 dump 读到的 `clk=False` 是过期的行。
监听器确实被触发了。

### 2. 但详情页仍然不上屏

`performClick` 返回 true（监听器跑完了），可 `addToDisplay` 计数仍为 0、画面不变。
与 `aa start` 那条路殊途同归：**点击链路通了，新窗口起不来。**

至此详情页的四层已知障碍：

| 层 | 现象 | 状态 |
|---|---|---|
| 1 | `createWebView` NPE → `InflateException` → 进程死 | ✅ WebView 防崩代理 |
| 2 | `WebSettings.getUserAgentString()` on null（`preCreateWebView`）| ✅ dex 中和 classes21 |
| 3 | `emoticon/emoticon.conf` 缺失被 Mira 吞掉，`setContentView` 未完成 | ✅ dex 中和 classes6 |
| 4 | 点击/`aa start` 都触发了，但**没有任何 `addToDisplay`** | ❌ 未解决 |

第 4 层无异常、无崩溃、进程存活，纯粹"起不来窗口"。

### 3. 五界面归档到 `frames/screens/`

| 界面 | 文件 | 状态 |
|---|---|---|
| 推荐频道流 | `30-feed-recommend-final.jpeg` | ✅ 真实内容 |
| 视频频道流 | `31-video-channel-final.jpeg` | ✅ 切换成功、进程存活（此前必崩）|
| 搜索主界面 | `32-search-activity-final.jpeg` | ⚠️ 窗口上屏、顶栏正常，内容区空 |
| 热榜频道流 | `21-feed-hotlist.jpeg` | ⚠️ 切换成功，服务端空态 |
| 详情页 | — | ❌ **未取得** |

热榜与搜索内容区为空是同一根因：`shared_prefs` 无 `device_id`/`install_id`，
`api.toutiaoapi.com` 一律 `400 invalid user`。这两格的"空"是服务端返回的真实状态，
不是渲染缺陷——我没有伪造内容。
