# 前线交互层冻结清单（S1 → S3）

面向官方统一构建库的合流交接件。这里列的是**前线实际跑在板上的那一份**，
不是设计意图：每一项都能用下面给出的哈希核对。

## 1. 交付物

| 件 | 路径 | sha256 |
|---|---|---|
| 适配层 jar | `amr/build/oh-adapter-runtime.all.jar` | `812a748641e6b2becb9ea5e0bfeabc23f176c7eac144e1aa927ad51e8b68ae1a` |
| 路由类源码 | `amr/src/adapter/activity/ActivityManagerRouting.all.java` | `ea2803c4d5f7c277f4471ffcabf74b2faaaec225ea2ba0f3eaa6904fb8c5fed7` |
| WebSettings 子类 | `amr/src/westlake/webview/InertWebSettings.java` | `46fae05882dc8cc67a7e4f836c675540dc7ee16b50ecb1ebc629f4f9aecc9f3a` |
| 应用 apk | `prebuilts/base.final14.apk` | `509fc7dccd5f89e60387038a945afb6cb96426dadad18f02aa75dbe59ce5e806` |

源码 commit hash 见本文件末尾「源码基线」。jar 不入库（`.gitignore` 排除
`prebuilts/*` 与 `**/build/`），用下面的命令从源码复现，产物应与上表哈希一致。

## 2. 复现构建

```bash
JAVA_HOME=<jdk11+> \
SRC=amr/src/adapter/activity/ActivityManagerRouting.all.java \
OUT=amr/build/oh-adapter-runtime.all.jar \
bash amr/build_amr.sh
```

`build_amr.sh` 只做四步：编译 `amr/stubs/`（仅供 javac，不进产物）→ 编译
`amr/src/` 全量 → `d8` 与 `amr/prebuilt/classes-retarget.dex` 合并 →
换掉 `amr/prebuilt/oh-adapter-runtime.base.jar` 里的 `classes.dex`。
无绝对路径、无网络依赖。

板端安装位置（`/system/android/framework` 是它的 bind mount，要写源）：

```bash
cp oh-adapter-runtime.all.jar \
   /data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar
chcon u:object_r:system_file:s0 <同上>
```

## 3. jar 导出清单

`classes.dex` 186652 字节，58 个 class_def：

| 命名空间 | 内容 |
|---|---|
| `adapter.activity.ActivityManagerRouting` + 32 个内部类 | 前线交互层主体（下节逐项） |
| `adapter.activity.AppSchedulerBridge` + 6 | 沿用 base jar，未改 |
| `westlake.webview.InertWebSettings` | **新增**：具体的 `android.webkit.WebSettings` 子类 |
| `android.net.ssl.SSLSockets` | TLS 桥所需，沿用 |
| `com.android.internal.os.AppSpawnXInit` / `TlsShimProvider` / `PmProxy` / `OHSecureRandom*` + 内部类 | 沿用 base jar，未改 |

**合流注意**：`ActivityManagerRouting` 继承 `adapter.activity.ActivityManagerAdapter`
（在 `oh-adapter-framework.jar` 里），通过 base jar 中被就地改写的 dex 字符串
`adapter.activity.ActivityManagerAdapter` → `adapter.activity.ActivityManagerRouting`
被注入，不在 boot classpath 上。官方产线若自己构建这个类，只需保证该字符串改写等价。

## 4. 运行时方法清单

`ActivityManagerRouting.all.java` 共声明 **107 个方法**（4185 行）。
清单由 `patches/tools/list_methods.py` 从源码直接生成，不是手写的：

```bash
python3 patches/tools/list_methods.py amr/src/adapter/activity/ActivityManagerRouting.all.java
```

数字对得上：编译后的 dex 里这个类有 109 个非合成方法
（107 + `<init>` + `<clinit>`），可用

```bash
python3 patches/tools/dexabstract.py oh-adapter-runtime.all.jar \
    'Ladapter/activity/ActivityManagerRouting;' | grep -c '^concrete'
```

