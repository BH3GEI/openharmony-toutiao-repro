# 触控事件投递链路分析

**结论先行：适配层的输入链路只造了「消费端」，「生产端」整段不存在。**
不是焦点没注册，不是窗口 ID 对不上，也不是坐标区域没上报——是**适配层从头到尾
没有向鸿蒙多模态输入（MMI）订阅过任何事件**，因此 MMI 无论投递给谁，App 进程里
都没有接收方。

全部结论由离线静态分析得出，证据是板上取回的二进制本身（带 `debug_info`，未 strip）。

分析对象：

| 文件 | 大小 | 来源 |
|---|---|---|
| `/system/android/lib64/liboh_adapter_bridge.so` | 5 368 592 B | 板端取回 |
| `/system/android/lib64/liboh_android_runtime.so` | 744 856 B | 板端取回 |
| `/system/android/framework/oh-adapter-framework.jar` | 125 882 B | 反编译（jadx）|
| `/system/android/framework/framework.jar` | 40 087 842 B | dex 索引解析 |

---

## 1. 设计意图：一条完整的 AOSP 输入通路

从代码里能还原出适配层原本的设计：

```
OH MMI ──▶ OHInputBridge::monitorOHInputEvents()   [生产端]
              │  解析 OH 事件
              ▼
           OHInputBridge::writeMotionEvent()
              │  按 AOSP InputMessage 格式写进 socketpair
              ▼
        ┌── socketpair ──┐
        │                │
   服务端 fd         客户端 fd（copyTo 给 ViewRootImpl）
                          │
                          ▼
     liboh_android_runtime: workerLoop() @ 0x3f334   [消费端]
       线程名 OH_InputMotionWorker / OH_InputKeyWorker
       poll() + recv() → 构造 Java MotionEvent
                          │
                          ▼
     InputEventBridge.dispatchOnMainThread(receiver, seq, event)
                          │  反射
                          ▼
     InputEventReceiver.dispatchInputEvent(int, InputEvent)
                          │
                          ▼
                    ViewRootImpl 正常派发
```

**消费端（下半段）是完整的。** `workerLoop` 确实 `poll`/`recv`，确实构造 MotionEvent，
确实调用 `dispatchOnMainThread`，日志串都在：

```
dispatchInputEvent seq=%u action=%d x=%.1f y=%.1f downMs=%lld evtMs=%lld src=TOUCHSCREEN (posting to main looper)
dispatchInputEvent(KEY) seq=%u action=%d code=%d downMs=%lld evtMs=%lld (posting to main looper)
Route A direct worker->dispatchInputEvent will be used
```

**生产端（上半段）整段是空的。** 下面逐条给证据。

---

## 2. 四处断点，每一处都足以单独让触控失效

### 断点 1：`nativeRegisterInputChannel` 是 no-op —— `InputChannel` 没有 `getFd()`

`WindowSessionAdapter.addToDisplay()` 建好 socketpair 后调
`InputEventBridge.nativeRegisterInputChannel(session, channel)`。该 JNI 在
`liboh_adapter_bridge.so` 的 `0x1615b8`，反汇编还原：

```c
void Java_adapter_window_InputEventBridge_nativeRegisterInputChannel(
        JNIEnv* env, jclass, jint session, jobject channel) {
    jclass cls = env->GetObjectClass(channel);
    jmethodID mid = env->GetMethodID(cls, "getFd", "()I");   // ← 0x110c2d / 0x116678
    if (!mid) {
        env->ExceptionClear();
        HiLog(WARN, "nativeRegisterInputChannel: session=%d (no-op, getFd not available)");
        return;                                              // ← 就到这里为止
    }
    int fd = env->CallIntMethod(channel, mid);
    HiLog(INFO, "nativeRegisterInputChannel: session=%d, fd=%d", session, fd);
    int dupfd = dup(fd);
    if (dupfd < 0) { HiLog(ERROR, "Failed to dup InputChannel fd: %s", strerror(errno)); return; }
    OHInputBridge::getInstance()->registerInputChannel(session, dupfd);
}
```

而这块板子 `framework.jar` 里 `android.view.InputChannel` 的**全部**方法是：

```
<clinit> <init> copyTo describeContents dispose dup getName getToken
nativeDispose nativeDup nativeGetFinalizer nativeGetName nativeGetToken
nativeOpenInputChannelPair nativeReadFromParcel nativeWriteToParcel
openInputChannelPair readFromParcel release setNativeInputChannel
toString writeToParcel
```

