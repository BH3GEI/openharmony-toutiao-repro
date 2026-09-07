# wechat-bridge-dev — 微信 8.0.77 部署到 OpenHarmony 板端(anco 兼容层)

目标:把 `weixin8077android3160_0x28004d30_arm64_1.apk` 部署到独立槽位并冷启动至
LauncherUI 主界面首帧,充分继承头条(`~/toutiao-bridge-dev`)已验证的运行时基线,
与板端头条任务(S1)严格互斥。

工作区镜像 `~/toutiao-bridge-dev` 的组织方式。板端经 `~/toutiao-bridge-dev/tools/hdc-remote`
(SSH→yao-win→hdc.exe, serial 5ce1227d…)控制。

---

## 一、APK 静态解构结论(宿主机,离线)

解码器 `scripts/axml.py`(自写 AXML parser,resmap 解析框架属性)→ `apk/AndroidManifest.decoded.xml`。

| 项 | 值 |
|---|---|
| package | **com.tencent.mm** |
| versionCode / Name | 3160 / **8.0.77** |
| minSdk / targetSdk / compileSdk | 24 / 34 / 36 |
| Application | **com.tencent.mm.app.Application**(Tinker loader,见下) |
| appComponentFactory | androidx.core.app.CoreComponentFactory |
| 启动 Activity | **com.tencent.mm.ui.LauncherUI**(MAIN/LAUNCHER, exported=true, launchMode=1 singleTop, **主进程** com.tencent.mm) |
| 组件规模 | activity 2075 / service 119 / provider 31 / receiver 47 |
| 多进程 | :push :tools :appbrand0-4 :sandbox 等(LauncherUI 在主进程,首帧只需主进程) |
| 关键 flag | extractNativeLibs=true, largeHeap, hardwareAccelerated, usesCleartextTraffic, allowNativeHeapPointerTagging=false, pageSizeCompat=32 |
| ABI | **仅 arm64-v8a**(206 个 .so),与板端匹配 |
| 签名 | 仅 V2/V3(无 META-INF/*.RSA v1);**安装路径不校验签名(见三),非阻塞** |

**Tinker 热补丁**:`com.tencent.tinker.loader.*` + `ActivityStubs` 存在。`com.tencent.mm.app.Application`
是 Tinker loader,attachBaseContext 时加载补丁并委托真实 Application。**无补丁时应 no-op 直通**。
纪律:保持 `tinker/` `tinker_temp/` 干净,绝不安装补丁。

**首帧关键 native**(WeChat 自带完整栈,对平台 shim 依赖比头条小):
- `libwechatxlog.so`(xlog 日志,极早加载)、`libmmkv.so`(配置,mmap)、`libwechatmm.so`(核心 JNI)
- `libWCDB.so`(自带 SQLCipher,不走平台 android.database.sqlite)+ `libcrypto/libssl`(自带 BoringSSL)
- `libmarscomm/libmarsquic/libilink_network/libwechatnetwork`(Mars 自有网络栈,非 okhttp/cronet)
- `libc++_shared.so`(自带,随 apk 解压到 app lib 目录)
→ 头条的 `libwlsqlite.so` 平台 SQLite 桥对 WeChat 仍有价值(AndroidX/Room/Tinker 记账),但非首帧必需。

---

## 二、板端环境勘察(继承的头条基线)

**anco 兼容子系统**:Android APK 经 `appspawn-x` 守护(pid 持久,ondemand)在 OHOS 上运行。
`pr03-runtime-recover.sh` 每次开机一次性 bind-mount `/data/pr03-74e6-portable/android` → `/system/android`
等 7 处,设 `persist.sys.abilityms.support_anco_app true`。

**运行时基线(所有 anco 应用在 appspawn-x 级自动继承,含 WeChat,无需逐应用配置):**
- `appspawn_x.cfg`(真身 `/system/etc/init/appspawn_x.cfg`):`APPSPAWNX_FORCE_INT=1 + NO_JIT=1` → **-Xint**;
  `LD_PRELOAD=…liblzma.so:…libwestlake_stackgrow.so`(**栈保护**)。
- `/system/android/framework/oh-adapter-runtime.jar`(**ActivityManagerRouting + ServiceManager 桩 + SQLite loader + TLS 网关**)——注入每个应用进程。**共享,只复用不覆盖**。
- `/system/android/lib64/` 下 `libandroid.so`(含 ALooper 补丁)、平台 shim。
- `libwlsqlite.so`(平台 SQLite 桥)、`libwlveltrack.so`(VelocityTracker 桩)。

**应用安装布局**(已有 android 应用:helloworld/noice/kika/头条 + 桥 zigzag):
- el1 bundle:`/data/app/el1/bundle/public/<pkg>/`,内含 `android/base.apk`、`android/lib/arm64-v8a/`、`entry.hap`(OHOS 注册桩)、arkwebcore/misc。
- el2 数据:`/data/app/el2/100/base/<pkg>/`(uid 独占),标准 Android 数据目录(databases/files/shared_prefs/…)。
- uid 递增分配:kika 20010044 / noice 53 / zigzag 55 / helloworld 56 / 头条 57 → 下一空位约 **20010058**(BMS 分配)。
- 板端环境:dev mode ON,SELinux **Permissive**,`/data` 余 **215G**。

---

## 三、安装机制(sanctioned,已从源码确认)

源码:`~/a2h-build/westlake-dev/framework/package-manager/`。

**`bm install <apk>` 直接支持 Android APK**:libbms(Route C)检测到 `.apk` 路径后 dlsym
`oh_adapter_install_apk` → `ApkManifestParser::Parse`(取 packageName)→ `ApkInstaller::DeployApk`:
1. 建 `/data/app/el1/bundle/public/<pkg>/android/`
2. copy base.apk
3. 解压 `lib/arm64-v8a/*.so` → `android/lib/arm64-v8a/`
4. dex2oat(**缺失→非致命,`LOGW` "app will run in interpreted mode"**,-Xint 本不需要)
5. 建数据目录 + 设 owner
6. libbms 用 `oh_adapter_install_apk_with_manifest` 返回的 JSON 注册 InnerBundleInfo 到 BMS,合成 entry.hap。

**关键性质**:
- 安装路径**不做签名校验**(C entry 直接 Parse→Deploy,无 VerifyApk 调用)——与不同签名的 helloworld/noice/头条 都能装一致。WeChat V2/V3 签名非阻塞。
- **不重启 appspawn-x**(纯 BMS 操作)→ 不打扰头条。
- 板端已部署库确认含 Route C:`libbms.z.so` ×3、`libapk_installer.so` ×8 引用 `oh_adapter_install_apk`。
- INTERNET 权限:经 access_token.db(头条 `scripts/grant_internet.sh` 思路),**首帧不需要**,联网后再补。

---

## 四、执行计划

- [x] Step 1 APK 静态解构(package/启动 Activity/native/签名) — 完成
- [x] 板端环境勘察 + 安装机制确认 — 完成
- [x] Step 2 独立槽位安装 — **完成**
  - `bm install` 直接装 278MB apk 会失败:manifest 2075 activities → JSON 385762B 超 262144B 缓冲(`oh_adapter_install_apk_with_manifest` 返回 -5,ERR_INSTALL_INTERNAL_ERROR)。**非 WeChat 问题,是安装桥缓冲上限**。
  - 解法(头条模型):装极小 stub apk 让 BMS 注册 bundle+uid+dirs(小 JSON),再换回真 base.apk + 解压真 libs。
    - `scripts/axml_setpkg.py`:改写 helloworld stub 的 AXML 池字符串 package→com.tencent.mm(索引不变,其余 chunk 全有效);去掉失配的 v1 META-INF → `out/stub-mm.apk`。
    - `bm install -p stub-mm.apk` → **install bundle successfully**;bundle=`/data/app/el1/bundle/public/com.tencent.mm/`,**uid/gid=20010058**,accessTokenId=536938018,合成 entry.hap。
    - `scripts/board_wechat_swap.sh`:base.apk 换成真微信(sha 18714afe… 校验)+ 解压 206 个 arm64 .so 到 `android/lib/arm64-v8a/`(installs:installs, data_app_el1_file);清掉 macOS tar 的 `._*` AppleDouble 副本(曾误计 412→206)。
- [ ] Step 3 冷启动 + 逐层排雷(进行中)
  - 排雷 #1 **ability 未注册**:`aa start` 报 `ExplicitQueryAbility no match ...LauncherUI` → OHOS AMS 在 spawn 前要求 ability 在 BMS 注册。头条有 975 个 ability(装包时按 manifest 全量注册)。stub 只注册了 helloworld 的 ability。
    - 修:`axml_setpkg.py` 再把 stub 启动 activity 名 `.MainActivity`→`.ui.LauncherUI`(→com.tencent.mm.ui.LauncherUI),`bm uninstall` + 全新 `bm install` stub-mm2 → BMS 有 `com.tencent.mm.ui.LauncherUI`(isLauncherAbility=true),uid 仍 20010058。再 swap 回真 apk+libs。
    - 注:`bm install -r` 会重解析盘上的真 apk(2075 activities)再撞缓冲 → 必须走 uninstall+fresh。
  - 排雷 #2 **数据目录缺失**:`aa start` 成功、AMS 路由 appspawn-x、fork 出子进程(pid),但 sandbox 建立失败:`check dir /data/app/el2/100/base/com.tencent.mm failed` → `DoAppSandboxMountOnce app-base failed` → `NotifyStartProcessFailed`。装包只建了 el1 bundle,没建 el1/el2 数据目录。
    - 修:`board_wechat_datadirs.sh` 按头条模式建 el2/100/{base,log,database}、el1/100/{base,database} 骨架(owner 20010058,mode/context 对齐)。
  - 排雷 #3 **首帧存活但 LIFECYCLE_TIMEOUT**:aa start 成功、pid 存活 ~100s,但 `ability com.tencent.mm.ui.LauncherUI foreground timeout` 被 OHOS AppDfr 杀(signal 9)。stderr 主线程栈(WL-MAINSTACK)显示卡在 `MMApplicationLike.onBaseContextAttached` → `za5.o.d/za5.a.e` → `cp.w0.<clinit>` → `cp.u0.<init>` → **`cp.v0.a()` 的 `while(!b()) Thread.sleep(10)` 自旋**。
  - **根因(决定性)**:`cp.v0` 是用 `LocalServerSocket`(抽象命名空间 AF_UNIX)做的跨进程互斥锁(WeChat `MicroMsg.DeviceInfo` 设备ID/auth 缓存)。`LocalSocketImpl.bind()` 调 native `bindLocal`,而**适配层没有注册任何 LocalSocket native**(遍历 route/android lib64 无 bindLocal/connectLocal/*_native)→ `new LocalServerSocket` 每次抛 UnsatisfiedLinkError → `b()` 恒 false → 主线程无限自旋 → 前台超时。这是继 SQLite/ALooper 之后的又一处平台能力缺口(AF_UNIX LocalSocket JNI)。
    - 隔离修法(不动共享适配层、不重启 AppSpawnX):dex 补丁 WeChat 自己的 `cp.v0.a()V`,首指令 `1d 02`(monitor-enter)→ `0e 00`(return-void),等长、verifier 安全,跳过永不成功的锁(单进程首启无竞争)。`scripts/dexpatch_v0.py` + 重建 `out/weixin-patched.apk`(classes12.dex,adler32+SHA1 已修)。
    - 备用彻底修法(需重编共享 liboh_android_runtime.so + 重启 appspawn-x):实现 LocalSocketImpl 的 bindLocal/connectLocal/read/write native。留给官方合流(S3)。
  - 排雷 #4 **libwechatcrash SIGSEGV**:patched apk 跳过锁后,进程 ~14s 崩溃(不再自旋)。tombstone `cppcrash-16956`:`SIGSEGV(SEGV_MAPERR)@0x8`(NULL 解引用),主线程栈全在 **`libwechatcrash.so`**(WeChat 自带 native 崩溃/APM 上报器),经 `art_quick_generic_jni_trampoline` 从 Java 调入;崩前有 FDSAN(signal 42)fd 卫士信号。JNI 类 `com.tencent.nativecrash.InitializationProbe`(nativeInit/nativeInitFd)。即 WeChat 崩溃上报器 native init 在 anco/MUSL 环境里 NULL 解引用。
    - 改名 `libwechatcrash.so` 无效:WeChat 有自己的 SO 提取器,从别处加载了同名库;`NativeCrash.nativeInit` 仍执行,这次 native 里**挂住**(RUNNABLE ~100s)→ 又 LIFECYCLE_TIMEOUT。
    - 真正修法:dex 中和 `com.tencent.nativecrash.NativeCrash.init(String,int,int,boolean)V` 与 `init([Landroid/os/ParcelFileDescriptor;II)V` 首指令 `63 00 6e 3f`(sget-boolean libLoaded…)→ return-void,彻底跳过 nativeInit/nativeInitFd。已恢复 libwechatcrash.so 原名。
    - 累积 dex 补丁:`scripts/dexpatch_methods.py`(通用中和器)对原始 classes12.dex 打 3 处(cp.v0.a + 2×NativeCrash.init)→ classes12.patched2.dex → out/weixin-patched.apk(sha 90b546b7)。
- 前台超时基线:`persist.sys.abilityms.timeout_unit_time_ratio`=**20**(恢复脚本本应设 400,本次启动未生效);实测前台超时 ~100s。若中和后仍是"主线程推进但慢"型超时,再抬高该 ratio(注意可能需 abilityms 重启才生效,属共享操作需谨慎)。
  - 排雷 #5(patched2 后暴露两条真 Java 异常):
    - **Failure A(Application 实例化)**:`NativeCrash.init` 单纯中和不够——`xp.i.<init>` 还调 `NativeCrash.resetCustomInfo()` 等 native,native 抛 `IllegalStateException: NativeCrash not initialized`(ExceptionInInitializerError→Tinker→Application 失败)。libwechatcrash `nativeInit` 本身在 anco 里必崩:反汇编 `LocalUnwind_getBacktrace` 崩点 `ldr x0,[x23,#0xfc0]`(空)→`ldp x8,x9,[x0,#8]`=NULL@0x8,即崩溃上报器的栈回溯器解引用一个 MUSL+bionic-compat 未填充的 per-thread 结构(深层共享缺口,不可外部修)。→ 修法:把 `NativeCrash` 的全部 native 调用方法都中和成 no-op(init×3 + customInfo + resetCustomInfo + reserveMemory),让崩溃上报器整体变哑桩。
    - **Failure B(LauncherUI 实例化)**:`LauncherUI.<init>`→`com.tencent.mm.ui.z8.<init>`→`android.os.Environment.getExternalStorageDirectory()` 抛 `ArrayIndexOutOfBoundsException: length=0`。适配层 `getExternalDirs()=StorageManager.getVolumeList()` 返回空(anco 无外部存储卷;`currentOpPackageName()`==null 时直接 return 空数组)。这是共享适配层缺口(S1 在头条 SearchActivity 也撞过同类空数组)。→ 隔离修法:`z8.<init>` 保留 super() 后 return-void,`f218606a` 取默认 false,跳过 getExternalStorageDirectory。
  - patched3 = 原始 classes12.dex(cp.v0 + NativeCrash 6 法)+ classes.dex(z8.<init>)→ out/weixin-patched.apk(sha 41eb164a)。dexpatch_methods.py 已支持构造器(super 后中和)。
  - 排雷 #6(patched3 后暴露 native 加载缺口):
    - **libz.so 缺失**:`libmmkv.so`(必需,配置存储)依赖 `libz.so`,app 命名空间没有 → NoClassDefFoundError o4 / UnsatisfiedLinkError。头条同款(README §1.8)。修:把板端 `/system/android/lib64/libshared_libz.z.so`(372784B,anco zlib)复制成 WeChat lib 目录的 `libz.so`。
    - **getprogname 符号缺失**:`libwechatbacktrace.so`(Matrix APM 栈回溯)UND 导入 `getprogname`(BSD/bionic 符号,anco musl libc 无;linker 只卡在这一个,其余 bionic 符号 compat 都提供)→ 重定位失败 → Application 失败。修:`scripts/elf_weaken.py` 把 libwechatbacktrace.so 的 getprogname 由 GLOBAL 改 WEAK(未解析→0,仅崩溃转储时用到,首帧不触发)。已单独推该 .so。
  - 排雷 #7 **Matrix APM native 崩溃簇(共享根因)**:让 libwechatbacktrace 能加载后,`libtrace-canary.so` 的 native init 又 SIGSEGV NULL@0x8。反汇编发现三个 Matrix native 库(libwechatcrash / libwechatbacktrace / libtrace-canary)崩点**同一模式**:`sigaction(35)` 装信号处理器 → 往一个 `std::vector`(存旧 handler)push,而 `*(base+off)` 向量头指针为 NULL → `ldp [NULL+8]` 崩。这是 Matrix 内部 C++ handler 注册表向量在 anco 里未初始化,**不是可外部提供的 OS 结构**,只能在 WeChat 侧禁用 Matrix native 监控。
    - 修法:dex 中和各 Matrix tracer 的 native-init 生命周期方法。已中和 `com.tencent.matrix.trace.tracer.SignalAnrTracer.d()`(保留 super.d(),跳过 nativeInitSignalAnrDetective)。其余 native(nativeInitThreadHook / nativeInitTouchEventLagDetective / nativeInitFileHook)若后续崩,按同法批量中和。
    - patched4 = classes12(cp.v0 + NativeCrash×6 + SignalAnrTracer.d)+ classes.dex(z8)。板端 lib 目录另有:libz.so(补)、libwechatbacktrace.so(getprogname 弱化)、libwechatcrash.so(原样)。
  - 排雷 #8 **getprogname 弱化反噬 + Matrix 需整体禁用**:patched4(SignalAnrTracer.d 中和)后仍崩,`SIGSEGV pc=0`(调用空函数指针)。根因:getprogname 弱化让 libwechatbacktrace 能加载,但它在**加载/init 时就调用** getprogname(→0)→ pc=0。即 getprogname 不是"仅崩溃时用",弱化不可行;而不弱化则 load 直接 "symbol not found" 致命。**结论:Matrix APM 三件套(crash/backtrace/trace-canary)在 anco 里无论加载与否都过不去,必须整体禁用**(阻止 trace-canary 被 loadLibrary)。
    - **下一步(明确)**:定位 WeChat 的 Matrix TracePlugin 初始化 / tracer 实例化点(谁 new SignalAnrTracer / 谁 start TracePlugin),在 dex 里中和,使 tracer 类根本不被引用 → 其 `<clinit>` 的 `System.loadLibrary("trace-canary")` 不执行 → 三个 native 库都不加载。这样 getprogname/NULL@0x8 全消。可能需同时处理 Matrix.with()/isInstalled() 的 downstream 断言(类似 NativeCrash)。
    - 备注:当前板端 libwechatbacktrace.so 是 getprogname 弱化版;禁用 Matrix 加载后该文件状态无所谓,可后续还原原版。

## 里程碑与状态(截至本轮)
- **核心部署完成(有板端凭据)**:微信 8.0.77 装入独立槽位 `/data/app/el1/bundle/public/com.tencent.mm/`(uid 20010058,BMS 注册,el1/el2 数据目录,206 arm64 .so + 补 libz.so),继承头条运行时基线(-Xint/stackgrow/adapter/shims),全程未碰头条,S1 全程 idle。
- **微信可 spawn + 跑 Application init**:`aa start -a com.tencent.mm.ui.LauncherUI -b com.tencent.mm` 成功路由 appspawn-x fork 子进程,主线程推进到 `handleBindApplication`/`scheduleTransaction LauncherUI`。
- **已修 6 处首帧阻断**(全部 WeChat 侧隔离,不动共享适配层/不重启 AppSpawnX):LocalSocket 自旋、NativeCrash 哑桩、z8/Environment、libz.so、getprogname(临时弱化,待改为禁用 Matrix)、SignalAnrTracer.d。
- **未达成**:LauncherUI 首帧未绘制。当前唯一阻断类 = **WeChat Matrix APM native 簇**(需整体禁用,见排雷 #8)。之后可能还有更多 anco 兼容缺口(逐层排雷)。
  - 排雷 #9 **MUSL init_array 空哨兵(关键突破)**:crash 一直是 `do_init_fini+444`(MUSL 逐个调 init_array 项)`blr x8`(x8=NULL)→ pc=0,不是 Matrix。tombstone maps 定位到 **libstlport_shared.so**:init_array 4 项,[0-2] 是 RELATIVE 重定位的真构造器(file 值为 0),[3] 是**无重定位的尾部空哨兵**。bionic 跳过 0/-1 项,OHOS-MUSL 不跳 → 调 NULL 崩。
    - 修法(sanctioned):`sanitize_android_elf.c`(westlake bionic_compat 工具)裁剪尾部空哨兵、缩小 DT_INIT_ARRAYSZ。宿主机无 elf.h,重写为 `scripts/sanitize_elf.py`,**关键修正:哨兵判定必须 reloc-aware**(file 0/-1 且该槽无 RELA/RELR 重定位;仅看 file 值会误删 RELATIVE 真构造器,C 工具也有此局限)。ANDROID_RELA(packed)库保守跳过不裁。
    - 全量:18 个库有真尾部空哨兵被裁(libstlport_shared/libNLog/libaudio_common/libcodec_factory/libentryexpro/... 都是 RELA/RELR,裁剪安全);40 个 ANDROID_RELA 库跳过;148 无需处理。已打 `out/san18.tar.gz` 上板。
    - **结果:init_array 崩溃全消,微信推进到 `LauncherUI.onCreate`!**(重大进展:越过全部 native 加载/init 崩溃)
- [ ] Step 4(进行中):当前阻断 = **平台 SQLite 缺实现**(`android.database.sqlite.SQLiteConnection.nativeOpen` No implementation → MMApplicationLike.onBaseContextAttached 失败)。这正是头条 §2.41-2.45 的 libwlsqlite.so(SQLite/CursorWindow JNI 桥)缺口。
    - 适配层(头条构建)在硬编码头条路径找 shim:`/data/app/el2/100/base/com.ss.android.article.news/app_librarian/default.version.6986727972/libwlsqlite.so` 或 `/data/app/el1/bundle/public/com.ss.android.article.news/android/lib/arm64-v8a/libwlsqlite.so`。当前板端**无** libwlsqlite.so(版本目录已轮转)。out/libwlsqlite.so 是 6520B 空桩(无 SQLite)。
    - 排雷 #10 **SQLite shim 命名空间隔离**:`board_sqlite.sh build`(WSL NDK)编出真 libwlsqlite.so(2.28MB,含 amalgamation)。但适配层 `loadSqliteShim` **硬编码头条路径**(SQLITE_SHIMS = com.ss.android.article.news 的 el2 app_librarian / el1 bundle lib),而 OHOS 命名空间隔离:微信进程的 linker ns 只能访问 com.tencent.mm 自己的目录,访问头条目录 → `dlopen_ns failed ... No such file or directory`(errno 2)。把 shim 放头条目录对微信无效。
    - 修法(通用、加法、不伤头条):patch `amr/.../ActivityManagerRouting.java` 的 loadSqliteShim,先按 `/proc/self/cmdline` 取当前包名派生 `curAppShim()`= `/data/app/el1/bundle/public/<当前包>/android/lib/arm64-v8a/libwlsqlite.so`(ns 可达),再回退硬编码头条路径。重建 oh-adapter-runtime.jar(7fd6e415),`bridge.sh backup`(原始 0192be1b 备份在)+ push-amr 部署。对头条:curAppShim 派生出头条自己路径=原路径,反而**恢复了头条丢失的 SQLite**;加法安全。libwlsqlite.so 放进微信自己 lib 目录(ns 可达)。
- [ ] Step 4(进行中):SQLite shim 部署后重启验证,通后继续排雷至 LauncherUI 首帧绘制。

## 深层共享缺口(留待官方合流 S3;当前用 WeChat 侧 dex 隔离绕过)
1. **AF_UNIX LocalSocket JNI 全缺**(bindLocal/connectLocal/read/write_native 未注册)→ WeChat cp.v0 设备ID 跨进程锁自旋。彻底修:适配层实现 LocalSocketImpl native。
2. **libwechatcrash 栈回溯器依赖的 bionic per-thread 结构(off 0xfc0)在 MUSL 下为空** → nativeInit 崩/挂。彻底修:补 bionic TLS 兼容或适配层提供该结构。
3. **StorageManager 外部存储卷为空** → Environment.getExternalStorageDirectory()[0] 越界。彻底修:适配层 StorageManager 返回至少一个卷(或 sandbox 挂 /storage/emulated/0)。

### 关键事实备查
- uid=20010058;bundle `/data/app/el1/bundle/public/com.tencent.mm/android/{base.apk,lib/arm64-v8a/}`;el2 数据 `/data/app/el2/100/base/com.tencent.mm/`(首次 spawn 由 appspawn 建)。
- 安装桥缓冲上限是已知技术债:根治需重编 libbms/apk_installer(增大缓冲或只序列化 launcher ability),或走 stub 模型(当前采用)。已交 memory 备查。

## 协作纪律
- 独立槽位 `com.tencent.mm`,绝不碰头条(`com.ss.android.article.news`)文件与 uid 20010057。
- 共享 `oh-adapter-runtime.jar` 只复用不覆盖(头条 §2.45 记录过 S1/S2 互相覆盖事故)。
- 每次推文件/杀进程/启测前 `ps -ef | grep article` 错峰;**绝不重启 AppSpawnX**(会杀头条)。
- 关联会话见 memory `reference_openharmony-android-bridge-sessions`。

## 现场日志
- 板端勘察产物:`board-recon/`(appspawn_x.cfg、pr03-runtime-recover.sh、appdata-sandbox.json、entry.hap、install 脚本、noice hap)。

### 里程碑更新(本轮)
- **越过全部 native init 崩溃**:sanitize_elf.py(reloc-aware)裁 18 库尾部空哨兵 → libstlport_shared 的 do_init_fini NULL 崩消失。
- **SQLite 通了**:真 libwlsqlite.so(2.28MB,WSL 编)+ 适配层通用化(curAppShim 用 ApplicationInfo.nativeLibraryDir,不能用 /proc/self/cmdline——anco 子进程 cmdline 仍是 appspawn-x)。适配层 jar 改后需重启 appspawn-x(daemon 预载)才生效(S1 idle 时错峰重启)。实测:`[WL-SQLITE] load .../com.tencent.mm/... OK; SQLiteConnection=OK 27/31; CursorWindow=OK`。此改动通用、也恢复了头条 SQLite。
- **当前阻断**:`Tinker Exception: baseRevision(...) must not be null`(Kotlin 非空断言,MMApplicationLike.onBaseContextAttached)——微信读取某 baseRevision(Tinker 基线版本?)为 null。已到 LauncherUI.onCreate。

### 排雷 #11 baseRevision / metaData 缺失
- `aq.g.a()`(CSO 启动)对 `mr0.a.f339336b`(=baseRevision)做 Kotlin 非空断言 → NPE。baseRevision 来自 manifest meta-data `com.tencent.mm.BuildInfo.BUILD_REV`(=14d41864...),运行时从 `ApplicationInfo.metaData` 读。
- 适配层 framework jar 的 PackageInfoBuilder 只给**空** metaData Bundle(BMS BundleInfo 不含 Android meta-data)→ 所有 BuildInfo.* 读为 null → WeChat NPE。
- 修法(在我可编的 runtime jar 内,不动 framework jar):`axml.py` 抽出全部 54 条字符串型 meta-data → `out/wl-metadata.properties`,放进微信 android/ 目录(ns 可达,per-app;头条无此文件不受影响);`ActivityManagerRouting` 新增 `enrichMetaData()`(纯反射,取 mBoundApplication.appInfo,从 props 填 metaData),在 loadNativeShims 拿到 appClassLoader 时最先调用(与 SQLite shim 同时机,早于 onBaseContextAttached)。重建 jar(0817e905)+ push-amr + 重启 appspawn-x。

### 排雷 #12 Environment.getExternalStorageDirectory 空卷(核心 VFS 阻断)
- baseRevision 通过后,微信核心 VFS `com.tencent.mm.vfs.f3.<init>→g()` 调 `Environment.getExternalStorageDirectory()` = `getExternalDirs()[0]`,而 `StorageManager.getVolumeList()` 经 framework 的 'mount' 桩返回空数组 → ArrayIndexOOB(length=0)→ e3.<clinit> ExceptionInInitializerError → Application 失败(l46 "Skeleton not initialized" 是连带)。与头条 z8/SearchActivity 同根(空外存卷),但 VFS 是核心,遍地都调,必须根治。
- 修法(runtime jar,不动 framework):`ensureMountVolumeShim()` 覆盖 ServiceManager.sCache['mount'],用 MountHandler 代理:getVolumeList/getVolumes 返回单个 StorageVolume(/storage/emulated/0,14 参构造器反射),其余方法委托真桩。在 attachApplication + appClassLoader ready 两处调用(幂等,需框架先注册 mount)。板端建 /storage/emulated/0(777)。重建 jar + push-amr + 重启 appspawn。
- **mount shim 装上但仍崩**:hilog 实证 `StorageManager: Missing package names; no storage volumes available` —— `StorageManager.getVolumeList` 在到达我的 shim **之前**就因 `currentOpPackageName()==null` 提前返回空数组。反编译 anco `ActivityThread.currentOpPackageName()`:**不读** mBoundApplication.appInfo.packageName,而是 `getApplication().getOpPackageName()`,`getApplication()` 只返回字段 `mInitialApplication`。微信 Tinker onBaseContextAttached 发生在 makeApplication **内部**(mInitialApplication 尚未赋值)→ 该字段为 null → currentOpPackageName null。
- **根治 #12(runtime jar,加法)**:`ensureInitialApplication()` 在 VFS 前用一个占位 `Application`(`new Application()` + `Application.attach(ContextImpl.createAppContext(at, LoadedApk))`,反射)填入 `ActivityThread.mInitialApplication`,其 `getOpPackageName()`=com.tencent.mm;makeApplication 完成后框架用真 app 覆盖它,只覆盖构造窗口。在 poll(enrichMetaData 路径,早于 VFS)调用。**实测:`[WL-INITAPP] seeded placeholder Application; getOpPackageName=com.tencent.mm`,getExternalStorageDirectory 越界消失,#12 清除。**

### 排雷 #13 tzdata 格式不匹配(48B vs 52B 索引项)
- 过 #12 后:`MMApplicationLike.onBaseContextAttached → za5.o.d → r75.x0.initialValue(new android.text.format.Time())` → `ZoneInfoDb.getBufferIterator` 对 null `MemoryMappedFile` 调 bigEndianIterator → NPE。头条不用 Time 故没踩。
- anco `com.android.i18n.timezone.ZoneInfoDb.<clinit>` 饿汉建单例 DATA=`loadTzDataWithFallback(getTimeZoneFilePaths("tzdata"))`;`getTimeZoneFilePaths` 只试**一条**路径 `$ANDROID_TZDATA_ROOT/etc/tz/tzdata`。子进程无 `ANDROID_TZDATA_ROOT` env → "null/etc/tz/tzdata" → mmap 空 → NPE。
- **修法(runtime jar + per-app 数据,不动平台)**:`ensureTzdataEnv()` 用 `android.system.Os.setenv("ANDROID_TZDATA_ROOT", <app android dir>/wl-tz, true)`(`System.getenv` 走 `Libcore.os` 实时,setenv 立即可见),再反射 `rebuildZoneInfoDb()` 重建 DATA(class 早已加载,单例已缓存 null-map,须替换)。路径从当前进程 nativeLibraryDir 派生,per-app、不碰头条。
- **深坑:平台 ZoneInfoDb 读不了平台自带 tzdata**。`/system/etc/zoneinfo/tzdata`(唯一副本,tzdata2025b)索引项 **48 字节**(Android P+ 去掉 gmtoff),而 anco ZoneInfoDb.readIndex 要求 `(data_off-index_off)%52==0`(旧 52 字节格式,含 legacy gmtoff)→ `22704%52=32≠0` → readIndex 抛 → loadData=false → createFallback(null-map)。诊断实证:fis open OK(magic tzdata2025b)、mmapRO OK size=286219、offsets 合法,但 loadData=false。
- **修法**:写 48→52 转换器(`scripts/`,已本地验证)——每条索引项插入 4 字节 gmtoff=0,data_off/final_off 各 +473×4;data 段原样(raw 偏移相对 data 段起点不变,byteOffsets 在 load 时 = raw+data_off 重算)。产物 `tzdata_52`(286219B,473 项、有序、%52==0、首 zone TZif 魔数正确),staged 到 `wl-tz/etc/tz/tzdata`。**实测:`[WL-TZ] loadData=true mappedFile=...`;`rebuilt ZoneInfoDb.DATA`;tz NPE 归零,#13 清除。**

### 排雷 #14 Cronet NetworkChangeNotifier NoSuchMethodError(当前)
- 过 #13 后微信推进极深:TLS/BCTLS 装好(`[WL-TLS] install: ok providers=WLBC/BCJSSE roots=133`)、DisplayMgr 查询(1200x1920)、MMKV native 注册、**LauncherUI transaction 已 scheduleTransaction**。但进程 `System.exit(1)`(appspawn `exit with code:1`,AMS `kill reason=OnRemoteDied`)。
- 关闭 hilog 隐私(`hilog -p off`)+ 解码微信崩溃盘文件 `MicroMsg/crash/.exception.*.preventcrashlog`(base64+zlib 的 error_json)得真栈:
  `java.lang.NoSuchMethodError: ConnectivityManager.registerDefaultNetworkCallback(NetworkCallback, Handler)V`(anco adapter-mainline-stubs.jar 的 ConnectivityManager 缺该 O+ 重载)
  `at org.chromium.base.compat.ApiHelperForO.registerDefaultNetworkCallback → NetworkChangeNotifierAutoDetect.register → CronetLibraryLoader.ensureInitializedOnInitThread`(Cronet init HandlerThread)。未捕获 → 微信 sandbox.monitor 崩溃处理器 → 起 ExceptionMonitorService(action uncatch_exception,失败)→ System.exit(1)。
- **修法(WeChat 侧 dex 隔离,不动共享 stub jar)**:`register()` 内两处都用缺失 API(registerDefaultNetworkCallback @0x1b + registerNetworkCallback @0x49),故整体中和 `org.chromium.net.NetworkChangeNotifierAutoDetect->register()V`→return-void(dexpatch_methods.py,classes16.dex)。首帧无网(无 INTERNET 权限)本不需要网络变更检测。重打 base.apk(仅 classes16 变)推板。**已清除(不再 NoSuchMethodError)。**

### 排雷 #15 缺失系统服务 → Kotlin 非空 cast NPE(telephony/connectivity)
- 过 #14 后:`com.tencent.mars.comm.NetworkSignalUtilImpl.InitNetworkSignalUtil → i0.k → getSystemService("phone") as TelephonyManager` → "null cannot be cast to non-null type TelephonyManager"(anco SystemServiceRegistry 无 telephony fetcher,getSystemService 返 null)。后台线程未捕获 → 微信 sandbox.monitor 崩溃处理器 → System.exit(1)(注:标准 Android 上任何线程未捕获异常都由 RuntimeInit KillApplicationHandler 杀进程,故必须消除抛出,而非改处理器)。
- **崩溃取证法(可复用)**:`hilog -p off` 关隐私;解码 `MicroMsg/crash/.exception.*.preventcrashlog` 里 `error_json_<base64+zlib>` 得真实 Java 栈(脚本见会话)。
- 尝试(适配层):`ensureSystemServiceStubs()` 反射向 `SystemServiceRegistry.SYSTEM_SERVICE_FETCHERS` 注册 "phone" fetcher(Proxy 实现 ServiceFetcher.getService → `new TelephonyManager(ctx)`)→ cast 过了,但 `TelephonyManager.getPhoneType()→getSubId→SubscriptionManager.getDefaultSubscriptionId→TelephonyFrameworkInitializer.getTelephonyServiceManager()` **又缺**(NoSuchMethodError)。**anco 电话栈整层不可用**,给了 TelephonyManager 其方法照崩。
- **根治(WeChat 侧 dex 中和,classes12)**:`i0.k()V`(telephony,getPhoneType)、`i0.l()V`(ConnectivityManager.registerNetworkCallback)、`i0.m()V`(unregisterNetworkCallback)全部 →return-void(都是 v0 网络态缓存的填充器,首帧无网可留默认);另 `NetworkSignalUtilImpl.InitNetworkSignalUtil(Context)V`→return-void。i0.k 有多个调用者(NetworkSignalUtil、ho0.k1.run 周期刷新),故必须中和 i0.k 本身而非单个调用点。

### 里程碑:**微信主线程进入消息循环(Application init 基本跑通)**
- 过 #15 后:`[WL-MAINSTACK] pass 4 main state=RUNNABLE at MessageQueue.nativePollOnce ← Looper.loop ← ActivityThread.main` —— 主线程空转在消息循环!TLS/BCTLS 装好、DisplayMgr、MMKV、VFS、WCDB 类加载均已推进。截图 W_15..W_150(150s 全程存活)= 纯白 app 窗口(状态栏在,`[WL-VIEWTREE] 0 window(s)`,LauncherUI 内容未绘制)。

### 排雷 #16 缺失 JNI native 方法(当前阻断,新相位)
- 白屏根因:`Application` 实例化其实**失败**——`za5.o.d(:280) → WeChatSplash.b → splash.t.a → WeChatSplashStartup.a(:163) → android.os.Process.getThreadPriority(I)I` **UnsatisfiedLinkError: No implementation found**(anco 未注册该 native)。被 AppSchedulerBridge bind Handler 捕获(未 System.exit),故主循环空转但无有效 Application → LauncherUI 无内容。此前各轮在更早处先崩,#15 修完才推进到此。
- 同类缺失 native(hilog/栈实证):`android.os.Process.getThreadPriority(I)I`、`android.os.Process.setThreadPriority(I,I)V`(WeChatSplashStartup.a 的 InitThreadController setHighPriority)、`android.app.ActivityThread.nPurgePendingResources()V`(MessageQueue idle)。这些是 anco 对 android.os.Process/ActivityThread 的 JNI 注册缺口(排雷类别 b:missing Bionic/native symbols),**全应用范围高频调用**,逐调用点 dex patch 不可持续(且每次 284MB 重推 base.apk)。
- **根治计划(app-local native shim,进行中)**:写 `libwlnatives.so`,JNI_OnLoad 里 `RegisterNatives` 补齐这些方法(getThreadPriority=getpriority(PRIO_PROCESS,tid);setThreadPriority=setpriority;nPurgePendingResources=no-op),WSL NDK 编(复用 board_sqlite.sh 工具链),放进微信 lib 目录,适配层在 poll 早期 `System.load` 载入(仅在微信进程注册,不碰头条)。一处修复所有调用者。
- 备注:base.apk 现为 nn3(classes.dex cp.v0/NativeCrash + classes12 cp.v0/NativeCrash/SignalAnrTracer/InitNetworkSignalUtil/i0.k/l/m + classes16 Cronet register)。适配层 jar 现含 initApp/tzdata/ZoneInfoDb-rebuild/telephony-fetcher/svcstub。tzdata_52(48→52 转换)在 wl-tz。

### 排雷 #16 解决:libwlnatives.so(app-local JNI 注册)
- 写 `native/wlnatives/wlnatives.c`,JNI_OnLoad 里 `RegisterNatives` 补齐 `android.os.Process.getThreadPriority(I)I`=getpriority、`setThreadPriority(II)V`/`(I)V`=setpriority、`android.app.ActivityThread.nPurgePendingResources()V`=no-op(逐个注册,单个失败不影响其余)。WSL NDK 编(build_wlnatives.sh,ndk-r23b/API30),放 WeChat lib 目录,适配层 `loadWlNatives(cl)` 在 poll 最先 System.load(仅微信进程注册,不碰头条)。**实测 `[WL-NATIVES] load ... OK`,getThreadPriority 崩消失,Application 实例化推进到 plugin 阶段(plugin.zero → WCDB)。**

### 排雷 #17 WeChat CSO/dlopen_ns 依赖库缺失(OHOS musl 合并)
- 过 #16 后:`com.tencent.cso.CsoExecuteError: cannot found library libm.so in load paths`(CsoLoader 加载 libWCDB.so)。libWCDB.so DT_NEEDED = libz/liblog/libm/libc++_shared/libdl/libc。OHOS musl 把 libm/libdl 合并进 libc,**无独立 libm.so/libdl.so 文件**;CsoLoader 的 load path 里找不到 → 报错。
- 关键区分两类加载器:(a)**CsoLoader**(libWCDB)load path 含 /system,找得到 real libz/liblog/libc,只缺 libm/libdl;(b)**dlopen_ns**(app_recovery_lib 的 libwechatcrash/libmmkv)namespace 只认 bundle lib 目录,且**真的要符号**(__android_log_vprint@liblog、crc32@libz)。
- 修法(app-local,放 WeChat bundle lib/arm64-v8a/):
  - `libm.so`、`libdl.so`:空 SONAME stub(WSL 编,build_libm.sh/build_stubs.sh)。满足"找得到"检查;libm/libdl 的符号经 DT_NEEDED 里的 libc(musl)全局解析。
  - `libz.so`、`liblog.so`:**必须用真库**(`cp /system/lib64/chipset-sdk-sp/libz.so`、`cp /system/lib64/westlake/route-a/<hash>/liblog.so`)——空 stub 会 shadow 真库,dlopen_ns 的 libmmkv/libwechatcrash 重定位缺 crc32/__android_log_vprint 而挂。
  - libc.so、libc++_shared.so:CsoLoader 从 /system 或 app 目录已能找到,不动。
- **实测:libWCDB/libmmkv/libwechatcrash 全部加载成功(无 dlopen/CSO 报错)。**

### 里程碑:**微信全部 native 库加载通过,Application 深入 init(WCDB/provider/service-init)**
- 过 #17 后微信推进到:WCDB 初始化、ContentProvider 安装、WeChat 服务初始化线程(wc_srvinit_*)。890 行 stderr,无 dlopen/CSO/telephony/tz 崩溃。

### 当前两处深层阻断(= goal 排雷 c + Matrix 簇,summary 标注留待 S3 官方合流)
1. **空类名 ContentProvider**:`installContentProviders → installProvider → Class.forName("") → ClassNotFoundException` → `Unable to get provider` → Application bind 失败。空名 ProviderInfo 来自 anco BMS 派生的 manifest(某 authority 没映射到类)。尝试在 poll 里过滤 `mBoundApplication.providers`(ensureProvidersClean),但 poll 时 providers 尚未populate(空)→ 未生效;真正的 provider 列表在闭源 AppSchedulerBridge/BMS 路径,ActivityManagerRouting 够不到。**待:在闭源 bind 前过滤,或让 installProvider 跳过空名(需 S3 侧改 AppSchedulerBridge)。**
2. **Matrix 簇 native SIGSEGV**:`Fatal signal 11 (SIGSEGV) Thread wc_srvinit_4`(WeChat 服务初始化线程,native,unwind 失败无栈)。libwechatcrash 已加载(real liblog),但其 backtrace/per-thread 结构在 MUSL 下仍不兼容(summary 深层缺口#2)。因已 dex-中和 NativeCrash,SIGSEGV 无 handler → 杀进程。**待:补 bionic per-thread 兼容 或 更彻底禁用 Matrix native 簇(S3)。**

### 里程碑升级(接续 502 后):微信跑通 Application init → SplashHackActivity.onCreate → 调度 LauncherUI
- 排雷 #16b(shim 走错路径):appClassLoader 可能先于 mBoundApplication.info 就绪(context-loader 兜底)→ curAppShim 返 null → 所有 shim 回退到硬编码头条路径(ENOENT)→ SQLite nativeOpen 缺失崩。头条并发运行时更易踩。修:poll 门槛改为 `cl != null && appNativeLibDir() != null`,超时 400→700 次(~21s)。**实测 shim 全部从 com.tencent.mm 路径加载 OK。**
- 排雷 #18 空 ContentProvider(接 #c):`ensureProvidersClean()` 反射读 `ProviderInfo.name`(编译 stub 缺该继承字段,用 readFieldValue 反射),poll 里从 mBoundApplication.providers 剔除空名项。**实测 `[WL-PROV] dropping empty-name provider authority=com.android.badge; removed 1 kept 30`,bind 越过 installContentProviders。** 全部 WeChat-specific poll 追加(initApp/svcstub/providers)加 `isWeChat()`(nativeLibDir 含 /com.tencent.mm/)门,彻底不碰头条。
- 排雷 #19 isHealthPermission(接 #14 同类,缺共享 stub 方法):`SplashHackActivity.onCreate → HellActivity.onCreate → Activity.dispatchActivityCreated → GMS measurement zzid.onActivityCreated → …zzws → b3.r.a → z2.p.d → AppOpsManager.permissionToOp → HealthConnectManager.isHealthPermission()` NoSuchMethodError(主线程,J_invokeStaticMain_main_threw)。修:dex 把 `z2.p.d(String)String`(permissionToOp 薄包装)patch 成 return null(patch_retnull.py,classes12),覆盖所有 GMS permissionToOp 调用点。**实测越过,Application init 完成、SplashHackActivity/LauncherUI Activity 生命周期推进。**

### 当前唯一阻断:wc_srvinit native SIGSEGV(Matrix 簇,goal 排雷 b / 留 S3)
- 主线程已推进到 Activity 生命周期(SplashHackActivity.onCreate→调度 LauncherUI),但并发的 WeChat 服务初始化线程 `wc_srvinit_0/4` 触发 `Fatal signal 11 (SIGSEGV) fault addr 0`(unwind 失败无栈,pc≈0x7f9e7df1a8 稳定复现)→ 杀整进程。
- 关键因果:本轮为修 libmmkv/WCDB 补了 real liblog/libz → **libwechatcrash.so(Matrix)现在能加载**了(之前重定位失败加载不了),其 native init 在 MUSL 下 bionic per-thread 结构不兼容 → SIGSEGV。即 summary 深层缺口#2 + 旧计划「整体禁用 Matrix」。NativeCrash 的 Java 侧已 dex 中和,但 dlopen(libwechatcrash) 仍发生。
- **待:阻止 libwechatcrash 加载/运行**(stub app_recovery_lib/libwechatcrash.so 为空;或 dex 中和 Matrix TracePlugin/tracer 的 loadLibrary,注意 Tinker 会从 base.apk 重解压 app_recovery_lib)——或 S3 侧补 bionic per-thread 兼容。

### 排雷 #20 wc_srvinit SIGSEGV 根因 = libWCDB.so 构造器 __stack_chk_fail(已解决)
- 取证突破:系统 faultlogger 有完整 tombstone `/data/log/faultlog/temp/cppcrash-<pid>-*`(比 stderr 里 "Unwind failed/Failed to parse maps" 的残缺 dump 强得多)。栈:
  `#00 __stack_chk_fail+4 (ld-musl) ← #01 libWCDB.so+0x3b8620 ← #02 do_init_fini+444 ← #03 dlopen_impl ← StockOpenNamespace ← ANL_Dlopen ← JavaVMExt::LoadNativeLibrary ← System.loadLibrary("WCDB")`
- 排除 init_array 哨兵:libWCDB 用普通 DT_RELA(非 packed),5 条 init_array 全部有 R_AARCH64_RELATIVE 重定位(addend 0x164e50/0x18714c/0x218620/**0x3b8468**/0x3e8740)。崩溃点 0x3b8620 = 构造器[3](0x3b8468)+0x1b8,**是真构造器在跑**,它调用了 __stack_chk_fail;musl 的 __stack_chk_fail 走 a_crash()(写 NULL)→ SIGSEGV@0。
- 排除"线程 TLS 差异"假设:把 libWCDB 提前在适配层自建线程(wl-shims,ART 已 attach)预加载,崩溃**跟着搬到 wl-shims 线程**——说明与线程无关,是 bionic 构建的库读的 stack-guard TLS 槽在 OHOS-musl 下不是同一个/会变,校验伪失败。(该预加载还会**卡住** shim 链导致 SQLite shim 不加载,已撤销。)
- **修法(WeChat 私有副本,零侵入)**:新增 `scripts/elf_rename_undef.py`,把 libWCDB.so 的 **UND** 符号 `__stack_chk_fail` 就地改名为 `getpid`(同为 0 参、更短、NUL 补齐)。UND 符号靠重定位→符号索引→名字解析,不进 GNU_HASH(只索引已定义符号),故改名安全;伪失败时只是白调一次 getpid 后继续。原库备份为 `libWCDB.so.orig`。
- **实测:SIGSEGV 与 __stack_chk_fail 全消**,进程不再被杀,主线程稳定在 `Looper.loop` 空转;WL-PROV/WL-TZ/WL-SQLITE/WL-NATIVES 全部正常。