核对（该命令另含 d8 生成的 `-$$Nest` 合成访问器，共 155）。

按职责分组的关键项：

| 组 | 方法 |
|---|---|
| 输入泵 | `startInputPump` `runInputCommand` `injectTap` `injectTapDirect` `injectSwipe` `injectKey` `motionEvent` `setSource` `dispatch` `enqueueOnMain` |
| 窗口选择 | `topInputTarget` `topInputTargetQuiet` `windowType` `viewDimension` `describeTarget` `describeLp` |
| **窗口焦点补投** | `focusOnly` `setWindowFocus` `hasWindowFocus` `dumpWindowFocus` `refocusTop` |
| **返回键与销毁** | `escalateBack` `waitFinishing` `isFinishing` `topActivity` `topActivityRecord` `activityForWindow` `recordForWindow` |
| **销毁后恢复** | `resumeUnderlyingActivity` `scheduleResume` `clearWindowStopped` `showWindow` |
| **单容器导航** | `dumpFragments` `mountFragment` `unmountFragment` `fragmentManager` `findViewById` `viewChildCount` `intMethod` |
| 命中测试 | `performClickAt` `collectHits` `pickClickable` |
| 诊断 | `dumpActivities` `dumpAllWindows` `dumpMainThreadStack` `startViewTreeDumper` `probeFingerprint` `probeWebView` |
| WebView 防崩 | `installWebViewGuard` `swapInGuardedProvider` `inertProxy` `inertWebSettings` `defaultValue` |
| Theme AXML 兜底 | `loadManifestThemes` `parseAxmlThemes` `manifestTheme` `fixTheme` |
| S2 合入项 | `startTlsBootstrap` `loadNativeShims` `hijackTlsShim` `loadVelocityTrackerShim` |
| 反射工具 | `readField` `readFieldValue` `findField` `findMethod` `writeBool` `runOnMain` `runOnMainSync` `uptimeMillis` |

### ActivityManagerRouting.all.java -- 107 methods

