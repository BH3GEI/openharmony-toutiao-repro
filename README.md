# 今日头条 · OpenHarmony 首帧复现

在 **OpenHarmony 6.1.0.31 / DAYU200 (aarch64)** 上，通过 Westlake **a2oh** Android 兼容适配层
把 **今日头条 (com.ss.android.article.news)** 跑到 `ArticleMainActivity` **首帧全彩渲染**。

![MainActivity 首帧](frames/mainactivity/MAINACTIVITY-firstframe.jpeg)

顶栏搜索框、频道栏（关注 / **推荐** / 热榜 / 本地 / 视频 / 畅听 / 问答 / 娱乐 / 科技）、
底部导航（头条 / 视频 / 放映厅 / 未登录）全部渲染。

> **信息流区域为空是预期结果**，不是缺陷。适配层的 TLS 目前是
> `construct-only SSLContext`（日志原文 `TLS shim: no real networking (construct-only SSLContext on OH)`），
> 任何 HTTPS 请求都发不出去，所以拉不到文章。控件树证明布局本身完整：
> `FeedCommonRecyclerView` 已 `VISIBLE 1200x1567`，`LoadingFlashView` 正在转圈。

真实控件树见 [`frames/mainactivity/MAINACTIVITY-viewtree.txt`](frames/mainactivity/MAINACTIVITY-viewtree.txt)（120 行，进程内探针 dump）。

---

## 1. 技术原理：挡在首帧前的致命崩溃

全部修复都**不需要重烘 boot image，也没有 patch `libart.so`**。

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | `MUSL: create socket failed for family: 2, errno: 1` | OH appspawn 的 `SetInternetPermission` 按 **AccessToken** 判定（不是 GID，也不是 BPF）。适配层装包器一条 Android 权限都没映射，`permission_state_table` 为空 → 装上 seccomp 过滤器让 `socket(AF_INET)` 返回 EPERM | 直接往 `access_token.db` 补 INTERNET 授权行（`scripts/grant_internet.sh`），避免 `bm install -r` 重建 bundle 目录 |
| 2 | `NoSuchMethodError: set([F)V in ColorMatrix` → `AsyncImageView.<clinit>` 失败 → 信息流布局无法 inflate | `adapter-mainline-stubs.jar` 的 `ColorMatrix` 桩缺 `set(float[])`，而它在 BCP，加方法必须重烘 boot image | 改 **App dex**：`AsyncImageView.<clinit>` 里的 `ColorMatrixColorFilter` 置 null（该字段只是夜间灰度滤镜） |
| 3 | `NullPointerException: No platform found on Android` → 所有 TTNet `DoConnect` 失败 | okhttp 的 `Platform` 检测两条路都要 `com.android.org.conscrypt.SSLParametersImpl`，而适配层 BCP **完全没有 conscrypt** | 关键点：它走 **Mira 的 `ClassLoaderHelper`（App 类加载器）**，不查 BCP。追加一个 `classes22.dex` 放空类即可 |
| 4 | 每 ~18s 必崩 `SIGSEGV @ 0x0`，`ICUWrapper::ICUWrapper()+100` | `libtttext_lite.so` 扫 `/system/usr/icu` 得到 OH 的 `icudt74l.dat` → 版本 74 → dlsym `ubrk_open_74`，而适配层 Android 侧 ICU 是 **72** | `libwlicu.so`：15 个符号 `b <name>_72` 裸尾跳转；等长改 tttext 的 dlopen 名 |
| 5 | 每 ~25s 必崩，`libnpth.so` `__stack_chk_fail` | NPTH 注册 bionic 形状的 fdsan 回调，musl `close()` → `fdsan_close_with_tag` 调用时栈约定不符 | 从 apk 里**删掉** `lib/arm64-v8a/libnpth.so`（改名无效：`app_librarian` 每次启动从 apk 重新解包） |
| 6 | `IllegalArgumentException: appName is empty` 打死主线程 | AppLog `BdInstallImpl.init` 校验 appName 非空 | NOP 掉 `X/4Li.a()` 里的 `if-nez`（TLS 是桩，分析上报本来也发不出去） |
| 7 | `NoSuchFieldError` → `PrivateApiLancetImpl.<clinit>` | 该 `<clinit>` 连读 **8 个** `MediaStore.*_CONTENT_URI`，桩里一个都没有 | 入口改 `return-void` |
| 8 | `UnsatisfiedLinkError: AudioPortEventHandler.native_setup` | `HeadsetHelperOpt.p()` → `AudioManager.getDevices()` 依赖未实现的 framework JNI | 入口改 `return-void`（板子没有耳机） |
| 9 | `ArticleMainActivity.delayInit()` 上还有一串同类地雷 | `onWaitFeedTimeout → delayInit` 会连续踩适配层空缺 | 入口改 `return-void` |
| 10 | `ASSERT FAILED [skia] mEglSurface == EGL_NO_SURFACE` → `abort() hwui hijack` → **exit 134** | PopupWindow (`TYPE_APPLICATION_PANEL`) 在适配层拿不到 OH scene session，退化到 `session=1`（无 surface、无输入）。它 relayout 时把 **MainActivity 自己窗口的 surface** 也拆了 | 见下 |
| 11 | `IllegalStateException: Surface was not locked` → **exit 1** | 关掉硬件渲染后退到 `drawSoftware()`，而那个 Surface 没有 buffer producer，`lockCanvas()` 从未真正上锁 | 见下 |