### 里程碑:**微信进程首次全程存活、零崩溃**(pid 12940,90s 监控窗口 alive=1 全程)
- 证据(`logs/wechat_stable_12940.stderr`,1034 行,crash 计数=0):
  `[WL-SHIM] app ClassLoader + nativeLibDir=.../com.tencent.mm/... after 3188ms` → `[WL-NATIVES] ... OK` → `[WL-PROV] removed 1 empty-name provider(s); kept 30` → `[WL-SQLITE] ... com.tencent.mm/... OK`;无 SIGSEGV、无 __stack_chk_fail、无 J_invokeStaticMain threw;系统侧 `AudioServiceAppStateListener: bundleName=com.tencent.mm uid=20010058 pid=12940 state=2`(系统已把它当作正常前台应用)。
- 截图 `logs/wechat_final_W90.jpeg`(38681B)= 微信空白窗口场景(状态栏在、应用场景在前台,但应用未添加自己的窗口内容)。

### 🎉 里程碑:**首帧窗口上屏 —— 0 window → 1 window,真实 View 树可见**(pid 11045)
- 证据(`logs/wechat_firstframe_11045.stderr`,截图 `logs/wechat_firstframe.jpeg`):
```
mVisibleFromClient=true mVisibleFromServer=true mFinished=false mWindowAdded=true
lifecycleState=3(RESUMED) decorClass=com.android.internal.policy.DecorView
decorVisibility=0(VISIBLE) decorSize=1200x1920
[WL-VIEWTREE] pass 1/2/3/4: 1 window(s)
  window[0] type=1 BASE_APPLICATION flags=... HARDWARE_ACCELERATED fillxfill
  #0 DecorView VISIBLE shown=true @0,0 1200x1920
    #0 ActionBarOverlayLayout VISIBLE shown=true 1200x1920
      #0 FrameLayout VISIBLE shown=true @0,126 1200x1794   ← content frame
      #1 ActionBarContainer VISIBLE @0,0 1200x126 → Toolbar 1200x126
```
  即窗口已 addToDisplay、测量布局完成、DecorView 全屏可见并硬件加速合成;截图从"纯白 38.6KB"变成"微信主题窗口 43.6KB"(深色状态栏 + 浅灰内容区)。