- `activityForWindow(Object vri)` -> `Object`
- `appClassLoader()` -> `ClassLoader`
- `attachApplication(IApplicationThread app, long startSeq)` -> `void`
- `awaitRealJsse()` -> `java.security.Provider`
- `backFill(Object result, int depth)` -> `int`
- `causeOf(Throwable t)` -> `Throwable`
- `clearWindowStopped(Object token)` -> `void`
- `collectHits(Object v, int x, int y, int depth, List<Object> out)` -> `void`
- `currentProcessName()` -> `String`
- `defaultValue(Class<?> type)` -> `Object`
- `describeLp(Object lp)` -> `String`
- `describeTarget(Object vri)` -> `String`
- `dispatch(Object vri, Object event)` -> `void`
- `driveConsentDialog(List<?> views)` -> `void`
- `dumpActivities()` -> `void`
- `dumpAllWindows(int pass)` -> `void`
- `dumpFragments()` -> `void`
- `dumpMainThreadStack(int pass)` -> `void`
- `dumpView(Object v, int depth, int index)` -> `void`
- `dumpWindowFocus()` -> `void`
- `enqueueOnMain(final Object vri, final Object event)` -> `void`
- `ensurePackageManagerWrapped()` -> `void`
- `ensureProcessNameVisible()` -> `void`
- `ensureStubServices()` -> `void`
- `ensureWindowSessionWrapped()` -> `void`
- `escalateBack(Object vri, final Object act)` -> `void`
- `fallbackAppCompatTheme()` -> `int`
- `findDeclaredProvider(String authority)` -> `ProviderInfo`
- `findField(Class<?> cls, String name)` -> `Field`
- `findMethod(Class<?> cls, String name, Class<?>... paramTypes)` -> `Method`
- `findViewById(Object view, int id)` -> `Object`
- `findViewWithText(Object v, String want, int depth)` -> `Object`
- `fixComponent(Object component)` -> `int`
- `fixTheme(Object component)` -> `int`
- `focusOnly(Object target)` -> `void`
- `fragmentManager(Object act)` -> `Object`
- `getContentProvider(IApplicationThread caller, String callingPackage, String nam...)` -> `ContentProviderHolder`
- `hasWindowFocus(Object vri)` -> `boolean`
- `hijackTlsShim()` -> `void`
- `inertProxy(final Class<?> iface, final int depth)` -> `Object`
- `inertWebSettings(Class<?> want)` -> `Object`
- `injectKey(int keyCode)` -> `void`
- `injectSwipe(float x1, float y1, float x2, float y2, int durationMs)` -> `void`
- `injectTap(float x, float y)` -> `void`
- `injectTapDirect(final float x, final float y)` -> `void`
- `installWebViewGuard()` -> `void`
- `intMethod(Object target, String name)` -> `int`
- `isFinishing(Object act)` -> `boolean`
- `loadAlooperShim(ClassLoader cl)` -> `void`
- `loadManifestThemes(Object appInfo)` -> `void`
- `loadNativeShims()` -> `void`
- `loadSqliteShim(ClassLoader cl)` -> `void`
- `loadVelocityTrackerShim()` -> `void`
- `manifestTheme(Object component, Object appInfo)` -> `int`
- `maybeSelfTest(Class<?> boot)` -> `void`
- `motionEvent(long downTime, long eventTime, int action, float x, float y)` -> `Object`
- `mountFragment(final String fqcn, final String[] kv)` -> `void`
- `nativeLoad(String path, ClassLoader loader)` -> `String`
- `neutralizeEverySubWindow()` -> `boolean`
- `orSelf(Object maybe, Object self)` -> `Object`
- `parseAxmlThemes(byte[] b)` -> `Map<String, Integer>`
- `performClickAt(final float x, final float y)` -> `void`
- `pickClickable(List<Object> chain)` -> `Object`
- `probeFingerprint()` -> `void`
- `probeSqlite()` -> `void`
- `probeWebView()` -> `void`
- `readField(Class<?> c, Object o, String name)` -> `Object`
- `readFieldValue(Object target, String name)` -> `Object`
- `readIntField(Class<?> c, Object o, String name)` -> `int`
- `readLines(File f)` -> `List<String>`
- `recordForWindow(Object vri, Object exclude)` -> `Object`
- `refocusTop()` -> `void`
- `registerBinderStub(String serviceName, String interfaceName)` -> `void`
- `report(String what, Probe c)` -> `void`
- `reportSslSocketsVisibility()` -> `void`
- `resumeUnderlyingActivity(Object finished)` -> `void`
- `runInputCommand(String line)` -> `void`
- `runOnMain(Runnable r)` -> `void`
- `runOnMainSync(final Runnable r, int timeoutMs)` -> `void`
- `sanitizeTrustManagers(javax.net.ssl.TrustManager[] tms)` -> `javax.net.ssl.TrustManager[]`
- `scheduleResume(Object token)` -> `boolean`
- `setSource(Class<?> cls, Object event, int source)` -> `void`
- `setWindowFocus(Object vri, boolean focused)` -> `void`
- `showWindow(Object act)` -> `void`
- `startInputPump()` -> `void`
- `startTlsBootstrap()` -> `void`
- `startViewTreeDumper()` -> `void`
- `supportedLayoutFlags()` -> `int`
- `swapInGuardedProvider()` -> `void`
- `tlsOptimizedDir()` -> `String`
- `topActivity()` -> `Object`
- `topActivityRecord(Object exclude)` -> `Object`
- `topInputTarget()` -> `Object`
- `topInputTargetQuiet()` -> `Object`
- `u16(byte[] b, int o)` -> `int`
- `u32(byte[] b, int o)` -> `int`
- `unmountFragment()` -> `boolean`
- `unwrap(Throwable t)` -> `String`
- `uptimeMillis()` -> `long`
- `velocityTrackerSelfTest()` -> `void`
- `viewChildCount(Object viewGroup)` -> `int`
- `viewDimension(Object view, String getter)` -> `int`
- `waitFinishing(Object act, int timeoutMs)` -> `boolean`
- `wantsAndroidId(Object[] args)` -> `boolean`
- `windowType(Object vri)` -> `int`
- `wrapSettingsProvider(ContentProviderHolder holder)` -> `ContentProviderHolder`
- `writeBool(Object target, String name, boolean value)` -> `void`

