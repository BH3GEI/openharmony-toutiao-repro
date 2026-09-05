# 今日头条 OpenHarmony 首帧复现记录

**结论**：今日头条 `SplashActivity` 开屏画面（「看见更大的世界」+ 头条 Logo）已在 OpenHarmony 6.1.0.31 / DAYU200 上完整渲染上屏，冷启动可复现。

- 验收截图：`frames/FIRSTFRAME-splash-clean.jpeg`（1200×1920，冷启后 t≈11s）
- 基座：OpenHarmony 6.1.0.31（架构冻结 L01-02-VERSION-FREEZE-20260711，**未升级系统**）
- 设备：DAYU200，serial `5ce1227d00000000000000000923012c`
- 约束遵守：未对 `libart.so` 做二进制 patch；未修改 BCP 的 `oh-adapter-framework.jar`；未重烘 boot image；未裸机拉起 `appspawn-x`（全部走 `aa start` + 系统托管的 ondemand 服务）

---

## 一、问题定位与解法汇总

按发现顺序排列。每一项都是上一项修完后才暴露出来的。

### 1. ART 栈检查 FATAL —— FFRT 协程栈与 musl pthread 栈范围冲突

```
FATAL [thread.cc:1393] Check failed: FindStackTop() > reinterpret_cast<void*>(tlsPtr_.stack_end)
  art::Thread::InitStackHwm() ← Thread::Init ← Thread::Attach ← Runtime::AttachCurrentThread
  ← liboh_adapter_bridge _JavaVM::AttachCurrentThread ← AdapterBridge::getEnv()
  ← AdapterEventSubscriber::OnReceiveEvent ← libffrt CoStartEntry
```

**根因**：中止线程是 `OS_FFRT_2_0`，正跑在 ffrt **换入的协程栈**上（`CoStartEntry`），而 ART 通过 `pthread_getattr_np()` 拿到的是**该 worker pthread 自己的栈**。实测 `sp=0x7efc33a360`，ART 算出 `stack_end=0x7efcb45000` —— 当前帧在它以为的栈底下方 8.4 MB，断言必然失败。**与栈大小无关，`ulimit -s` / 扩栈都修不了。**

**解法**：在既有的 LD_PRELOAD 位（`libwestlake_stackgrow.so`）拦截 `pthread_getattr_np`。当调用方问的是自己、且返回区间不含当前帧时，改用 ffrt 自己导出的 `ffrt_get_current_coroutine_stack()` 汇报真实协程栈边界。

> 中间踩过一坑：最初用 `/proc/self/maps` 整块 VMA 汇报，把 ffrt `CoRoutine` 头部（含 `CoStackCheck` 校验的 canary）也算进可用栈，Java 深递归压穿 canary → `CoStackCheck` abort。改用 ffrt 上报的精确 1023 KB 后消失。

### 2. `StackOverflowError: stack size 128KB` —— 预触栈被编译器优化掉

musl 的 `pthread_getattr_np()` 对**主线程**返回的是「已增长到的区间」（内部用 `mremap` 向下探测），所以 ART 看到多大栈完全取决于预触了多少。原 `libwestlake_stackgrow.so` 就是干这个的。

**根因**：重写时预触用的递归被 `-O2` 尾调用优化成了单帧循环，一个字节都没触到。
**解法**：`__attribute__((noinline, optnone))` + 递归后仍使用局部帧 + `-fno-optimize-sibling-calls`。

### 3. App 自有 ContentProvider 被 OH DataShare bridge 永久顶替

```
07:52:52.437  OH_CPRegistry: creating OH bridge for ...pm.PPMP     ← Mira 在 Application.<init> 请求
07:52:52.438  getContentProvider ...pm.PPMP -> ContentProviderBridge
07:52:54.355  W ActivityThread: Content provider PluginPackageManagerProvider
                already published as ...pm.PPMP                    ← 真 provider 1.9 秒后被拒绝
```

**根因**：AOSP 是「先造 Application、后 `installContentProviders`」，但 Mira/Tinker 在 `Application.<init>`/`attachBaseContext` 里就对**自己的** authority 调 `ContentResolver`。此时 `ContentProviderRegistry` 还不认识它，于是伪造一个 OH DataShare bridge 返回；AOSP 把这个 bridge 注册进 `mProviderMap`，等真 provider 装载时发现 authority 被占 → 跳过。死 bridge 永久占位，`call("query_binder")` 返回 null → `PluginPackageManager.sInstance` 永远为 null → Mira 全线 NPE。

**解法（避开 BCP 的关键手法）**：修复点本在 BCP 的 `ContentProviderRegistry`，但 `IActivityManagerSingleton.mInstance` 是在**非 BCP 的 `oh-adapter-runtime.jar`** 里由 `AppSpawnXInit` 用 `Class.forName("adapter.activity.ActivityManagerAdapter")` 反射注入的。于是：

1. 新建 `adapter.activity.ActivityManagerRouting extends ActivityManagerAdapter`，override `getContentProvider`；
2. 把 dex 里那个字符串**等长改写**成 `adapter.activity.ActivityManagerRouting`（39 字节，字符串表排序位置不变——邻居是 `ActivityClientControllerAdapter` 和 `ActivityTaskManagerAdapter`，`ActivityM…` 仍严格居中）；
3. `d8` 把新类**合并进原 dex**，仍是单个 `classes.dex`。

对本 App 声明的 authority 返回 `provider == null` 的真 `ProviderInfo` holder，AOSP `installProvider()` 就地实例化 —— 正是真机 AMS 的行为。

### 4. `ProviderInfo.applicationInfo.uid` 未设置 → mProviderMap 键 userId 错位

部署版 framework 实测：

```
installProviderAuthoritiesLocked:
  000e: iget v1, v1, ApplicationInfo.uid:I
  0010: invoke-static {v1}, UserHandle.getUserId(I)I     → uid=0 ⇒ userId 0
```