**没有 `getFd`。** 所以 `GetMethodID` 返回 null，走 no-op 分支，
`OHInputBridge` 的 session 表**从来没有被填过一条记录**。

> 这解释了此前那个矛盾现象：日志里
> `[OH_WSA] step3a createInputChannelPair OK` / `step3b InputChannel.copyTo OK`
> 都正常打印——因为 `nativeRegisterInputChannel` **不抛异常**，它安静地什么都没做。
> 「通道建好了」这个结论只对了一半：AOSP 侧的 socketpair 确实建好了，
> 适配层侧的登记完全没发生。

### 断点 2：`registerOHInputFd()` 零调用点 —— 监听线程要 poll 的 fd 永远没人设

`monitorOHInputEvents()` poll 的是 `SessionInput` 里的第二个 fd 字段，
只有 `OHInputBridge::registerOHInputFd(int, int)` 会写它。全库扫描
（`bl`/`b`/PLT/重定位四种引用方式全查）：

```
registerOHInputFd     : 0 call sites
registerInputChannel  : 1 call site   （断点 1 里那个走不到的分支）
writeMotionEvent      : 1 call site   （来自 injectTouchEvent）
injectTouchEvent      : 2 call sites  （JNI + OHInputManagerBridge::injectMotionEvent）
subscribeMmi          : 2 call sites
startTapControlChannel: 2 call sites
```

`registerInputChannel(session, fd)` 本身还显式把该字段清零：

```c
SessionInput& s = mSessions[session];
s.channelFd = fd;
s.ohFd      = 0;        // str wzr, [x8, #8]
```

### 断点 3：`subscribeMmi(int)` 是一条 `ret`

`OHWindowManagerClient::createSession()` 里调了它两次，但函数体（`0x255fe4`）是：

```asm
255fe4: sub  sp, sp, #0x10
255fe8: str  x0, [sp, #0x8]      ; this
255fec: str  w1, [sp, #0x4]      ; session
255ff0: add  sp, sp, #0x10
255ff4: ret
```

存参数、返回。**没有任何订阅动作。**
`startTapControlChannel()` / `startTextControlChannel()`（`0x2557c0` / `0x2557d0`，
相隔 16 字节）同样是空壳。

### 断点 4：适配层根本没有 MMI 事件订阅能力

`liboh_adapter_bridge.so` 确实 `DT_NEEDED libmmi-client.z.so`，但它从中导入的
**全部 11 个符号**是：

```
OHOS::MMI::InputManager::GetInstance()
OHOS::MMI::InputManager::GetDeviceIds(function<void(vector<int>&)>)
OHOS::MMI::InputManager::GetDevice(int, function<void(shared_ptr<InputDevice>)>)
OHOS::MMI::InputDevice::GetId/GetName/GetType/GetUniq/GetProduct/GetVendor/IsLocal/IsVirtual
```

**清一色是设备枚举 API。** 没有 `SetWindowInputEventConsumer`、没有 `AddMonitor`、
没有 `SubscribeKeyEvent`、没有任何一个能收到 PointerEvent 的接口。
`liboh_android_runtime.so` 则一个 MMI 符号都不导入。

即使 MMI 把事件正确路由到了适配层的 SceneSession，App 进程里也没有接收方。

### 附带：`monitorOHInputEvents()` 拿到数据也只是打日志

退一万步，就算前三处都修好，监听线程读到数据后的完整处理是：

```c
ssize_t n = read(fds[i].fd, buf, 4096);
if (n <= 0) continue;
__android_log_print(DEBUG, "OH_InputBridge",
        "OH input event received: session=%d, %zd bytes", sessions[i], n);
// ← 函数体到此为止，数据被丢弃
```

不解析、不转发、不调 `writeMotionEvent`。

---

## 3. 对总师两条排查方向的回答

**「是否缺少向 Rosen/SceneSession 注册 WindowToken、Focus 状态或坐标区域？」**

`createSession()` 里确实调了 `WindowSessionProperty::SetFocusable(bool)` 与
`SetFocusableOnShow(bool)`——这也是整个库里仅有的两个 Rosen 焦点相关导入。
焦点链路可能另有欠缺，但**它不是当前的约束条件**：断点 3、4 在焦点问题之前就已经
让事件无处可去。先修焦点不会产生任何可观测变化。