### 第 10/11 项的收敛：子窗口注册表 + 释放 `mSurface`

在 `ActivityManagerRouting`（代理 `IWindowSession`）里对子窗口彻底静默绘制。有两个坑：

**坑一：`attrs == null`。** `ViewRootImpl.relayoutWindow()` 只在 LayoutParams *变化时*才传 attrs，
之后每次 relayout 都传 `null`。所以按当次 `attrs.type` 判断会漏掉第 2..N 次 —— 第一次拦下了，
第二次放行，适配层返回 `SURFACE_CHANGED(2)`，ViewRootImpl 重绘，主线程当场崩。
解法是**子窗口注册表**（弱键，popup 消失后不钉住 ViewRootImpl）：

```java
sSubWindows = Collections.synchronizedSet(newSetFromMap(new WeakHashMap<>()));

subWindowType(window, attrs):
    attrs != null && type >= 1000                                  -> 登记并返回
    sSubWindows.contains(window)                                   -> 命中（attrs==null 也认得）
    window.mViewAncestor -> ViewRootImpl.mWindowAttributes.type     -> 兜底探测
```

**坑二：`setWindowStopped(true)` 挡不住已经排上的 traversal。**
但 AOSP `ViewRootImpl.draw()` 开头恒有 `if (!surface.isValid()) return false;` ——
所以**在真实调用之后**反射释放 `ViewRootImpl.mSurface`，绘制就在 lock/unlock 之前被短路。
必须放在调用之后，否则适配层会把 Surface 换回来。

效果：

| | 修复前 | 修复后 |
|---|---|---|
| 子窗口拦截次数 | 2（只有带 attrs 的两次） | **13** |
| `ASSERT ... EGL_NO_SURFACE` → exit 134 | 每次 | **0** |
| `Surface was not locked` → exit 1 | 每次 | **0** |
| 进程存活 | 60–80s 必死 | **180s 全程存活** |
| 截图 | 38 KB 纯白 | **71–72 KB 主界面** |

---

## 2. 环境要求