而查找侧 `ContentResolver` 用 `mContext.getUserId()` = `getUserId(20010057)` = **200**，键永远对不上。
**解法**：`AppSchedulerBridge.buildProvidersFromManifest` 造的合成 `ApplicationInfo` 补上 `uid = Process.myUid()`（dex 等长原地补丁，见"遗留项"）。

### 5. 每次 provider 获取白等 20 秒 —— holder 少了 `mLocal`

AOSP `ActivityThread.acquireProvider`：

```java
if (holder != null && holder.provider == null && !holder.mLocal) {
    key.mLock.wait(CONTENT_PROVIDER_READY_TIMEOUT_MILLIS);   // 20s：以为别的进程在发布
}
```

而 Mira 是在持有 `PluginPackageManager.class` 监视器时发起的，导致主线程和一堆线程全堵在那个类锁上。
**解法**：返回的 holder 设 `mLocal = true`，直接走 `installProvider()` 就地实例化。

### 6. Mira `TreeMap.get(null)` —— PM 结果缺 `ComponentInfo.processName`

```
NullPointerException at java.util.TreeMap.getEntry
  at com.bytedance.mira.am.PluginActivityManagerProvider.c
     → self.a.get(providerInfo.processName)   // processName == null
```

真机上 `ComponentInfo.processName` 恒为应用进程名，adapter 留了 null。
**解法**：包一层 `IPackageManager`（`ActivityThread.sPackageManager`），对 `query*/resolve*/get*` 的返回值递归回填 `processName`（`ResolveInfo` / `PackageInfo.activities|services|receivers|providers` / `ParceledListSlice`），取值优先级 `applicationInfo.processName` → `applicationInfo.packageName` → `packageName` → `ActivityThread.currentProcessName()`。实测一次 `getPackageInfo` 补 54 个。

### 7. `PermissionManager` 为 null → `SplashActivity.onResume` 崩溃

```
NPE: PermissionManager.shouldShowRequestPermissionRationale(String) on a null object reference
  at ApplicationPackageManager.shouldShowRequestPermissionRationale
  at Activity.shouldShowRequestPermissionRationale ← SplashActivity.onResume
```

`ApplicationPackageManager` 构造时缓存 `context.getSystemService(PermissionManager.class)`，该 fetcher 走 `ServiceManager.getServiceOrThrow("permissionmgr")`，adapter 无此路由。
**解法**：往 `ServiceManager.sCache` 注册一个只返回默认值的桩 binder（adapter 本来就用这个通道注册 `user` / `mount`）。`IBinder` 与 `IPermissionManager` 各用一个动态代理互相挂钩，让 `Stub.asInterface()` 能通过 `queryLocalInterface` 拿到接口实现。

### 8. **`libz.so` 缺失** → `libalog.so` / `libkeva.so` 加载失败（关键解锁）

```
MUSL-LDSO: Error loading shared library libz.so: (needed by .../libalog.so)
MUSL-LDSO: Error loading shared library libz.so: (needed by .../libkeva.so)
```

`ALog.createInstance()` 在 `ALog.sConfig == null` 时返回 null，而 `ALog.init` 依赖 `libalog.so`。OpenHarmony 的 zlib 叫 `libz.z.so`，Android native 库按 `libz.so` 找不到。
**解法**：把 route-a 里的 `libshared_libz.z.so`（AOSP zlib）以 `libz.so` 之名放进 App 的 native 搜索路径。

> 这一项修完，开屏内容才真正被填充。此前的白屏正是 ALog 未初始化导致 `onActivityResumed` 观察者链断掉。

### 9. 窗口创建被 fail-closed 拒绝 —— LayoutParams flag 白名单

```
WindowManager$InvalidDisplayException: Unable to add window -- the specified window type 1 is not valid
  at ViewRootImpl.setView(ViewRootImpl.java:1426)
[OH_WSA] addToDisplayAsUser delegate returned -10        ← ADD_INVALID_TYPE
```

`WindowSessionAdapter.unsupportedLayoutParamsFlags()` 对白名单外的 flag 一律 `ADD_INVALID_TYPE`。头条开屏要 `FLAG_TRANSLUCENT_STATUS`(0x04000000)、`FLAG_TRANSLUCENT_NAVIGATION`(0x08000000)、`FLAG_DIM_BEHIND`(0x02)，均不在白名单（`SUPPORTED_LAYOUT_FLAGS = 0x81810500`）。
**解法**：包一层 `IWindowSession`（`WindowManagerGlobal.sWindowSession`，需在首个 `ViewRootImpl` 构造前替换），把纯装饰位掩掉再下发。掩码取自 BCP 的 `SUPPORTED_LAYOUT_FLAGS` 字段（反射读取，读不到则用内置镜像）。

结果：`addToDisplayAsUser delegate returned 3`（`ADD_OKAY | IN_TOUCH_MODE | APP_VISIBLE`）。

### 10. 「11 秒被 STOPPED」—— 不是看门狗，是板子锁屏

```
Intent { act=android.intent.action.SCREEN_OFF }
[OH_WSA-relayout] nativeHideWindow session=67 visibility=8
OH callback: state=FOREGROUND->INITIAL -> STOPPED
WMSAttribute: bundle=SCBScreenLock10, zOrder=2000   ← 锁屏在最上层
              bundle=com.ss.android.article.news, visibleForeground=0
```

生命周期回调是完整的（`AbilityTransitionDone rc=0`），OH 侧 `§338 Foreground(persistentId=72) ret=0` 也成功。**不需要放宽任何超时**，只要保持亮屏+解锁，`state #FOREGROUND` 可稳定 60 秒以上。

> 注：`persist.sys.abilityms.timeout_unit_time_ratio` 在排查过程中被我临时改成过 400，**这一项并非必需**，重启即恢复为 boot recovery 脚本设置的 20。

---

## 二、源码与产物清单