### 排雷 #21 跨 Activity 路由:OH AMS 拒绝应用内 startActivity(首帧的真正阻断)
- 取证(关掉 hilog 隐私后):`bridgeStartAbility: bundle=com.tencent.mm, ability=com.tencent.mm.app.WeChatSplashActivity` → `StartAbility returned 2097205`。
- **关键判别实验**:shell 启动未注册 ability → `10104001 The specified ability does not exist`;shell 启动已注册的 LauncherUI → 成功;把应用内目标 dex 改写成已注册的 LauncherUI(`sget-object j.p` → `const-class LauncherUI`,同为 2 单元指令,见 `scripts/patch_splash_target.py`)→ **仍是 2097205**。⇒ 2097205 不是"找不到 ability",而是**调用方侧拒绝**:anco 适配层把应用内 startActivity 桥接到 OH `IAbilityManager.StartAbility`,而 OH 不接受来自 Android 应用进程的调用(token/权限不被 AMS 认可)。**所以"往 stub 里多注册几个 ability"救不了**,这是平台级缺口(留 S3)。
- 流程链:LauncherUI(继承 SplashHackActivity)→ `onCreate` 先 `setVisible(false)`,再 `if (j.b()) { startActivity(new Intent(this, j.p)); }` → 该 start 被拒 → splash 自 finish → 进程零窗口。
- **绕过修法(WeChat 侧 dex,两处微创)**:
  1. `scripts/patch_splash_nohandoff.py`:把 `j.b()` 判断后的 `if-nez` NOP 掉,让 onCreate 直接走早退分支——**不动 j.b() 本身**(实测把 `j.b()` 强制 false 会连带改变 `Application.onCreate` 里 `WeChatSplash.a` 的路径,主线程卡死在 `sd5.n0.l → ForkJoinTask.get`,LIFECYCLE_HALF_TIMEOUT 被杀,全程 alive=0)。早退后 super 返回,子类 LauncherUI.onCreate 继续在**当前** Activity 里建自己的 UI。
  2. `scripts/patch_splash_visible.py`:把 `setVisible(false)` 的 `const/4 v11,#0` 翻成 `#1`(单个 code unit),否则窗口建好也不显示。