| 项 | 值 |
|---|---|
| 板子 | DAYU200 (RK3568 类)，aarch64 |
| 系统 | OpenHarmony **6.1.0.31**（架构冻结，不要升级） |
| 适配层 | Westlake a2oh，`appspawn-x` ondemand + sealed child plugin，route-a generation `74e6f759…8ce16d` |
| 挂载 | `pr03-74e6-portable` 7 个 bind mount 必须 `state=READY`（`sh /data/pr03-74e6-portable/runtime/pr03-runtime-recover.sh`） |
| ART | 强制解释执行：`APPSPAWNX_FORCE_INT=1` / `APPSPAWNX_NO_JIT=1`。**不要关**——本适配层的 JIT 会在 `JitCompiler::ParseCompilerOptions` 空指针崩溃 |
| 主机 | `hdc` 能连到板子；源码构建另需 JDK 11+、`d8`、OpenHarmony native SDK |

> 板子需要联网（首帧本身不依赖网络，但便于对照）；WiFi 连上后 NTP 会把时钟拨正，
> 这块板子没有 RTC。

---

## 3. 快速验证（用 Release 预编译产物）

```bash
git clone https://github.com/BH3GEI/openharmony-toutiao-repro
cd openharmony-toutiao-repro

# 1. 拉预编译产物（apk / jar / .so，都在 GitHub Release）
scripts/fetch_prebuilts.sh

# 2. 确认板子在线且适配层挂载就绪
hdc list targets
hdc shell "tail -3 /data/service/el1/public/appspawnx/pr03-boot-recovery.txt"   # 期望 state=READY

# 3. 部署 + 冷启动 + 抓帧（约 4 分钟）
scripts/deploy_and_run.sh
```

产物落在 `out/F_<t>.jpeg`。判读方式：

- `~38 KB` = 空白窗口（还在起）
- `~70 KB` = **MainActivity 已渲染**
- 预期 `t=60s` 前后为白，`t=80s` 起出主界面（纯解释执行，慢但会到）

想自己打 apk（**推荐**，避免下载 132 MB）：

```bash
# 从你自己那份干净的 base.apk 生成，10 秒
python3 patches/patch_base_apk.py /path/to/base.apk -o prebuilts/base.final6.apk
scripts/deploy_and_run.sh
```

`patch_base_apk.py` 会先校验输入 sha256；用参考底包时，输出与本仓库发布的
`base.final6.apk` **逐字节一致**（可用 `--verify` 自证）。

---

## 4. 全量源码构建

```bash
export JAVA_HOME=/path/to/jdk17
export D8=/path/to/android-sdk/build-tools/34.0.0/d8
export OHOS_NDK=/path/to/ohsdk/linux/native-x/native      # 含 llvm/ 与 sysroot/

# 适配层运行时（ActivityManagerRouting）
amr/build_amr.sh                       # -> amr/build/oh-adapter-runtime.jar

# native shim
native/stackgrow/build.sh              # -> native/stackgrow/build/libwestlake_stackgrow.so
hdc file recv /system/android/lib64/libicuuc.so native/icushim/prebuilt/libicuuc.so
native/icushim/build.sh                # -> native/icushim/build/libwlicu.so

# conscrypt 存在性 shim（会刷新 patches/prebuilt/classes22.dex）
shims/conscrypt/build.sh

# App apk
python3 patches/patch_base_apk.py /path/to/base.apk -o prebuilts/base.final6.apk

# libtttext_lite.so 的等长改名（把 dlopen 目标指向 libwlicu）
hdc file recv /data/app/el1/bundle/public/com.ss.android.article.news/android/lib/arm64-v8a/libtttext_lite.so /tmp/
python3 patches/tools/patch_tttext.py     # 见脚本头部说明

# 部署
scripts/deploy_and_run.sh --jar amr/build/oh-adapter-runtime.jar
```

---

## 5. 仓库结构