```
~/toutiao-repro/
├── README_REPRODUCE.md                                本文件
│
├── amr/                                               ★ 非 BCP 适配层改动
│   ├── src/adapter/activity/ActivityManagerRouting.java   651 行，解法 3/4/6/7/9 全在这里
│   ├── stubs/                                             5 个 compile-only stub（无 android.jar 时用）
│   │   ├── adapter/activity/ActivityManagerAdapter.java
│   │   ├── android/app/ContentProviderHolder.java
│   │   ├── android/app/IApplicationThread.java
│   │   ├── android/content/pm/ProviderInfo.java
│   │   └── android/os/RemoteException.java
│   ├── build_amr.sh                                       javac + d8 合并构建脚本（在 WSL 上跑）
│   ├── oh-adapter-runtime.amr.jar                         ★ 成品，直接推板子即可
│   └── oh-adapter-runtime.jar.orig                        原始 jar（回滚用）
│
├── stackfix/                                          ★ ART 栈修复
│   ├── wl_stackgrow.c                                     解法 1/2 源码
│   └── libwestlake_stackgrow.so                           aarch64-ohos 成品
│
├── frames/
│   ├── FIRSTFRAME-splash-clean.jpeg                    ★ 验收截图（干净版构建）
│   └── FIRSTFRAME-splash.jpeg                             验收截图（含调试日志版）
│
├── hdc-remote / hdc-relay.ps1                          Mac → yao-win → 板子 的 hdc 中继
└── toutiao/                                            团队原有 deploy_asx.sh / native shim 等
```

### 板上落点

| 产物 | 板上路径 | 备注 |
|---|---|---|
| `oh-adapter-runtime.amr.jar` | `/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar` | bind 到 `/system/android/framework/`；原件备份 `/data/local/tmp/oh-adapter-runtime.jar.bak-preuid` |
| `libwestlake_stackgrow.so` | `/data/pr03-74e6-portable/android/lib64/libwestlake_stackgrow.so` | 已在 `appspawn_x.cfg` 的 LD_PRELOAD 中；原件备份 `/data/local/tmp/stackgrow-orig-v1.so` |
| `libz.so` | `/system/android/lib64/libz.so`（源）<br>`/data/app/el1/bundle/public/com.ss.android.article.news/android/lib/arm64-v8a/libz.so`<br>`/data/app/el2/100/base/com.ss.android.article.news/app_librarian/default.version.200400/libz.so` | 由 route-a `libshared_libz.z.so` 复制而来 |

---

## 三、标准复现步骤

### 0. 前置：板子处于 pr03 运行时（7 个 bind mount 就位）

```bash
H=~/toutiao-repro/hdc-remote
$H shell "sh /data/pr03-74e6-portable/runtime/pr03-runtime-recover.sh >/dev/null 2>&1; \
          cat /data/service/el1/public/appspawnx/pr03-boot-recovery.txt"
# 期望 state=READY / mount_count=7
```

> 若刚重启且 recovery 未自动跑，上面这条会补上。`appspawn-x` 是 ondemand，不要手工拉起。

### 1. 部署三件套

```bash
H=~/toutiao-repro/hdc-remote

# (a) 适配层 jar
$H file send ~/toutiao-repro/amr/oh-adapter-runtime.amr.jar /data/local/tmp/rt-amr.jar
$H shell "T=/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar; \
          cp -f /data/local/tmp/rt-amr.jar \$T; chmod 0644 \$T; chown root:root \$T; \
          chcon u:object_r:system_file:s0 \$T"

# (b) ART 栈修复
$H file send ~/toutiao-repro/stackfix/libwestlake_stackgrow.so /data/local/tmp/wl_new.so
$H shell "T=/data/pr03-74e6-portable/android/lib64/libwestlake_stackgrow.so; \
          cp -f /data/local/tmp/wl_new.so \$T; chmod 0644 \$T; chown root:root \$T; \
          chcon u:object_r:system_lib_file:s0 \$T"

# (c) libz.so 兼容名（仅首次需要）
$H shell 'G=/system/lib64/westlake/route-a/74e6f75976087d7890088b29c08482f17573a588fa5857cb6ca39264838ce16d; \
  cp -f $G/libshared_libz.z.so /data/pr03-74e6-portable/android/lib64/libz.so; \
  chmod 0644 /data/pr03-74e6-portable/android/lib64/libz.so; \
  chcon u:object_r:system_lib_file:s0 /data/pr03-74e6-portable/android/lib64/libz.so; \
  A=/data/app/el1/bundle/public/com.ss.android.article.news/android/lib/arm64-v8a; \
  B=/data/app/el2/100/base/com.ss.android.article.news/app_librarian/default.version.200400; \
  for D in $A $B; do [ -d $D ] && cp -f /system/android/lib64/libz.so $D/libz.so && \
    chmod 0644 $D/libz.so && chcon $(stat -c %C $D/libalog.so) $D/libz.so; done'
```

### 2. 保持亮屏并解锁（**必须，否则 11 秒后被锁屏 STOP**）

```bash
$H shell "power-shell wakeup; power-shell timeout -o 3600000; power-shell dump -t"
$H shell "uinput -T -m 600 1600 600 400 400"   # 上滑解锁，必要时执行两次
```

### 3. 冷启动

```bash
$H shell "aa force-stop com.ss.android.article.news; kill -9 \$(pidof appspawn-x); sleep 3; \
          power-shell wakeup; \
          aa start -a com.ss.android.article.news.activity.SplashActivity -b com.ss.android.article.news"
sleep 15
$H shell "snapshot_display -f /data/local/tmp/shot.jpeg"
$H file recv /data/local/tmp/shot.jpeg ./shot.jpeg
```

### 4. 验收标准

```bash
$H shell "aa dump -a | grep -A6 SplashActivity | grep -E 'state #|main name'"
# 期望：main name [com.ss.android.article.news.activity.SplashActivity]
#       state #FOREGROUND      （持续 60s 以上不掉）
```

