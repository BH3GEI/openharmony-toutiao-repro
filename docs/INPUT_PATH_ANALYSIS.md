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

- 目标窗口取 `WindowManagerGlobal.mRoots` 中**最后一个** `mView != null` 且
  `mInputEventReceiver != null` 的 `ViewRootImpl`（mRoots 按添加顺序，末尾在最上层）。
- `MotionEvent.obtain(JJIFFI)` 之后**必须 `setSource(SOURCE_TOUCHSCREEN)`**：
  `obtain` 留下的是 `SOURCE_UNKNOWN`，缺了 `SOURCE_CLASS_POINTER` 会被
  `ViewPostImeInputStage` 路由到 `processGenericMotionEvent`，视图树根本收不到。
- 首选 `InputEventBridge.dispatchOnMainThread`（它自带 post 到主 looper）；
  取不到时回退到 `ViewRootImpl.enqueueInputEvent(InputEvent)` + 主线程 Handler。
- 命令文件读完即删，避免某条命令卡住主线程后被反复重放。
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

产物：`amr/build/oh-adapter-runtime.input.jar`（`amr/build_amr.sh` 一条命令重建）。

**这是测试夹具，不是 MMI 的替代品。** 它让 UI 能被脚本驱动、界面矩阵能采全，
但真实硬件触控仍然没有生产端。

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