**「uinput 注入的目标 Window ID 是否落到了 SceneBoard 上？」**

静态分析无法排除这一点，但**它同样不是约束条件**。哪怕 MMI 完美命中头条的
SceneSession，App 进程里既没订阅、也没登记 fd、拿到数据也只打日志。
先查窗口 ID 归属属于在错误的层级上花时间。

这两条方向本身是合理的排查顺序，只是这套适配层的缺口比预期靠前得多。

---

## 4. 已交付的规避手段：`wl-input-pump`

消费端既然完整，就从消费端重新接入。`ActivityManagerRouting`（`oh-adapter-runtime.jar`，
我们已有的注入点）新增一个守护线程，读命令文件、合成 MotionEvent，
直接交给 `InputEventBridge.dispatchOnMainThread(receiver, seq, event)`——
也就是 `OH_InputMotionWorker` 本来要用的那个入口：

```bash
hdc shell "echo 'tap 400 250'                > /data/local/tmp/wl_input.cmd"
hdc shell "echo 'swipe 600 1400 600 500 400' > /data/local/tmp/wl_input.cmd"
hdc shell "echo 'key 4'                      > /data/local/tmp/wl_input.cmd"   # BACK
hdc shell "echo 'dump'                       > /data/local/tmp/wl_input.cmd"   # 控件树
```

实现要点：

- 目标窗口在 `WindowManagerGlobal.mRoots` 里**按秩选**，不是简单取最后一个：
  已布局的应用窗口（`type < 1000` 且 `getWidth()/getHeight() > 0`）优先，
  其次是有面积的子窗口，最后才是无面积的。**取最后一个是错的**——
  被我们中和过的 PopupWindow（`type=1000`、`w=0`、surface 已释放）就排在末尾，
  往它派发事件会被安静吞掉，日志一切正常而像素毫无变化。
- `MotionEvent.obtain(JJIFFI)` 之后**必须 `setSource(SOURCE_TOUCHSCREEN)`**：
  `obtain` 留下的是 `SOURCE_UNKNOWN`，缺了 `SOURCE_CLASS_POINTER` 会被
  `ViewPostImeInputStage` 路由到 `processGenericMotionEvent`，视图树根本收不到。
- 首选 `InputEventBridge.dispatchOnMainThread`（它自带 post 到主 looper）；
  取不到时回退到 `ViewRootImpl.enqueueInputEvent(InputEvent)` + 主线程 Handler。
- 命令文件按 `(mtime, length)` 去重，不能只靠删除：shell 是以 root 身份写这个文件的,
  app 通常既 unlink 不掉也 truncate 不了，早期版本因此把同一条命令重放了 753 次。
- 无命令文件时线程只是每 150 ms 空转，**对既有行为零影响**。

已验证存在于板端 `framework.jar` 的全部依赖 API：

```
android.view.InputEventReceiver.dispatchInputEvent(ILandroid/view/InputEvent;)V   ✓
android.view.MotionEvent.obtain(JJIFFI)Landroid/view/MotionEvent;                 ✓
android.view.InputEvent.setSource(I)V                                             ✓
android.view.ViewRootImpl.mInputEventReceiver : ViewRootImpl$WindowInputEventReceiver ✓
android.view.ViewRootImpl.enqueueInputEvent(Landroid/view/InputEvent;)V           ✓
android.os.SystemClock.uptimeMillis()J                                            ✓
```

产物：`amr/build/oh-adapter-runtime.all.jar`。

**这是测试夹具，不是 MMI 的替代品。** 它让事件能被投进视图树，
但真实硬件触控仍然没有生产端。

### 真机验证结果（2026-09-05）

已与 S2 的 TLS 网关 + ALooper 加载合流为 `oh-adapter-runtime.all.jar`（见第 7 节），
在板端实测：

| 项 | 结果 | 证据 |
|---|---|---|
| 泵启动 | ✅ | `[WL-INPUT] pump armed, watching /data/local/tmp/wl_input.cmd` |
| 命令送达 | ✅ | 一条命令一次执行（早期版本因无法 unlink root 文件而重放了 753 次，已修） |
| 投递到主窗口 | ✅ | `tap 320.0,213.0 -> ViewRootImpl(type=1 … ty=BASE_APPLICATION)` |
| 进入真实视图树 | ✅ | `tapv 1050.0,1855.0 on com.android.internal.policy.DecorView -> down=true up=true` |
| 界面切换 | ❌ | 见下 |