截图应与 `frames/FIRSTFRAME-splash-clean.jpeg` 一致：渐变背景 +「看见更大的世界」+ 头条 Logo +「火山引擎提供云和AI服务」。约 t≈11s 出现（解释器模式下 Lego 异步初始化较慢，属正常）。

子进程日志中应出现且仅出现这 12 行 `WL-AMR`：

```
[WL-AMR] provider-aware IActivityManager active
[WL-AMR] stub service 'permissionmgr' registered (android.permission.IPermissionManager)
[WL-AMR] stub service 'legacy_permission' registered (android.permission.ILegacyPermissionManager)
[WL-AMR] IPackageManager wrapped for processName back-fill
[WL-AMR] IWindowSession wrapped; supported flags=0x81810500
[WL-AMR] currentProcessName=com.ss.android.article.news appInfo.processName=com.ss.android.article.news
[WL-AMR] pm.getPackageInfo processName back-filled x54 (further back-fills silent)
[WL-AMR] own authority com.ss.android.article.news.pm.PPMP -> local install
[WL-AMR] own authority com.ss.android.common.multiprocess.SHARE_PROVIDER_AUTHORITY143 -> local install
[WL-AMR] addToDisplayAsUser: cleared unsupported window flags 0xc000002 (was 0xd800002)
[WL-AMR] addToDisplayAsUser: cleared unsupported window flags 0x4000000 (was 0x85810500)
```

日志位置：`/data/service/el1/public/appspawnx/adapter_child_<pid>.stderr`

---

## 四、重新构建

### `oh-adapter-runtime.amr.jar`

团队正规构建（`build_adapter.sh`）需要完整 AOSP 树，仅 ECS 可跑。本次在无 AOSP 树的机器上用了等效路径：

```bash
# 在 WSL（有 JDK 17 + Android SDK build-tools 34 的 d8/dexdump）
bash amr/build_amr.sh
```

流程：
1. `javac --release 11` 编译 5 个 compile-only stub；
2. `javac -cp out_stubs` 编译 `ActivityManagerRouting.java`；
3. 对原 `classes.dex` 做等长字符串改写：`adapter.activity.ActivityManagerAdapter` → `adapter.activity.ActivityManagerRouting`；
4. `d8 --min-api 30 --classpath stubs.jar` 把新 class 与改写后的 dex **合并**为单个 `classes.dex`；
5. 连同原 `META-INF/MANIFEST.MF` 重新打包为 jar。

### `libwestlake_stackgrow.so`

```bash
LLVM=/home/yao/ohsdk/linux/native-x/native/llvm
SYSROOT=/home/yao/ohsdk/linux/native-x/native/sysroot
$LLVM/bin/clang --target=aarch64-linux-ohos --sysroot=$SYSROOT \
  -shared -fPIC -O2 -Wall -fno-optimize-sibling-calls \
  -o libwestlake_stackgrow.so stackfix/wl_stackgrow.c
```

`-fno-optimize-sibling-calls` 与源码里的 `optnone` 缺一不可，否则预触栈递归会被优化成单帧循环（见解法 2）。

---

## 五、遗留项 / 后续应做

1. **`libz.so` 的放置方式是权宜之计**。现在是拷进 App 安装目录。正解是让 adapter 的 native namespace 搜索路径覆盖 `/system/android/lib64`（那里已放了一份），或在 route-a 生成期就提供 `libz.so` 别名。
2. **三项修复长远应回写 BCP 源码**，走 ECS 正规构建：
   - `WindowSessionAdapter.SUPPORTED_LAYOUT_FLAGS` 增补 `FLAG_TRANSLUCENT_STATUS` / `FLAG_TRANSLUCENT_NAVIGATION` / `FLAG_DIM_BEHIND` 的真实映射（或明确降级策略），替代外层掩码；
   - `PackageInfoBuilder` 构造 `ComponentInfo` 时填 `processName`，替代外层回填；
   - `ContentProviderRegistry.acquireProvider` 对本 App 自有 authority 返回真 `ProviderInfo`（`provider == null` + `mLocal = true`），替代 `ActivityManagerRouting` 的拦截。
3. **`uid` 修正目前是 dex 等长原地补丁**，副作用是那个合成 `ApplicationInfo` 的 `sourceDir` / `publicSourceDir` 变成 null（原值 `/system/app/...` 本就是不存在的假路径）。源码版应写作 `appInfoTmpl.uid = android.os.Process.myUid();` 并保留 sourceDir。
4. **画面停在开屏页，未进入信息流**。日志有 `create socket failed for family: 2, errno: 1` / `connect to local address failed`，板子无可用网络，开屏之后的 feed 拉不到数据。
5. **`persist.sys.abilityms.timeout_unit_time_ratio`** 排查期间被临时改成 400，非必需；重启后由 boot recovery 脚本恢复为 20。

---

## 六、下一阶段（主界面 / 信息流）现状

首帧归档完成后继续推进主界面，结论如下。

### 6.1 主界面硬阻塞：`android.graphics.ColorMatrix` 是一个空壳桩

直接拉主界面：

```bash
aa start -a com.ss.android.article.news.activity.MainActivity -b com.ss.android.article.news
```

**Activity 成功启动并跑到布局解析**，然后主线程崩溃：

```
java.lang.NoClassDefFoundError: com.ss.android.image.AsyncImageView
  at LayoutInflater.createView ← rInflate ← inflate
  ← ArticleMainActivity.superOnCreate ← ArticleMainActivity.onCreate
  ← SplashMainActivity.onCreate ← MainActivity.onCreate
Caused by: java.lang.NoSuchMethodError:
  No virtual method set([F)V in class Landroid/graphics/ColorMatrix;
  (declaration of 'android.graphics.ColorMatrix' appears in
   /system/android/framework/adapter-mainline-stubs.jar)
  at com.ss.android.image.AsyncImageView.<clinit>
```

实证（`dexdump` 逐个 boot jar 查类定义）：