total: 107

## 5. 输入泵命令面

板端写 `/data/local/tmp/wl_input.cmd` 一行一条，进程内守护线程按 (mtime,size)
去重读取（文件是 root 所有，应用删不掉，所以不能靠删文件去重）。

| 命令 | 作用 |
|---|---|
| `tap x y` / `tapv x y` | 合成 MotionEvent 投递 / 直投 ViewRootImpl |
| `swipe x1 y1 x2 y2 [ms]` | 分步 MOVE 序列 |
| `key <code>` | 先补投窗口焦点再投键；`key 4` 带完整返回语义 |
| `click x y` | 全子树命中收集 + `performClick()`（头条 item root 报 `isClickable()==false`） |
| `mount <fqcn> [k=v ...]` | **新增**：把 Fragment 事务挂进 `android.R.id.content` |
| `unmount` | **新增**：弹出一层挂载页 |
| `frags` | **新增**：宿主 FragmentManager / 容器现状 |
| `resume` | 给当前顶层窗口对应的 Activity 补一条 `ResumeActivityItem` |
| `winfocus` / `acts` / `dump` / `stack` | 焦点、Activity 记录、窗口树、主线程栈 |

## 6. 平台侧待补（西湖）

这四项都是**适配层缺口**，不是应用问题，应用侧只是用 dex 补丁绕开：

| 缺口 | 触发点 | 后果 |
|---|---|---|
| `IWindow.windowFocusChanged` 从不投递 | `WindowSessionAdapter` | 任何按键被 `shouldDropInputEvent()` 丢弃 |
| `TrafficStats.getUidRxBytes/getUidTxBytes(int)` | `onActivityStopped` | 任何 Activity 销毁必死 |
| `AudioSystem.native_getMaxChannelCount()` | `AudioManager.isWiredHeadsetOn` | 逛过视频频道后每次 resume 必死 |
| `AssetManager.nativeOpenAssetFd` | `Typeface.createFromAsset` | 任何 assets 自定义字体必死 |

另有两项前线已在适配层自行补上，官方产线可直接采用或换成平台实现：
`ResumeActivityItem` 补发（销毁后没人恢复下层 Activity）、
`dispatchAppVisibility(true)` 补投（Android 侧可见但 OH scene 仍隐藏）。

## 7. 板端共享现状（合流前必读）

`/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar` 与
`/data/local/tmp/wl-launch-activity` 是多条工作流共用的同一份文件，
板子重启也会把 jar 恢复成其中一支。前线的做法是备份—借用—原样还回
（板端 `oh-adapter-runtime.jar.s2-*` / `.s3-*`，仓库 `prebuilts/oh-adapter-runtime.s2-*.jar`）。
**统一构建库若要接管这个路径，需要先约定归属，否则两边会互相覆盖。**

## 8. 源码基线

| 项 | 值 |
|---|---|
| commit | `fa66f09bbdf0caae17d1a5b8b9047b0cbd47440b` |
| 分支 | `main` |
| 仓库 | `git@github.com:BH3GEI/openharmony-toutiao-repro.git` |

本文件第 1 节的四个 sha256 就是这个 commit 的树上算出来的（jar 与 apk 不入库，
按第 2 节复现后核对）。之后前线若再动交互层，会另起一份清单，不改这一份。