```
patches/
  patch_base_apk.py        一键：干净 base.apk -> base.final6.apk（6 处改动，可 --verify）
  prebuilt/classes22.dex   conscrypt 存在性 shim（648 B，已编译）
  tools/                   dex 解析/反汇编/按字符串反查/方法定位/单点补丁脚本
amr/
  src/ stubs/ build_amr.sh 适配层 IActivityManager 替换实现（含子窗口注册表）
  prebuilt/                base jar + classes-retarget.dex（字符串已改名，不可再生）
native/stackgrow/          ART 故障捕获 + musl 栈/fdsan 修补（LD_PRELOAD）
native/icushim/            ICU 74->72 转发桥
shims/conscrypt/           空 SSLParametersImpl 源码
scripts/                   deploy_and_run / run_capture / keepawake / grant_internet / fetch_prebuilts
frames/mainactivity/       首帧截图 + 120 行真实控件树
docs/ROOT-CAUSES.md        全部根因的完整技术记录
docs/NETWORK_STACK_ANALYSIS.md  网络栈静态分析：TTNet/OkHttp/Cronet 三条路径与 TLS 打通靶标
docs/WORKLOG.md            攻坚过程流水（含被证伪的路线，避免重走）
```

### 关键排障经验

- **native 崩溃日志在 `/data/log/faultlog/temp/cppcrash-<pid>-*`**，不在 `faultlogger/`。
  ICU 和 npth 两个根因都是从这里一眼看出来的。
- **libart 的 `LOG(FATAL)` 文本在 hilog / stderr / faultlog 里都看不到**（它的 liblog 在
  `sealed.child` namespace 内解析）。要拦 `__android_log_write_log_message` /
  `__android_log_logd_logger` 才拿得到正文 —— `native/stackgrow` 已实现。
  注意阈值要设 FATAL：设成 WARN 会因为这套 libart 每次类加载都打 ERROR 级诊断而写出 GB 级日志，
  反过来把启动耗时测歪。
- **控件树只能靠进程内探针拿**，`uitest dumpLayout` 只看得到 SceneBoard 的 ArkUI 树，
  看不到 Android view。
- ART 在 `attachApplication` 之前 abort 会让 AMS 的 `AppRunningRecord` 永久卡在 BEGIN，
  之后 `aa start` 全部静默丢弃（`aa force-stop` 因 `kill(pid)` ESRCH 拒绝），只能重启整机。

### 已证伪的路线（别重走）

- **imageless ART**：移走 boot image 后 ART 确实会 fallback，但死在
  `InitWithoutImage: Class mismatch for Ljava/lang/String;`。适配层的 libcore/art 配对只在预烘 image 下成立。
- **patch libart 放宽 BCP 校验**：无意义。boot image 里烘的是**类对象本身**，
  `ColorMatrix` 仍然没有 `set([F)V`。
- **在 x86_64 模拟器上重烘 boot image**：其端上 `dex2oat` 没有 arm64 后端
  (`Unknown InstructionSet: Arm64`)。原始配方用的 `dex2oat64-exp` 是**主机侧**构建。

---

## 6. 仍未解决

1. **适配层没有 TLS** → 信息流永远为空。这是唯一挡在「有内容的主界面」前面的东西。
   下一步的靶标分析见 [`docs/NETWORK_STACK_ANALYSIS.md`](docs/NETWORK_STACK_ANALYSIS.md)：
   App 自带 `libsscronet.so` + `libttboringssl.so`，走通 Cronet 比移植 conscrypt 短得多。
2. **PopupWindow 拿不到真正的 OH scene session** —— 现在是被**静默**掉的，
   弹窗不可见也点不到。要真正可用，需要适配层给子窗口分配 session。
3. **JIT 不可用**（`JitCompiler::ParseCompilerOptions` 空指针），只能解释执行，
   所以首帧要 60–80 秒。

---

## 7. 授权与合规

本仓库**不包含**今日头条的原始 APK。`patches/` 里只有**差分补丁与偏移**，
需要你自备一份合法获得的 `base.apk`（sha256 见 `patch_base_apk.py`）。

Release 中的 `base.final6.apk` 与 `oh-adapter-runtime.jar` 属于第三方/内部产物，
仅供本项目的复现验证，请勿再分发。仓库自身的脚本与源码按 MIT 使用。