| jar | 是否定义 `Landroid/graphics/ColorMatrix;` |
|---|---|
| `framework.jar` classes1–5.dex | 0（全部没有） |
| `oh-adapter-framework.jar` | 0 |
| `adapter-mainline-stubs.jar` | **1（唯一定义）**，且只有 `<init>()V` 一个方法 |

也就是说全系统只有这一个**空壳** `ColorMatrix`，`set(float[])`、`setSaturation()` 等全缺。

而 `adapter-mainline-stubs.jar` 共 190 个类，其余全是货真价实的 mainline API（`android.app.appsearch.*`、`android.bluetooth.*`、`android.adservices.*`、`android.health.connect.*`、`android.app.role.*`、`android.app.job.*`…），**`android/graphics/ColorMatrix` 是里面唯一一个 `android/graphics/*` 类** —— 它不属于 mainline，八成是自动 stub 生成器误扫进来的。

**影响面**：任何用 Fresco/Drawee（`AsyncImageView`）的界面都会在 `<clinit>` 挂掉。头条主界面必经此路，所以走开屏自动跳转还是手工 `aa start` MainActivity，结果一样。

**修法（需 ECS）**：把 `ColorMatrix` 从 `adapter-mainline-stubs.jar` 移除并由 `framework.jar` 提供完整实现，或直接在 stubs 里补全实现。该 jar 已烘进 `boot-adapter-mainline-stubs.{art,oat,vdex}`，**改它必须重烘 boot image**，按铁律不在本机做。

> 这是目前主界面的**唯一**硬阻塞点，且与网络无关——即使有网也会崩在同一处。

### 6.2 开屏为何停住

主线程实测是 **idle 在 Looper**（`epoll_wait` → `MQ_nativePollOnce`），不是死锁、不是卡死：

```
Tid:<pid>, Name:com.ss.android.
#00 ld-musl-aarch64.so.1(epoll_wait+116)
#01 liboh_android_runtime.so(MQ_nativePollOnce+64)
```

即在等一个永远不来的回调/定时器。开屏广告请求走网络，而板子无网（见 6.3），请求既不成功也没走到失败回调，于是停在品牌页。日志里同期只有无害的后台异常（`GeckoManager` 写文件 ENOENT、`okhttp3.internal.platform.Platform` NoClassDefFoundError），均被 `W-ROOM-SURVIVE` 吞掉。

点击屏幕（`uinput -T -c 600 960`）不会推进——品牌页没有"跳过"热区。

### 6.3 板子网络：物理不通

```
ifconfig          → 只有 lo 有 IP；无默认路由
ip route          → 空
ping 223.5.5.5    → Network unreachable
ping www.baidu.com→ Name does not resolve
```

| 接口 | 状态 |
|---|---|
| `eth0`（驱动 `r8152`，USB 网卡） | `operstate=down`，`carrier=0` → **没插网线** |
| `wlan0` | 存在，`ifconfig wlan0 up` 可拉起（拿到 link-local v6），但 WiFi SA 未运行 |
| `sipa_*` | SoC modem 占位接口，无用 |

WiFi 侧：`/system/etc/init/wifi_standard.cfg`（`wifi_manager_service`）与 `wifi_hal_service.cfg` 都在，`/data/service/el1/public/wifi/` 下有 `wpa_supplicant/` 目录，但 **`device_config.conf` 为 0 字节，没有任何已保存的 AP**。

日志里的 `create socket failed for family: 2, errno: 1`（EPERM）已排除两个常见原因：

- `zcat /proc/config.gz | grep PARANOID_NETWORK` → **空**，Android paranoid network 未启用，所以不是缺 gid 3003（子进程 `Groups: 3099`）；
- `getenforce` → **Permissive**，不是 SELinux 拦截。

EPERM 的真实来源尚未定位，怀疑在 adapter 的 `libandroid_native_network_compat.so` 或 OH netsys 侧；但**在没有路由的前提下这一项并不阻塞排查**。

**要让板子上网，需要人工介入二选一**：

1. 给板子插网线（`eth0` 是 r8152 USB 网卡，插上后 `carrier` 会变 1，再 `dhclient`/OH 的 EthernetManager 取地址）；
2. 提供 WiFi SSID + 密码，启动 `wifi_manager_service` 后写入配置连接。

### 6.4 下一步建议顺序

1. **先修 `ColorMatrix`**（ECS 出一版 boot image）——这是主界面的唯一硬阻塞，修完才谈得上信息流；
2. 再解决板子联网（插网线最省事）；
3. 然后回头看开屏的广告回调是否能正常超时进入主界面，若仍不进，再查 `create socket failed` 的 EPERM 来源。

---

## 七、板子联网（已完成）

WiFi 已连上，板子具备完整外网与 DNS。

### 7.1 结果

```
wlan0  inet addr:192.168.3.56  Bcast:192.168.3.255  Mask:255.255.255.0
ping 223.5.5.5        → 2 packets, 0% loss, 24ms
ping www.baidu.com    → 解析到 111.45.11.5，0% loss, 21ms
```

重启后会自动重连（配置已持久化）。板子 RTC 缺失，联网后系统时间从 1970 跳到真实时间，属正常现象。

### 7.2 操作步骤（可复现）

板上没有 `wpa_cli` 之类的命令行入口，`aa start -b com.ohos.settings` 直接拉 ability 会报
`10104001 The specified ability does not exist`，所以走 UI 注入。关键是 **`uitest dumpLayout`**：
`snapshot_display` 截不到 WLAN 密码弹窗（拿到的是黑帧），但 `uitest` 能拿到完整控件树和精确坐标。