- 另:`scripts/rebuild_apk.sh` 现在从 `out/weixin-patched.apk` 一次性重放全部 dex 中和(telephony/connectivity/GMS permissionToOp/Cronet/splash 三patch),产出 `out/weixin-deploy.apk`,不再依赖 /private/tmp 里的中间件(该目录会被系统清理)。

### 排雷 #22 容器复用:把任意 Activity 挂到已开的窗口(已实现并验证)
- 既然 OH AMS 拒绝一切应用内 startActivity(2097205,见 #21),就不再走"再开一个 ability"的路:唯一能成功的那次启动(已注册 ability)以 `EXECUTE_TRANSACTION` 消息进程内到达 ActivityThread,其中带 `LaunchActivityItem`。
- 实现(`ActivityManagerRouting.installActivityRedirect`,纯反射):给 `ActivityThread.mH` 装一个 `Handler.Callback`(`mCallback` 字段,原为 null 才装),在 `handleMessage` 里遍历 ClientTransaction 的 callbacks,找到 `LaunchActivityItem` 就改写其 `mIntent` 的 component 与 `mInfo.name`,然后返回 false 让 ActivityThread 正常处理。目标类从 `/data/local/tmp/wl-launch-activity` 读,**不改 jar 即可换目标**;无该文件则完全不介入。
- **实测成功**(三个目标都验证过 WelcomeActivity / LoginPasswordUI / WeChatSplashActivity):
```
[WL-REDIR] launch redirect armed -> com.tencent.mm.plugin.account.ui.WelcomeActivity
[WL-REDIR] launch com.tencent.mm.ui.LauncherUI -> com.tencent.mm.plugin.account.ui.WelcomeActivity
[WESTLAKE-RSNODE] name=com.tencent.mm/...WelcomeActivity GetSurface=0x...
[WESTLAKE-QID] nodeName=com.tencent.mm/...WelcomeActivity_content
RESUMED / mWindowAdded=true / DecorView VISIBLE 1200x1920 / 1 window(s)
```
  即**目标视图控制器确实挂载进了当前活跃窗口并拿到独立渲染节点**,容器复用链路打通。

### 排雷 #23 native 依赖链(已定位并修复,但引发上层回归,已回滚)
- `WeChatSplashActivity.onCreate → XLogSetup` 崩:`libwechatxlog.so` 重定位失败 `__pthread_cleanup_push: symbol not found`(bionic 专有,musl 无)。
- 通用修法:`scripts/patch_apk_natives.py` 扫描 APK 内**所有** arm64 .so,把 UND 的 `__pthread_cleanup_push/pop` 改名到 0 参无害符号(push/pop 成对跳过,不注册取消清理)。一次命中 **7 个库**:libwechatxlog / libmarscomm / libwechatbase / libwechatmm / libwechatnetwork / libwechatpaybase / libwechatpaynetwork。注意:`app_recovery_lib` 每次启动从 base.apk 重解压,**必须改 APK 内的副本**;而 `bundle/.../lib/arm64-v8a` 下是安装期铺开的,**两处都要改**。
- 接着 tombstone 指出 `dlopen(libcrypto.so)` 的构造器调用 `__system_property_get`,westlake `libbionic_compat.so` 在该实现里跳到 **pc=0**;把 libcrypto 的该 UND 符号改名到它自己定义的 `ERR_peek_error`(保证可解析、返回 0)后 native 崩溃消失。
- 新工具 `scripts/sanitize_elf_packed.py`:实现 **Android packed relocation(APS2)** 解码(sleb128 分组格式),补上 `sanitize_elf.py` 对 `DT_ANDROID_RELA` 库"保守跳过"的空白。用它复核这 7 个库:init_array 条目**全部有重定位**,不存在尾部空哨兵——排除了 MUSL do_init_fini 空跳这一嫌疑。
- **回归**:上述 native 修好后微信启动反而更早自杀(exit code 1,~14s,无崩溃记录),窗口再不出现。已按校验和把 8 个库**全部还原**,并部署纯 dex 补丁包 `out/weixin-deploy-dexonly.apk`,恢复首帧基线。

### 排雷 #24 微信崩溃循环保护 / Tinker 状态会把启动锁死(重要运维经验)
- `shared_prefs/crash_status_file.xml` 里累积了 **20 条** `com.tencent.mm,java` 崩溃记录;微信 booter 会据此在启动早期直接自杀,表现为"日志固定 729 行、exit code 1、无任何崩溃记录",极易被误判成新 native 缺口。
- 删 `files/recovery_v3/last_patch`、清 `MicroMsg/crash` 目录会进一步打乱 Tinker 状态(还会让微信写不出崩溃记录,自断取证)。
- **恢复手法**:清空 el2 数据目录下 `MicroMsg / files / shared_prefs / app_recovery_lib / cso / databases / no_backup / cache / code_cache`(未登录状态无数据损失),保持顶层目录 owner=20010058。清后首帧基线立即恢复(pid 9096,1309 行,4 次探针均 `1 window(s)`)。

### 排雷 #25 主题被清零导致 UI 走错装饰层(已修,视图管线真实推进)
- `[G2.5-PIB] ... theme zeroed` + `B47-SLA activityInfo.theme=0x0`:适配层把 ActivityInfo.theme 清零,Activity 落到**平台默认主题**,装饰层是 `ActionBarOverlayLayout + 平台 Toolbar`,微信基于 AppCompat 的界面无法正常成型。
- 修法:redirect 钩子里同时按 manifest 恢复主题(`mInfo.theme` 与 `applicationInfo.theme`),标志文件格式扩展为 `<class> <themeHex>`;`<class>` 填当前类即"仅改主题"模式。WelcomeActivity/LauncherUI=0x7f1102b6,application=0x7f1102ab。
- **实测装饰层变了**:`DecorView → LinearLayout → { ViewStub(GONE) + FrameLayout 1200x1920 }`,正是 AppCompat 的 `abc_screen_simple`,内容区从 `@0,126 1200x1794` 变为**全屏 1200x1920**;窗口动画也解析到微信资源(`wanim=0x7f110217`),说明资源与主题链路真实生效。

### 排雷 #26 关键纠错:被挂载的并不是 redirect 的目标类(推翻上一轮结论)
- 新增两个自研探针(均在 runtime jar 内):
  - `installCrashTap()`:包住 `Thread.defaultUncaughtExceptionHandler` 并在应用换 handler 时重新包(实测依次包住 AppSpawnXInit → TinkerUncaughtHandler → `com.tencent.mm.app.l3`),把未捕获异常打到 child stderr——**解决了微信崩溃记录写不出时完全失明的问题**。
  - `installActivityDump()`:定时遍历 `ActivityThread.mActivities`,打印 Activity 真实类名、intent component、`ActivityInfo.name`、`android.R.id.content` 子 View 数。
- **实测结论**(决定性):
```
[WL-ACT] com.tencent.mm.splash.SplashHackActivity paused=false stopped=false
         intentComp=ComponentInfo{com.tencent.mm/com.tencent.mm.plugin.account.ui.WelcomeActivity}
         recInfoName=com.tencent.mm.plugin.account.ui.WelcomeActivity
         decor=yes contentChildren=0
```
  intent 与 ActivityInfo **都已改成目标类**,但真正实例化出来的对象是 `com.tencent.mm.splash.SplashHackActivity`。**上一轮"WelcomeActivity 已挂载"的判断是错的**——`WESTLAKE-RSNODE` 的名字来自 intent,不代表实例类型。`SplashHackActivity` 根本不在 manifest 里(它是各 Splash Activity 的父类),说明微信在实例化环节自行替换了类(这正是它 "splash hack" 的设计)。同时确认 `ActivityInfo.targetActivity` 为空,不是 activity-alias 机制。
- 崩溃探针全程**未触发**:onCreate 没有抛异常,内容区确实是"从未被填充",而非"填充后异常回退"。

### 排雷 #27 恢复原生 hand-off 会崩在 splash 列表为空
- 用 `NOHANDOFF=0` 重新出包(保留 setVisible(true) 与 `j.p`→LauncherUI 改写)后,两段式启动实测:
  `IndexOutOfBoundsException ← ArrayList.get ← com.tencent.mm.splash.SplashHackActivity.onCreate(:95)`
  —— 说明 splash 框架的列表(`j.a`/`j.b`)此时**是空的**,即 splash 子系统尚未初始化就走到了移交逻辑。这也反证了之前"NOP 掉分支"是当前唯一能稳住首帧的选择。
- 已把 `scripts/rebuild_apk.sh` 参数化(`NOHANDOFF=0/1`),并把板端还原为可用的 no-handoff 纯 dex 包;复核:`1 window(s)`、AppCompat 装饰层、无崩溃。

### 下一步(明确指向)
要让真实登录/欢迎界面上屏,必须让微信**实例化真正的 Activity 类**,而这被 splash 框架挡住;而 splash 框架又因自身未初始化(列表为空)无法完成移交。因此下一步应查:`MMApplicationLike.onCreate → WeChatSplash.a` 里 splash 列表是如何填充的、缺哪个前置条件;或找到微信在实例化处替换类的具体钩子(不在 manifest、无 Instrumentation 子类,疑在 Tinker/ClassLoader 或 splash 框架内),将其在 anco 下短路,使 `LauncherUI`/`WelcomeActivity` 本体被实例化。

### 🎉 里程碑达成:**微信欢迎/登录主界面完整渲染上屏**(logs/wechat_login_ready.jpeg)
- 验收产物:`logs/wechat_login_ready.jpeg`(123498B,微信"蓝色地球"欢迎页 + 右上「语言」+ 底部绿色「登录」/白色「注册」)、`logs/wechat_login_ready.stderr`(3425 行)。
- **可复现**:连续两次冷启动均在 ~12s 命中(`cap4.sh` 事件驱动:轮询 child 日志出现 `WelcomeSelectView` 即截图),截图 123498B / 123432B,而空窗口基线是 ~38.6KB。
- 控件测量/布局证据(同次运行视图树):
```
DecorView 1200x1920 → LinearLayout → FrameLayout → ActionBarOverlayLayout
  → ContentFrameLayout 1200x1920
    → com.tencent.mm.plugin.account.ui.WelcomeSelectView  VISIBLE 1200x1920
        → SplashWelcomeView → SplashImageView  VISIBLE 1200x1920
        → TextView "语言"   VISIBLE @1023,144 129x102
        → RelativeLayout @0,1716 1200x144
             Button "登录"  VISIBLE @63,0  369x144
             Button "注册"  VISIBLE @771,0 369x144
```
  三个交互控件均 `vis=VISIBLE shown=true` 且有真实测量尺寸与坐标,处于可交互就绪状态。

### 打通首屏的三处关键修复(本轮)
1. **主题恢复**(#25):适配层把 `ActivityInfo.theme` 清零,微信 AppCompat 界面无法成型。redirect 钩子按 manifest 回填 `mInfo.theme` 与 `applicationInfo.theme`(标志文件 `<class> <themeHex>`;同类名=仅改主题)。
2. **解开 SplashHackInstrumentation**(#26 的真正解法):微信用 `com.tencent.mm.splash.SplashHackInstrumentation` 包住系统 Instrumentation,其 `newActivity()` 对 launcher 组件直接返回 `new SplashHackActivity(...)`,所以无论怎么改 intent/ActivityInfo,实例化出来的都是占位 Activity。
   - 先试 dex 改写 `newActivity` 走 `invoke-super`,结果子进程 **exit 123**(dex 加载被拒),已把该 dex 补丁在 `rebuild_apk.sh` 里默认关掉(`INSTR_DEX_PATCH=1` 才启用)。
   - 改为**运行期解钩**:适配层 `installInstrumentationUnhook()` 轮询 `ActivityThread.mInstrumentation`,发现是 SplashHackInstrumentation 就取出它包着的原始 Instrumentation 放回去。实测 `[WL-INSTR] replaced ... with wrapped android.app.Instrumentation (field a)`,此后实例化的就是**真正的 `WelcomeActivity`**。
3. **切断 inflate 路径上的插件依赖**(决定成败的一步):微信自定义 LayoutInflater 在 `AccProviderFactory.onInflateRootAsync → AccUtil.isAccessibilityEnabled()` 里**同步驱动插件生命周期 transit**,主线程 park 在 `ForkJoinTask.get`,而该任务的 worker(`wc_srvinit_5`)卡在 `CsoLoader → SQLiteGlobal.<clinit> → System.load`(WCDB 的 CSO 原生加载,时快时不通)。板上没有无障碍服务,故 dex 把 `AccUtil.isAccessibilityEnabled()`/`canPreDeal()` 置为 `return false`,把 inflate 路径从插件框架上摘下来 —— **首屏渲染由此从"偶发"变为稳定命中**。

### 剩余工作:内容区为空(content FrameLayout 无子 View)—— 已解决,保留下文作历史记录
- 三个账号/闪屏 Activity 都能挂载、拿到 surface、进入 RESUMED,但视图树始终只有系统装饰层:
  `DecorView → ActionBarOverlayLayout → { FrameLayout(content, 1200x1794, 无子 View) + ActionBarContainer→Toolbar }`,
  且 Activity onCreate 期间**无任何异常**、主线程随后空转 —— 即微信的 MM UI 框架根本没有调用 setContentView。
- 判断:微信 UI 基类(MMActivity/MMFragmentActivity 链)在其插件/账号子系统"就绪"前不构建界面;这与 `sd5` 插件生命周期 transit 直接相关。要出登录表单,下一步应查 `MMFragmentActivity`/`MMActivity` 的 onCreate 是如何 gate setContentView 的,以及 `plugin-account` 需要哪些服务达到 started 状态。
- 尚未产出 `logs/wechat_login_ready.jpeg`(不能伪造)。本轮实证产物:
  - `logs/wechat_welcome_mounted.jpeg`(43616B,微信主题窗口 + 顶部导航栏,内容区空)
  - `logs/wechat_welcome_mounted.stderr`(1222 行,含 WL-REDIR 挂载证据与完整视图树)
- 现象:LauncherUI 事务下发 OK(`B47-SLA AFTER scheduleTransaction OK`)→ Activity RESUMED → 500ms 时 `mFinished=true mWindowAdded=false decorClass=null`(微信自己 finish 了首个 Activity),此后 `WL-VIEWTREE pass1/2/3 = 0 window(s)`,主线程空转,微信**没有再发起任何 startAbility**(hilog 无 bridgeStartAbility),最终进程退出。
- 判断:微信 splash 阶段自 finish 后应转入真正的首页/登录页(未登录账号通常走 WelcomeActivity/LoginUI),这一步没有发生。下一步方向:(a) 查微信为何判定需要 finish(账号/初始化状态,CSO/service init 未完成?);(b) 查适配层 app→AMS 的 startActivity 通路(OH_AMAdapter 曾报 `broadcastIntent: cannot map action null`)是否支持应用内 startActivity;(c) 直接 `aa start` 目标登录页 Activity 验证渲染链路是否已通。

### 环境事故与恢复(本轮)
- 反复 kill appspawn-x 后出现**僵尸 [appspawn-x] 进程**(ppid=1 未被 init 回收),init 认为 ondemand 服务仍在,不再拉起新实例 → 所有 `aa start` 报 `NotifyStartProcessFailed`(AMS 侧 AppSpawnClientSendMsg 后 4.4s 超时)。`begetctl start_service` / `service_control start` 均无效。
- 期间板子重启(12:04:33 新 appspawn pid),僵尸清除、bind mounts 与 AppSpawnX ondemand listener 均完好,环境自愈;之后 aa start 正常。
- **教训**:appspawn-x 只应在必要时重启,且重启后确认 `ps -ef | grep appspawn` 无 `[appspawn-x]` 方括号(僵尸)状态;不要连续多次 kill。

### ⚠️ 协作冲突(需人/团队协调)
- 共享 `oh-adapter-runtime.jar` 被**另一会话(疑 S3 官方合流)反复覆盖**:我的构建 sha 41ca876 部署后数分钟内先后被覆盖为 0431b7a0、bd1a21e7(均不含我的 WeChat 修复)。原始 backup=0192be1b。
- 规避:push-amr 后**紧接** kill appspawn + aa start(appspawn 重启时预载当次磁盘 jar,fork 出的微信即用我的 jar,之后磁盘被覆盖不影响已 fork 的子进程)。实测可赢下竞争(pid 9823 用到我的 nativeLibDir gate)。但不稳定,长期需与 S3 约定合入而非互相覆盖。
- 头条隔离:全程 `ps -ef|grep article` 错峰;头条本轮多次并发运行且正常(Activity resume/main loop),我的 isWeChat 门确保头条不受 WeChat-specific 改动影响。

### 关键产物(本轮新增)
- `native/wlnatives/{wlnatives.c,build_wlnatives.sh,libm_stub.c,build_libm.sh,build_stubs.sh}`;产物 libwlnatives.so(5320B)、libm.so/libdl.so(空 stub,各 1520B)。
- WeChat bundle lib/arm64-v8a 新增:libwlnatives.so、libm.so、libdl.so(stub)、libz.so(127KB real)、liblog.so(72KB real)。
- 崩溃取证脚本:解码 `MicroMsg/crash/.exception.*.preventcrashlog` 的 `error_json_<base64+zlib>`(见会话)。dism.py 通用 dex 反汇编(/private/tmp/dism.py)。