**第一版打到了错窗口。** 最初 `topInputTarget()` 取 `mRoots` 的最后一项，
拿到的是被我们中和过的 PopupWindow 子窗口（`type=1000`、`w=0`、surface 已释放），
事件被安静吞掉。改成按「已布局的应用窗口 > 已布局的子窗口 > 无面积窗口」评分后，
命中 `type=1 BASE_APPLICATION`。

### 新发现的平台缺口：`VelocityTracker` 没有 native 实现

事件确实进了视图树，但屏幕不变。`tapv` 直通路径把异常原样抛了出来：

```
java.lang.UnsatisfiedLinkError: No implementation found for
    long android.view.VelocityTracker.nativeInitialize(int)
  at android.view.VelocityTracker.nativeInitialize(Native Method)
  at android.view.VelocityTracker.obtain(VelocityTracker.java:230)
  at android.widget.HorizontalScrollView.initOrResetVelocityTracker(HorizontalScrollView.java:540)
  at android.widget.HorizontalScrollView.onInterceptTouchEvent(HorizontalScrollView.java:641)
  at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2654)
```

顶部频道行是一个 `HorizontalScrollView`。触摸一进入它，
`onInterceptTouchEvent` 就要 `VelocityTracker.obtain()`，
而这块板子的适配层**没有提供 `VelocityTracker` 的 JNI 实现**，于是整条
`dispatchTouchEvent` 直接展开退栈。**任何可滚动容器都会这样**——
频道行、`FeedCommonRecyclerView` 都用 `VelocityTracker`。

这也解释了走 ViewRootImpl 的 `tap` 为什么「无声无息」：同一个
`UnsatisfiedLinkError` 在 `ViewPostImeInputStage` 里被 ViewRootImpl 的
阶段机制吞掉了，日志里什么都看不到。**事件一直是送到了的，死在 VelocityTracker。**

底部导航（`tapv 1050 1855`）不在滚动容器里，所以不抛异常、`down=true up=true`，
但同样没有可见变化——那是另一个待查项，不能与本条混为一谈。

**已修复**，见下一节。

---

## 5. 要真正打通硬件触控，需要板端改动

按代价从低到高：

1. **给 `android.view.InputChannel` 补一个 `getFd()I`**（framework.jar，BCP）。
   这样断点 1 消失，`OHInputBridge` 的 session 表能填上，
   `nativeInjectTouchEvent` / `OHInputManagerBridge::injectMotionEvent` 这条
   已经写好的注入路径立刻可用（`writeMotionEvent` 会按 AOSP InputMessage 格式
   写 socket，`workerLoop` 那端已经在等）。**但断点 3、4 仍在**——只是让
   「程序化注入」可用，硬件触控依然不通。

2. **实现 `OHInputBridge::subscribeMmi()`**：用
   `InputManager::SetWindowInputEventConsumer` 或 `AddMonitor` 订阅 PointerEvent，
   在回调里换算坐标后调 `writeMotionEvent`。这是唯一能让硬件触控真正贯通的改动，
   属于适配层源码工作，不是二进制补丁能覆盖的范围。

3. 顺带：`SessionStageBridge.onHandleBackEvent()` / `onMarkProcessed()` /
   `onNotifyTouchOutside()` 与 `WindowCallbackBridge.onConsumeKeyEvent()` /
   `onNotifyWindowClientPointUp()` 目前也全是 `logPartial` 空壳，
   注释里写着「-> inject KEYCODE_BACK via input channel」但没有实现。

---

## 6. 复现本分析

```bash
hdc file recv /system/android/lib64/liboh_adapter_bridge.so  ./
hdc file recv /system/android/lib64/liboh_android_runtime.so ./

# 断点 3：subscribeMmi 是空壳
objdump -d --start-address=0x255fe4 --stop-address=0x255ff8 liboh_adapter_bridge.so | c++filt

# 断点 4：MMI 只导入设备枚举
nm -D --undefined-only liboh_adapter_bridge.so | c++filt | grep 'OHOS::MMI'

# 断点 1：JNI 找的是 getFd()I
strings -a liboh_adapter_bridge.so | grep nativeRegisterInputChannel

# 断点 1 的另一半：framework 的 InputChannel 没有 getFd
unzip -p /path/to/framework.jar classes4.dex > c4.dex   # 用 patches/tools 解析方法表

# 消费端是完整的
strings -a liboh_android_runtime.so | grep 'dispatchInputEvent seq='
```