```bash
H=~/toutiao-repro/hdc-remote

# 1. 亮屏 + 解锁
$H shell "power-shell wakeup; power-shell timeout -o 3600000; power-shell dump -t"
$H shell "uinput -T -m 600 1600 600 400 400"          # 上滑解锁

# 2. 桌面点「设置」图标（1200x1920 下约在 190,1545）
$H shell "uinput -T -m 600 1880 600 1400 300"         # 手势回桌面
$H shell "uinput -T -c 190 1545"

# 3. 进 WLAN（列表项 y≈702），打开开关（y≈313 右侧）
$H shell "uinput -T -c 600 702"
$H shell "uinput -T -c 1100 313"
sleep 10                                              # 等扫描

# 4. 点 SSID（首个热点 y≈600）
$H shell "uinput -T -c 400 600"

# 5. 用控件树拿密码框/连接按钮的真实坐标
$H shell "uitest dumpLayout -p /data/local/tmp/layout.json"
#   TextInput hint='密码'  bounds=[36,303][1165,393]   → 中心 (600,348)
#   Button(连接)          bounds=[36,951][1164,1041]  → 中心 (600,996)

# 6. 输密码并连接
$H shell "uinput -T -c 600 348"
$H shell "uinput -K -t <password>"
$H shell "uinput -T -c 600 996"

# 7. 验证
$H shell "ifconfig wlan0 | grep 'inet '; ping -c 2 223.5.5.5"
```

> `uinput -K -t <text>` 可直接注入文本，不必逐键点软键盘。

---

## 八、主界面：两个已定位但本机无法收口的阻塞

### 8.1 `ColorMatrix` 空壳 → 需 ECS 重烘 boot image

详见 `colormatrix/README.md`。已备好完整实现与 dex 产物。

本机尝试「把完整实现作为 `classes.dex` 塞进 jar 最前、原 dex 降为 `classes2.dex`」失败——
boot image 记录了每个 BCP jar 的 dex 身份，子进程在运行时初始化阶段直接 abort：

```
art::Runtime::Abort ← art::ClassLinker::CheckSystemClass ← art::Runtime::Init+17172
```

已即时回滚 + 重启，板子恢复正常（开屏仍可复现）。

本机**没有可用的 dex2oat**：`/system/android/bin/dex2oat` 被 `libapk_installer.so` 引用但文件不存在；
Windows/WSL 上只有构建配方（`b-route-dex2oat/` 下仅 `link.sh` / `link-dyn.sh` 与源码）。

### 8.2 应用拿不到网络权限 → socket EPERM → 开屏不跳转

板子联网后，**开屏依然停住**，应用日志仍持续报：

```
MUSL: create socket failed for family: 2, errno: 1     (EPERM)
```

进程补充组对比：

| 进程 | Groups |
|---|---|
| 头条子进程（appspawn-x 拉起） | `3099` |
| SceneBoard（正常 OH 应用） | `1006 1008 1010 1065 3099 3817` |

根因在**安装期**——OH bundle 侧一个权限都没有：

```bash
bm dump -n com.ss.android.article.news | grep -c 'ohos.permission'   # → 0
atm dump -t -b com.ss.android.article.news                           # → "permStateList": []
```

即适配层的 APK 安装器没有把 APK 里的 `android.permission.INTERNET`
（manifest 中确实声明了）映射成 OH 的 `ohos.permission.INTERNET`，
BMS 因此不发放网络相关补充 gid，内核侧 `socket(AF_INET)` 返回 EPERM。

运行期补授权走不通——permission 不在 bundle 申请列表里，`atm` 直接拒绝：

```bash
atm perm -g -i 537691773 -p ohos.permission.INTERNET   # → Failure
```

**修法**：在 APK 安装流程（`libapk_installer.so` / 装包 pipeline）里把 Android 权限映射进生成的
OH module profile，然后重装应用。这也解释了此前排除 paranoid-network（内核未开启）
与 SELinux（Permissive）之后，EPERM 仍无解释的问题。

### 8.3 收口顺序

1. ECS 重烘 boot image，修 `ColorMatrix`（主界面唯一硬阻塞，与网络无关）；
2. 修 APK 安装器的权限映射并重装，让应用真正拿到网络；
3. 两者齐备后开屏广告回调才可能正常完成并跳转 `MainActivity`。

---

## 九、Boot Image 重烘尝试（用宿主机 Android 设备）

### 9.1 关键发现：原始 dex2oat 命令行是可恢复的

板上 `boot.oat` 的 OAT header key-value store 里存着当初的完整命令行：

```bash
strings /system/android/framework/arm64/boot.oat | grep dex2oat64-exp
```

完整内容见 `colormatrix/bake/ORIGINAL-DEX2OAT-CMDLINE.txt`。要点：
`--instruction-set=arm64 --base=0x70000000 --compiler-filter=speed`
`--runtime-arg -Xgc:CMC --runtime-arg -Xverify:none`，9 个 BCP jar 顺序固定：

```
core-oj → core-libart → core-icu4j → okhttp → bouncycastle
→ apache-xml → adapter-mainline-stubs → framework → oh-adapter-framework
```

有了它，重烘不用猜参数。

### 9.2 镜像版本对齐情况

| 设备 | ART 镜像版本 | 架构 |
|---|---|---|
| 鸿蒙板（目标） | **`art\n108`** | arm64 |
| `emulator-5554`（Android 14 / SDK 34） | **`art\n108`** ✅ 版本一致 | **x86_64** |
| `N100CU025C18D000458`（Android 16 / SDK 36） | `art\n118` ❌ 版本不符 | arm64 |

### 9.3 卡住的地方：没有「A14 + arm64 后端」的 dex2oat

在 A14 模拟器上按原始命令行重跑，dex2oat 崩在 ISA 后端：

```
--compiler-filter=speed  → Abort message: 'Unknown InstructionSet: Arm64'
                            at art::JniCallingConvention::Create(...)
--compiler-filter=verify → Abort message: 'Unexpected InstructionSet: Arm64'
```

Google 的**端上** dex2oat 只编进本机 ISA 后端，x86_64 版本没有 arm64 代码生成器。
原始命令行里的 `dex2oat64-exp` 是**主机侧（Linux x86_64）**构建，主机版才带全部后端。

