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
- `[01:54:11]` 总行数: 5827 | 最新角色: user | 最新执行工具: Bash: cd ~/toutiao-repro && cat > /tmp/whichmethod.py <<'PY'
import zipfile, struct, sys
exec(open('/tmp/dexcore.py').read())
 | 工具返回: MATCH Lcom/ss/android/article/base/feature/main/ArticleMainActivity;->delayInit()V  [virtual] code_off=0x7d63ec
--- cros | 最新说明: Board is running `base.final6.apk` — so my verified screenshot **was** with the supervisor's build.
- `[01:42:15]` 总行数: 5790 | 最新角色: assistant | 最新执行工具: Bash: cd ~/toutiao-repro && cat >> README_REPRODUCE.md <<'EOF'

---


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