---

## 7. 与 TLS / ALooper 的合流

板端的 `oh-adapter-runtime.jar` 不是原始版本：S2 在里面注入了 TLS 网关
（`TlsGateSpi` / `hijackTlsShim`）和 `libwlalooper.so` 的动态加载。
直接推自己的构建会把网络层打回原形，所以走三方合并：

```
amr/src/adapter/activity/
  ActivityManagerRouting.java       首帧修复 + input pump
  ActivityManagerRouting.tls.java   S2：首帧修复 + TLS 网关 + ALooper
  ActivityManagerRouting.all.java   合流：三者齐全  ← 板端部署这一份
```

合并是纯增量的（`git merge-file` 零冲突，S2 那份相对公共基线**没有任何删除**）。
构建：

```bash
JAVA_HOME=<jdk11+> \
SRC=amr/src/adapter/activity/ActivityManagerRouting.all.java \
OUT=amr/build/oh-adapter-runtime.all.jar amr/build_amr.sh
```

**合并结果是逐符号验证过的**，不是"看着像对"：

- 用同一套脚本从 `.tls.java` 重建 S2 的 jar，dex 的方法集与字符串集
  与板上那份**完全一致**（双向差集均为 0）；
- `.all.jar` 相对 S2 的 jar 是**严格超集**：缺失 0 个方法 / 0 个字符串，
  新增 26 个方法 / 73 个字符串（全部来自 input pump）。

真机也确认三套逻辑同时在跑：

```
[WL-ALOOPER] loaded …/libwlalooper.so into the app namespace
[WL-TLS] hijacked 'TlsShim' provider: 2 service(s) re-pointed at the gate
[WL-INPUT] pump armed, watching /data/local/tmp/wl_input.cmd
```

顺带修掉两个构建脚本问题：`build_amr.sh` 用了 `mapfile`（bash 4+，macOS 跑不了，
之前只做过 `bash -n` 所以没暴露），且只编译 routing 类一个文件——
`src/android/net/ssl/SSLSockets.java` 因此从未被打进 jar。现在编译整个 `src/`。

部署前板上那份已备份为
`/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar.s2-tls`。


---

## 8. 补上 `VelocityTracker`：`native/veltrack/`

`VelocityTracker` 只声明了 7 个 native 方法，全部无实现：

```
nativeInitialize(I)J        nativeDispose(J)V       nativeClear(J)V
nativeAddMovement(JLandroid/view/MotionEvent;)V     nativeComputeCurrentVelocity(JIF)V
nativeGetVelocity(JII)F     nativeIsAxisSupported(I)Z
```

`native/veltrack/wl_veltrack.c` 把它们补齐（OpenHarmony native SDK 交叉编译到 aarch64）。
**只做记账**：发放真实的 per-tracker 句柄让 `obtain`/`recycle`/`finalize` 配平，速度一律返 0。
点击、拖拽、跟手滚动都只依赖触摸坐标，不需要真实速度；只有**惯性滑动（fling）**会退化成"不滑"。
真算速度要在触摸派发过程中于 UI 线程上把 `MotionEvent` 经 JNI 读回来，
为一个当前用不上的能力扩大爆炸半径，不划算。

### 绑定方式：`RegisterNatives`，不是符号查找

第一版走的是"以 boot classloader `nativeLoad`"，**被适配层挡了**：

```
[WL-VELTRACK] nativeLoad(/system/android/lib64/libwlveltrack.so, boot) failed:
              system library is absent from the adapter manifest
```

这条消息来自 `libnativeloader.so`，它带着一份**硬编码的三条白名单**
（`libopenjdk.so` / `libicu_jni.so` / `libjavacore.so`，见
`westlake::nativeloader::policy::kBridgePermittedPaths`），不是可配置文件。

所以改成**根本不依赖符号查找**：把库放进 app 的 native lib 目录、用 **app classloader**
加载（这条路是通的，`libwlalooper.so` 就这么进去的），再由库的 `JNI_OnLoad`
对 `android.view.VelocityTracker` 调 `RegisterNatives`。
**显式注册优先于查找，且不关心库是从哪个 loader 进来的**——BCP 类的限制就此绕开。

```
[WL-VELTRACK] loaded …/lib/arm64-v8a/libwlveltrack.so into the app namespace after 3828ms
[WL-VELTRACK] self-test OK: obtain/compute/recycle, xVelocity=0.0 yVelocity=0.0
```