已排查过、都不可用：

- `/home/yao/aosp-14/out/host/linux-x86/bin/` 只有 `d8`，没有 dex2oat；
- android-sdk 无 arm64 系统镜像；
- 原生安卓板是 arm64，但 A16 出 `art\n118`，我方 libart 只认 108；
- `art-build-recipes/.../b-route-dex2oat/` 只有 `link.sh` / `link-dyn.sh` 与源码，无产物。

**所需**：一个 Android 14 版本线、**带 arm64 后端**的 dex2oat（主机 Linux x86_64 构建即可，
就是团队当初那个 `dex2oat64-exp`）。拿到后，把 `colormatrix/adapter-mainline-stubs.cmfix.jar`
替换进去按 `colormatrix/bake/dex2oat-recipe.sh` 跑一次即可。

### 9.4 已备好的产物

`colormatrix/adapter-mainline-stubs.cmfix.jar`（52904B）—— **可直接送去烘的成品**：

- 用 Python 精确摘掉原 dex 里 `Landroid/graphics/ColorMatrix;` 的 class_def
  （class_defs_size 190→189，同步改 map_list 的 TYPE_CLASS_DEF_ITEM，重算 signature/checksum）；
- 再用 `d8` 把完整实现合并回去，输出**单个** `classes.dex`；
- 校验：190 个类、`ColorMatrix` 定义恰好 1 处、`set` 两个重载俱在
  （`set(ColorMatrix)` / `set(float[])`），另含 `setSaturation`/`setConcat`/`setRotate`/
  `setScale`/`setRGB2YUV`/`setYUV2RGB`/`preConcat`/`postConcat`/`getArray`/`reset`。

> 注意：不能用「加 `classes2.dex` 让 BCP 线性查找先命中」的偷懒办法——已实测，
> boot image 记录了每个 BCP jar 的 dex 身份，子进程会在
> `ClassLinker::CheckSystemClass ← Runtime::Init` 直接 abort。必须单 dex + 重烘。

---

## 十、当前状态小结

| 项 | 状态 |
|---|---|
| 开屏首帧（Logo/图文） | ✅ 已达成，冷启可复现 |
| 板子联网（WiFi） | ✅ 已达成，重启自动重连 |
| 应用联网 | ❌ 装包器权限映射缺失（`permfix/` 已备修复件） |
| 主界面 | ❌ `ColorMatrix` 需重烘 boot image（`colormatrix/` 已备修复件），缺 arm64 后端的 A14 dex2oat |

板子当前处于**可用状态**：BCP jar 已还原（md5 与原件一致），开屏正常，WiFi 正常。

---

## 11. 第二夜（2026-09-05 02:00–05:00）：又拆掉 5 个根因

详细过程与全部证据见 `WHITEBOARD.md` 第五、六节；脚本与源码见 `night2/`（`night2/README.md` 有叠加顺序）。

**核心心得：这一夜没有动 boot image，也没有 patch libart。** 之前卡住的 ColorMatrix
被证明可以完全在 App 侧解决（改 `AsyncImageView.<clinit>`），而 conscrypt 缺失也可以
用一个空类塞进 `classes22.dex` 绕开——因为 okhttp 是通过 **Mira 的 App 类加载器**
去 `findClass` 的，根本不查 BCP。**结论：先确认"这个类到底由谁加载"，再决定要不要碰 BCP。**

修好的 5 项（细节见白板表格）：
1. 网络权限 —— 走 `access_token.db`，不重装 bundle
2. `ColorMatrix.set([F)V` —— App dex 等长指令替换
3. okhttp `No platform found on Android` —— 追加 `classes22.dex`
4. `ICUWrapper` 空指针（ICU 74 vs 72）—— `libwlicu.so` 尾跳转 + tttext 等长改名
5. `libnpth.so` fdsan ABI 冲突 —— 从 apk 里删库（改名无效，librarian 会重新解包）

**当前首帧的真实障碍**（有主线程栈为证，不是猜测）：
`MainActivity.onCreate` 已跑完，`onResume` 里 Lego `InitTaskDispatcher` 串行 init，
70s 仍未跑完 → 第一帧排不上。已排除 AMS 超时（ratio 20→400 无变化）与渲染管线故障
（开屏能出完整彩色帧）。

**三个平台级缺口**（需要适配层介入，App 侧无解）：
- 适配层**没有 TLS**：`TLS shim: no real networking (construct-only SSLContext on OH)`
- **PopupWindow 拿不到 OH session**（`tokenAddr=0x0` → `session=1`，无 surface 无输入）
- **JIT 不可用**：`APPSPAWNX_NO_JIT/FORCE_INT` 置 0 后 App 20s 内即死（已还原）

### 新增的排障基建（后续一定用得上）

- `libwestlake_stackgrow.so` 现在能抓 ART 的致命错误：适配层的 liblog 在 sealed.child
  namespace 内解析，libart 的 `LOG(FATAL)` 文本在 hilog / stderr / faultlog 里**都看不到**。
  拦 `__android_log_write_log_message` / `__android_log_logd_logger` 才拿得到正文；
  `abort()` 里做 aarch64 fp 回溯并 park 600s（否则 AMS 的 AppRunningRecord 永久卡死，只能重启整机）。
- **native 崩溃日志在 `/data/log/faultlog/temp/cppcrash-<pid>-*`**，不在 `faultlogger/`。
  ICU 和 npth 两个根因都是从这里一眼看出来的。
- `ActivityManagerRouting` 里的 view-tree dump 与主线程栈探针（**必须跑在非主线程**）。
- `night2/` 下的 dex 工具链（解析 / 反汇编 / 按字符串反查引用）。

---

## 12. 第三轮（09:00–10:00）：Lancet 线索验证 + 死因定案

### 又拆掉两颗雷（都在 `onWaitFeedTimeout -> delayInitNew` 这一条主线程路径上）

| 类 / 方法 | 症状 | 修法 |
|---|---|---|
| `PrivateApiLancetImpl.<clinit>` (classes20.dex, `code_off=0x70ba5c`) | 连读 8 个 `MediaStore.*_CONTENT_URI`，stub 里全没有 → `NoSuchFieldError` 打死主线程 | 入口 4 字节 `sget-object` → `return-void; nop` |
| `HeadsetHelperOpt.p()V` (classes8.dex, `code_off=0x74baf8`) | `AudioManager.getDevices()` → `AudioPortEventHandler.native_setup` 无实现 → `UnsatisfiedLinkError` | 同上 |

两者都以 `sget-object <...>->changeQuickRedirect` 开头（ByteDance hotfix 前导，4 字节 21c），
所以等长替换后**指令数、偏移、跳转目标逐字节不变**。通用脚本：`night2/neutralize.py`。

叠加顺序：`base.final3.apk` →(patch_lancet.py)→ `final4` →(neutralize.py)→ `final5`。

### 自我纠正：探针污染了测量

`wl_artfatal.log` 涨到 **1.26 GB**——日志 tee 阈值设成了 WARN，而这套 libart 每次类加载
都打若干 ERROR 级 `class_linker` 诊断，于是类加载热路径上多了一次「open+write+close 到
几百 MB 文件」。已改为只 tee FATAL。**第 11 节里「主线程只是解释执行慢」的判断被这个
放大过，需要打折看待。**（改掉后死亡时间点未变，所以慢不是唯一因素。）

### 死因定案

`exit with code:134`（不是 signal），我的 abort 钩子未触发 → 是适配层自己的 abort hijack。
每次子进程日志的最后一段完全一致：

```
session=1 SURFACE_CHANGED (wasHidden=true) -> ViewRootImpl drains RT + rebuilds BBQ
[WESTLAKE-GONW] session=36                    <- 切回 MainActivity 主窗
ReliableSurface::ReliableSurface / ~ReliableSurface   <- 刚建即析构
ASSERT FAILED [skia] drawRenderNode called on a context with no surface!
abort() hwui hijack
```

**拿不到 OH session 的 PopupWindow（`session=1`）relayout 时，适配层把 MainActivity
自己窗口的 surface 拆了**，下一帧 drawRenderNode 撞空 → 整个进程 exit(134)。
兜底首帧就是在这一刻被打断的。

试过并否定的两条：给 popup 换 Activity token（方向就错——`type=1000` 子窗口本来就该带
`ViewRootImpl$W`；换了 tokenAddr 非 0 了但仍落 `session=1`）；给子窗口摘
`FLAG_HARDWARE_ACCELERATED`（标志确实清了，但 `ViewRootImpl` 在 `setView()` 就已决定
用不用 HardwareRenderer，`IWindowSession` 这个钩子点太晚）。

### 需要适配层做的（App 侧与 Java 侧都够不着）

`OH_WSA-relayout` / `WESTLAKE-GONW` / `ReliableSurface` 都在 `liboh_android_runtime.so` 内。二选一：
1. 让子窗口（PopupWindow / TYPE_APPLICATION_PANEL）拿到真正的 OH scene session；或
2. **让 `session=1` 的 relayout 不要去 drain/rebuild 其它窗口的 surface** ——
   这条更小，且只要不再 abort，MainActivity 的兜底首帧就有机会画出来。

---

## 13. ✅ MainActivity 首帧达成（2026-09-05 16:31）

`frames/mainactivity/`：
- `MAINACTIVITY-firstframe.jpeg` — 主界面全彩截图（顶栏/频道栏/底部导航齐全）
- `MAINACTIVITY-viewtree.txt` — 120 行 Android view 层级（进程内探针 dump）

### 压垮最后一关的是「子窗口 relayout 的 attrs == null 陷阱」

`ViewRootImpl.relayoutWindow()` 只在 LayoutParams **变化时**才传 attrs，
之后每次 relayout 都传 `null`。所以按当次 `attrs.type` 判断子窗口，会漏掉第 2..N 次：
第一次（带 attrs）被拦下了，第二次放行 → 适配层返回 `SURFACE_CHANGED(2)` →
ViewRootImpl 重绘 → `drawSoftware()` 抛 `Surface was not locked` 打死主线程。

**解法：子窗口注册表 + 释放 mSurface。**
```java
// 弱键，popup 消失后不钉住 ViewRootImpl
sSubWindows = Collections.synchronizedSet(newSetFromMap(new WeakHashMap<>()));

subWindowType(window, attrs):
   attrs != null && type >= 1000        -> 登记并返回
   sSubWindows.contains(window)         -> 命中（attrs == null 也认得）
   window.mViewAncestor -> ViewRootImpl.mWindowAttributes.type   -> 兜底探测
```
三处判定（neutralize / relayout 返回值清零 / surface 失效）全部改用它。

关键一环是 **post-invoke 释放 `ViewRootImpl.mSurface`**：
`setWindowStopped(true)` 挡不住已排上的 traversal，而 AOSP `ViewRootImpl.draw()`
开头恒有 `if (!surface.isValid()) return false;` —— 释放 mSurface 正好打这个闸，
绘制在 lock/unlock 之前被短路。必须放在真实调用**之后**，否则适配层会把 Surface 换回来。

### 验收

| | 修复前 | 修复后 |
|---|---|---|
| 子窗口拦截 | 2 | **13** |
| `ASSERT ... EGL_NO_SURFACE` → exit 134 | 每次 | **0** |
| `Surface was not locked` → exit 1 | 每次 | **0** |
| 进程存活 | 60–80s 必死 | **180s 全程 alive** |
| 截图 | 38 KB 纯白 | **71–72 KB 主界面** |

信息流区域为空（显示「今日头条」水印 + LoadingFlashView），因为适配层 TLS 仍是
`construct-only SSLContext`，拉不到文章 —— 这是**唯一**挡在「有内容的主界面」前面的缺口。