自检刻意走 `HorizontalScrollView` 的那条调用链（`obtain` → `computeCurrentVelocity`
→ `getXVelocity` → `recycle`），启动时跑一次，失败会直接打在日志里。

### 结果：频道切换成功

| 动作 | 结果 |
|---|---|
| `tapv 320 213` | 推荐 → **热榜**（红线移位，页面换成热榜空态"网络异常，请稍后重试"）|
| `tapv 560 213` | 热榜 → **视频**（页面换成"当前网络不可用，点击重试"，**频道行自身滚动了**）|
| `tap 840 213` | → **娱乐**（走 `dispatchOnMainThread` → ViewRootImpl 的正式路径，同样成功）|

两条路径都通：`tapv` 直通 DecorView，`tap` 走
`InputEventBridge.dispatchOnMainThread` → `InputEventReceiver.dispatchInputEvent`
→ ViewRootImpl 阶段链，也就是 `OH_InputMotionWorker` 本来要用的那条。
整轮 `UnsatisfiedLinkError` 计数为 0。

频道行会把选中项滚动到可见位置（娱乐那张图里"关注/推荐/热榜"已滚出左边、
"汽车/财经/军事"露了出来），这本身就是 `HorizontalScrollView` 恢复工作的直接证据。

信息流仍然是空的——那是 TTNet 自取消的问题（S2 在查），与输入无关。


---

## 9. 子窗口中和的精细化，以及「未登录 / 搜索框」的真相

### 9.1 中和范围收窄：只中和**零面积**子窗口

首帧那颗 `EGL_NO_SURFACE` / `Surface was not locked` 的雷，是**一个特定的**
PopupWindow 埋的，它的 LayoutParams 是 `w=0 h=-1`——**完全没有面积**。
但当时的修复是把 `type >= 1000` 的子窗口**全部**静默掉，这是大锤版本。

现在按尺寸判定：`MATCH_PARENT(-1)` 和 `WRAP_CONTENT(-2)` 都是**真实尺寸**，
只有**恰好为 0** 才算退化。只有退化窗口进 `sDegenerateSubWindows`，也只有它被中和。
`LayoutParams` 只在变化时才下发（其余传 null），所以和 `sSubWindows` 一样按窗口记忆。

留了一个回退开关：`touch /data/local/tmp/wl_neutralize_all` 恢复全域中和。

**真机验证无回归**：冷启动照常在 t=80s 出首帧，启动期日志显示只有一个子窗口，
正是那个退化的：

```
[WL-WIN] addToDisplayAsUser attrs=w=-1 h=-1 type=1    … ty=BASE_APPLICATION
[WL-WIN] addToDisplayAsUser attrs=w=0  h=-1 type=1000 … ty=APPLICATION_PANEL
[WL-WIN] sub-window w=0 h=-1 is degenerate -> will be neutralised
```

### 9.2 但它不是「未登录 / 搜索框」的拦路虎

新增的 `[WL-WIN]` 一行日志覆盖 `addToDisplay*` / `remove`——
任何 Dialog、PopupWindow 或新 Activity 要上屏都**必须**经过这里。结果：

| 点击 | 新窗口 | 网络 | 界面 |
|---|---|---|---|
| 未登录（1050,1870）| **0 条** `addToDisplay` | **真实 HTTPS/H2 到 `api.toutiaoapi.com:443`** | 不切换 |
| 搜索框（517,113）| **0 条**，等到 **150 秒**仍为 0 | 无 | 不切换 |

所以**没有任何窗口被创建**，中和根本没有东西可吞——放宽中和不会让它们出现。
两者的原因还不一样：

- **未登录**：点击**确实被处理了**。日志里出现
  `[WL-TLS] tap createSocket api.toutiaoapi.com` 以及 h2 帧的收发，
  说明处理函数跑起来并发了真实请求。Tab 不切换，更像是**切换本身依赖这个请求的结果**——
  和信息流为空是同一个内容层问题，不是输入问题。
- **搜索框**：坐标落在 `CropRelativeLayout abs=[36,68][998,158] CLICKABLE` 正中，
  事件送达主窗口，但此后**什么都没有**：没有窗口、没有网络、没有异常。
  等 150 秒也没有（排除了"新 Activity 解释器冷渲染太慢"这个解释）。
  处理函数看来根本没跑到 `startActivity`。**这一条尚未定位，不下结论。**
