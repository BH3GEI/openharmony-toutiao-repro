# OpenHarmony-A2OH 兼容子系统架构白皮书与演进决策树
# Architecture Blueprint and Evolution Decision Tree for A2OH Compatibility Subsystem

Document Version: 1.0.0-PROPOSAL
Date: 2026-09-05
Author: Chief System Architect (A2OH Subsystem)
Target Audience: System Architects, Core Runtime Engineers, Session 1/2/3 Task Forces

---

## 目录 (Table of Contents)

- [一、 顶层架构愿景与第一性原理公理](#一-顶层架构愿景与第一性原理公理)
  - [1.1 系统使命与终态定义](#11-系统使命与终态定义)
  - [1.2 第一性原理三大公理](#12-第一性原理三大公理)
  - [1.3 双体系架构阻抗失配总览](#13-双体系架构阻抗失配总览)
- [二、 课题一：窗口与渲染总线架构（从临时中和到 SceneSession 拓扑归一）](#二-课题一窗口与渲染总线架构从临时中和到-scenesession-拓扑归一)
  - [2.1 Android 渲染管道与 OpenHarmony Rosen 树的失配本质](#21-android-渲染管道与-openharmony-rosen-树的失配本质)
  - [2.2 战场排雷复盘：EGL_NO_SURFACE 与 Surface 未锁定崩溃](#22-战场排雷复盘egl_no_surface-与-surface-未锁定崩溃)
  - [2.3 现有战地方案评估：sSubWindows 静态中和的技术债](#23-现有战地方案评估ssubwindows-静态中和的技术债)
  - [2.4 目标架构设计：多窗口统一 SceneSession 拓扑模型](#24-目标架构设计多窗口统一-scenesession-拓扑模型)
  - [2.5 状态机与 Relayout 协议规范](#25-状态机与-relayout-协议规范)
- [三、 课题二：输入与事件分发中枢（从文件注入泵到原生 MMI 穿透管道）](#三-课题二输入与事件分发中枢从文件注入泵到原生-mmi-穿透管道)
  - [3.1 输入子系统断裂根因深度解剖](#31-输入子系统断裂根因深度解剖)
  - [3.2 战场排雷复盘：libwlveltrack 与 wl-input-pump](#32-战场排雷复盘libwlveltrack-与-wl-input-pump)
  - [3.3 目标架构设计：原生 MMI 穿透式管道拓扑](#33-目标架构设计原生-mmi-穿透式管道拓扑)
  - [3.4 线程模型与非阻塞派发保证](#34-线程模型与非阻塞派发保证)
- [四、 课题三：安全与网络协议栈底座（从 2MB BCTLS 到系统级通信底座）](#四-课题三安全与网络协议栈底座从-2mb-bctls-到系统级通信底座)
  - [4.1 网络协议栈断裂根因与 TTNet 行为建模](#41-网络协议栈断裂根因与-ttnet-行为建模)
  - [4.2 战场排雷复盘：SQLite JNI 攻坚与 BCTLS 纯 Java 握手](#42-战场排雷复盘sqlite-jni-攻坚与-bctls-纯-java-握手)
  - [4.3 双阶段架构演进路径](#43-双阶段架构演进路径)
    - [4.3.1 近期路线（Phase 1）：BouncyCastle R8 深度裁剪与冷启动加速](#431-近期路线phase-1bouncycastle-r8-深度裁剪与冷启动加速)
    - [4.3.2 终态路线（Phase 2）：OpenHarmony 原生 BoringSSL 平台级 Provider](#432-终态路线phase-2openharmony-原生-boringssl-平台级-provider)
- [五、 课题四：工业化基线与官方代码合流工程](#五-课题四工业化基线与官方代码合流工程)
  - [5.1 a2hlab/manifest 体系剖析与闭包构建标准](#51-a2hlabmanifest-体系剖析与闭包构建标准)
  - [5.2 战场补丁向官方源码库 (a2hlab/westlake) 的结构化映射](#52-战场补丁向官方源码库-a2hlabwestlake-的结构化映射)
  - [5.3 质量门禁与无依赖构建契约](#53-质量门禁与无依赖构建契约)
- [六、 系统演进决策树与行动路线图 (Decision Tree & Roadmap)](#六-系统演进决策树与行动路线图-decision-tree--roadmap)
  - [6.1 关键架构分叉决策树](#61-关键架构分叉决策树)
  - [6.2 三军协同作战演进路线图](#62-三军协同作战演进路线图)

---

## 一、 顶层架构愿景与第一性原理公理

### 1.1 系统使命与终态定义

OpenHarmony-A2OH（Android to OpenHarmony）兼容子系统的核心使命是：**在 OpenHarmony 微内核与系统服务之上，提供一套生产级、高保真、透明无感的 Android 应用运行时环境**。

它的终态形态绝非针对单一商业应用（如今日头条）的代码硬编码打桩集合，而是一个标准、分层、自洽的系统级兼容层（Compatibility Layer）。该运行时在不侵入 Android 核心语义、不篡改应用签名逻辑的前提下，实现应用无缝安装、高效图形渲染、零丢帧硬件交互与安全通信。

### 1.2 第一性原理三大公理

在整个架构设计中，严格贯彻三条不可违背的第一性原理公理：

1. **公理一：代码是给人看的，只是机器恰好可以运行。**
   - 坚决杜绝任何充满不可解释魔数、硬编码内存偏移、未文档化跳板指令（Code Cave）或私有硬编码路径（如 `/mnt/c/Users/...` 或特定机器绝对路径）的补丁。
   - 任何接口适配必须拥有清晰的类定义、类型安全的调用边界以及完整的契约注释。

2. **公理二：契约守恒与语义保真。**
   - 适配层必须忠实实现 AOSP 与 OpenHarmony 双向系统的公开 IPC 契约。
   - 当遇到 AOSP 未实现类或系统服务（如 `IWindowSession`、`SSLSockets`）时，应当在框架层提供符合规范的 Stub/Adapter，而非通过在应用 Dalvik 字节码中插入 `nop` 或 `return-void` 来掩盖矛盾。

3. **公理三：数据流与生命周期强一致。**
   - 任何跨系统对象（如 Android 的 `SurfaceControl` 与 OpenHarmony 的 `RSSurfaceNode`、Android 的 `InputChannel` 与 OpenHarmony 的 `MMI::InputChannel`）必须遵循单向所有权与严格配对的创建/销毁生命周期，杜绝跨进程死锁与内存野指针。

### 1.3 双体系架构阻抗失配总览

A2OH 运行时面临底层核心技术栈的根本性差异：

```
+-----------------------------------------------------------------------------------+
|                            Android Application (APK)                              |
+-----------------------------------------------------------------------------------+
                                      |
                                      v
+-----------------------------------------------------------------------------------+
| AOSP Framework API (android.*, java.*, javax.*) - AOSP 15 (API 35)                |
+-----------------------------------------------------------------------------------+
                                      |
       ================== A2OH COMPATIBILITY SUBSYSTEM ==================
                                      |
         [Window / Rosen]          [Input / MMI]            [Security / Net]
         ViewRootImpl              InputChannel             javax.net.ssl
              |                         |                         |
              v                         v                         v
         WindowSessionAdapter      InputEventBridge         TlsBootstrap / JNI
              |                         |                         |
              v                         v                         v
         liboh_adapter_bridge.so   liboh_android_runtime    libboringssl.z.so
                                      |
       =================== OPENHARMONY OS (API 23 / 6.1) ==================
                                      |
         [RenderService]           [MultimodalInput]        [System Services]
         SceneSessionManager       MMIService / InputDevice NetManager / Security
         (RS UniRender Tree)       (IPC Client)             (Musl Libc Environment)
```

- **C 运行时环境失配**：Bionic C（Android 专有，具备 `pthread_gettid_np`、特殊 TLS 结构、jemalloc） vs Musl C（OpenHarmony 标准 C 库，默认 128KB 线程栈，不同的 dynamic linker 命名空间隔离规则）。
- **图形管线失配**：SurfaceFlinger 客户端 BufferQueue 拓扑 vs Rosen RenderService 单一渲染树（UniRender）节点拓扑。
- **IPC 模型失配**：Linux Binder 内核驱动协议 vs OpenHarmony IPC/SAMgr 通信协议。

---

## 二、 课题一：窗口与渲染总线架构（从临时中和到 SceneSession 拓扑归一）

### 2.1 Android 渲染管道与 OpenHarmony Rosen 树的失配本质

#### Android HWUI 渲染管道
在标准 Android 系统中：
1. `Activity` 创建 `PhoneWindow`，通过 `WindowManagerGlobal.addView` 实例化 `ViewRootImpl`。
2. `ViewRootImpl.setView` 调用 `IWindowSession.addToDisplay` 向 `WindowManagerService` 注册自身。
3. `ViewRootImpl.performTraversals` 执行 `relayoutWindow`，向 WMS 申请合法的 `SurfaceControl`。
4. HWUI 的 `HardwareRenderer` 将该 `SurfaceControl` 底层的 `ANativeWindow` 绑定为 `EGLSurface`。
5. `RenderThread` 在每次 vsync 驱动下，执行 OpenGL ES / Vulkan 绘制命令，调用 `eglSwapBuffers` 将图形缓冲区送入客户端 BufferQueue。

#### OpenHarmony 6.1 Rosen UniRender 拓扑
在 OpenHarmony 6.1（API 23）中：
1. 所有 UI 元素都映射为 `OHOS::Rosen::RSNode` 树。
2. 窗口管理器为 `SceneSessionManager`（SSM），负责维护服务端 `SceneSession`。
3. 渲染服务 `render_service` 运行在 UniRender 模式下：
   - 普通窗口节点（`APP_WINDOW_NODE`）默认不获取客户端的 BufferQueue，而是由 RS 自身根据绘制命令绘制；
   - 只有自绘制节点（`SELF_DRAWING_NODE`，如视频、XComponent 挂载层）才真正挂接外部图形缓冲队列（BufferQueue Consumer）。
4. 如果一个 `SurfaceControl` 绑定的 NativeWindow 没有挂接到一个激活的、类型为 `SELF_DRAWING_NODE` 且 `Visible=true` 的 Rosen 节点上，`render_service` 就永远不会消耗其缓冲，或者客户端直接报告 `EGL_NO_SURFACE`。

### 2.2 战场排雷复盘：EGL_NO_SURFACE 与 Surface 未锁定崩溃

在前线验证今日头条主界面时，遇到了两波致命的崩溃接力：

```
[Crash 1: Native HWUI 崩溃]
ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE msg=drawRenderNode called on a context with no surface!

[Crash 2: Java 软件渲染兜底崩溃]
java.lang.IllegalStateException: Surface was not locked
    at android.view.Surface.unlockSwCanvasAndPost(Surface.java:362)
    at android.view.ViewRootImpl.draw(ViewRootImpl.java:4612)
```

**深入推演其物理根因**：
- 今日头条在主界面加载时，除了 `MainActivity` 顶级窗口（`type=1`）外，会密集弹出各类浮层、气泡引导或预加载的 `PopupWindow`（`type=1000`，`TYPE_APPLICATION_PANEL`）。
- 这些子窗口在向系统发起 `relayout` 时：
  1. 子窗口没有独立的 `ActivityToken`（`attrs.token` 指向的是父窗口的 `W` Binder，而非 AMS 分配的 AbilityRecord Token）。
  2. 底层 `oh_window_manager_client.cpp` 无法从 `OhTokenRegistry` 解析出合法 OH Token，退化使用缺省 session 或试图创建 `WINDOW_TYPE_APP_SUB_WINDOW`。
  3. 由于没有为子窗口建立独立的 Rosen 缓冲通道，HWUI 试图为子窗口的 `ViewRootImpl` 创建 `EGLSurface` 失败，触发 Skia 断言 `mEglSurface == EGL_NO_SURFACE`。
  4. 当通过屏蔽硬件加速退化到软件渲染时，由于第二次 `relayout` 时 `attrs` 参数为空，掩码拦截失效，`relayout` 返回了非 0 状态码，促使 `ViewRootImpl` 认为 Surface 有效并调用 `unlockSwCanvasAndPost`，但底层实际从未执行过 `lockCanvas`，直接抛出 `IllegalStateException`。

### 2.3 现有战地方案评估：sSubWindows 静态中和的技术债

为了让首帧通过，S1 实现了战地“三重截断”中和方案：
```java
// S1 战地中和实现（ActivityManagerRouting.java）
if (isSubWindow(window, attrs)) {
    neutralizeSubWindow(window);
    invalidateSubWindowSurface(outSurfaceControl);
    return 0; // 强行让 relayout 返回 0
}
```

**架构评估**：
- **收益**：立竿见影。将所有 `type >= 1000` 的子窗口就地静默，切断了子窗口向 HWUI 索要 EGLSurface 的路径，彻底消除了崩溃，保住了主界面首帧渲染。
- **技术债（代价）**：
  1. **破坏功能完整性**：应用中所有的 Dialog、PopupWindow、下拉菜单、长按上下文浮层、Toast 均无法显示。
  2. **违反设计原则**：在 `ActivityManagerRouting` 这一本该属于 AMS 代理的层级，侵入了 WMS 的 `relayout` 逻辑与内部属性反射，职责倒错。
  3. **隐藏生命周期问题**：被中和的子窗口其 `ViewRootImpl` 处于一种半死不活的僵尸状态（`mStopped=true` 但依然驻留内存），可能引发内存泄漏或业务层回调超时。

### 2.4 目标架构设计：多窗口统一 SceneSession 拓扑模型

为了从根本上支持 Dialog 和 PopupWindow，必须在 `WindowSessionAdapter` 与 `oh_window_manager_client.cpp` 中建立严格的**父子窗口会话归一模型**。

#### 目标渲染节点树拓扑图

```
                       [SceneSessionManager (SSM)]
                                   |
            +----------------------+----------------------+
            |                                             |
   [Main SceneSession]                           [Sub SceneSession]
   (Ability Main Window)                         (PopupWindow / Dialog)
   - PersistentId: 101                           - PersistentId: 102
   - WindowType: APP_MAIN_WINDOW                 - WindowType: APP_SUB_WINDOW
   - ParentId: 0                                 - ParentId: 101
            |                                             |
            v                                             v
   [RS APP_WINDOW_NODE]                          [RS APP_SUB_WINDOW_NODE]
   (SceneBoard Anchor)                           (Mounted under Main Node)
            |                                             |
            v                                             v
   [RS SELF_DRAWING_NODE]                        [RS SELF_DRAWING_NODE]
   (Content Buffer Consumer)                     (Sub Content Buffer Consumer)
            |                                             |
            v                                             v
   ANativeWindow (Producer 101)                  ANativeWindow (Producer 102)
            ^                                             ^
            | (EGLSurface)                                | (EGLSurface)
   MainActivity HWUI RenderThread                PopupWindow HWUI RenderThread
```

#### 关键技术实现规范

1. **父窗口 Token 链式解析**：
   - 在 `WindowSessionAdapter.addToDisplay` 中，当检测到 `attrs.type >= FIRST_SUB_WINDOW (1000)` 时，`attrs.token` 携带的是父窗口的 `IWindow.asBinder()`。
   - `WindowSessionAdapter` 内部维护 `mWindowSessionMap`。通过父窗口 Binder 反查到父窗口的 `parentSessionId` 与 OpenHarmony `parentWindowId`。

2. **JNI 层显式声明父子依附关系**：
   - 改造 `nativeCreateSession` 接口，显式传入 `parentSessionId`：
   ```cpp
   int[] nativeCreateSession(
       Object androidWindow,
       String bundleName, String abilityName, String moduleName,
       String windowName,
       int androidWindowType, int displayId,
       int requestedWidth, int requestedHeight,
       long ohTokenAddr,
       int parentSessionId /* 新增：父会话标识 */
   );
   ```
   - 在 `oh_window_manager_client.cpp` 中：
     如果 `parentSessionId > 0`，设置：
     ```cpp
     property->SetWindowType(OHOS::Rosen::WindowType::WINDOW_TYPE_APP_SUB_WINDOW);
     property->SetParentId(parentWindowId);
     ```

3. **双层 Rosen 节点挂接模式**：
   - 无论是主窗口还是子窗口，统一采用 **Anchor + Self-Drawing Child** 结构：
     - 主节点作为 SSM 认可的 Session 载体；
     - 子节点类型设为 `SELF_DRAWING_NODE`，负责承接 HWUI 送上来的 GraphicBuffer。
   - 子窗口的 `APP_SUB_WINDOW_NODE` 必须挂接到父窗口的 `APP_WINDOW_NODE` 之下，保证 Z-Order 层级与相对坐标偏移受 SceneBoard 正确管理。

4. **SurfaceControl 单例缓存保真**：
   - 严格在 `WindowSessionAdapter` 中维护每个 `IWindow` Binder 到 `SurfaceControl` 的 1:1 强缓存，严禁在每次 `relayout` 时重建 `SurfaceControl`，避免触发 HWUI 销毁重建 `EGLSurface` 的致命震荡。

### 2.5 状态机与 Relayout 协议规范

```
[ViewRootImpl]                  [WindowSessionAdapter]               [Rosen SSM / Client]
      |                                   |                                   |
      |--- 1. addToDisplay(window) ------>|                                   |
      |                                   |--- 2. nativeCreateSession ------->| (分配独立 Session)
      |                                   |<-- 3. return sessionId/NodeId ----|
      |<-- 4. addResult(OK) --------------|                                   |
      |                                   |                                   |
      |--- 5. relayout(window, attrs) --->|                                   |
      |                                   |--- 6. nativeUpdateSessionRect --->| (更新窗口大小与属性)
      |                                   |--- 7. nativeAttachSessionToSc --->| (绑定 GraphicBuffer)
      |<-- 8. outSurfaceControl ----------|                                   |
      |    (RELAYOUT_RES_FIRST_TIME)      |                                   |
      |                                   |                                   |
      |--- 9. HWUI initialize(Surface) -->|                                   |
      |    (绑定 EGLSurface 成功)         |                                   |
      |--- 10. eglSwapBuffers ----------->|==================================>| (送显至 RS BufferQueue)
```

---

## 三、 课题二：输入与事件分发中枢（从文件注入泵到原生 MMI 穿透管道）

### 3.1 输入子系统断裂根因深度解剖

今日头条主界面虽然渲染成功，但在早期阶段屏幕点击毫无反应。S1 通过反编译与汇编分析，精准揭示了 OpenHarmony 适配层在输入事件管道上的**断裂现场**：

```
[硬件触摸屏] ---> [Linux evdev] ---> [OpenHarmony MultimodalInput (MMI)]
                                                    |
                                                    X  <-- 断点 1：subscribeMmi 为空桩 (ret 指令)
                                                    v
                                    [OHInputBridge::monitorOHInputEvents]
                                                    |
                                                    X  <-- 断点 2：只打 log，直接丢弃，不写 fd
                                                    v
                                         [Native Input Pipe (fd)]
                                                    |
                                                    X  <-- 断点 3：nativeRegisterInputChannel 缺 getFd()
                                                    v
                                         [Android InputChannel]
                                                    |
                                                    v
                                          [ViewRootImpl 派发]
```

**断裂物理成因**：
1. **ABI 对齐故障（历史阴影）**：
   在 `oh_window_manager_client.cpp` 的历史提交（§342b）中，明确记录了为什么关闭原生 MMI：
   调用 `OHInputBridge::subscribeMmi(int)` 会触发 `SIGBUS (BUS_ADRALN)` 崩溃，位于 `libeventhandler.z.so` 的 `EventHandler` 构造函数内部。这是由于外部交叉编译器头文件与 OpenHarmony 6.1 系统库的 C++ 内存对齐规则失配所致。
2. **投机性降级**：
   为了避开 `SIGBUS`，原作者直接把 `subscribeMmi` 改写成了空桩，并在底层通过轮询本地文件 `/data/local/tmp/noice_tap` 模拟输入。
3. **框架接口缺失**：
   Android 端的 `android.view.InputChannel` 在适配层精简版中缺少 `getFd()I` 方法，导致 `nativeRegisterInputChannel` 探测失败后直接退出。

### 3.2 战场排雷复盘：libwlveltrack 与 wl-input-pump

在面对上述断裂时，S1 展现出了极其强大的战场生存与推进能力：

1. **输入泵穿透（`wl-input-pump`）**：
   - 既然生产端（MMI）断了，而消费端（`InputEventBridge.dispatchOnMainThread`）是完备的，S1 直接在 `ActivityManagerRouting` 中拉起守护线程，解析外部命令文件并直接构造 `MotionEvent`。
   - 解决了 Android 10+ 对于 `SOURCE_TOUCHSCREEN` 的标志位校验。
   - 引入了 `topInputTarget` 窗口排序算法，精准绕过 0x0 尺寸的隐形子窗口，将触摸事件准确派发给最上层的真实 DecorView。

2. **滑动手势防崩桩（`libwlveltrack.so`）**：
   - 当点击或滑动 `HorizontalScrollView` 或 `RecyclerView` 时，系统触发 `UnsatisfiedLinkError: android.view.VelocityTracker.nativeInitialize`。
   - S1 手写了轻量级 C 语言 Native 垫片 `libwlveltrack.so`，实现全部 7 个 `VelocityTracker` 本地方法，并绕过系统 BootClassLoader 白名单，使用 App ClassLoader 进行 `RegisterNatives` 显式绑定，彻底解锁了分类 Tab 切换和新闻列表滑动。

### 3.3 目标架构设计：原生 MMI 穿透式管道拓扑

虽然 `wl-input-pump` 成功支撑了自动化截图与交互，但它依赖文件轮询，延迟高达 50~150ms，且无法支持真实物理多点触控与高速滑动手势。必须建立真正的原生 MMI 穿透管道。

#### 原生输入管道架构拓扑

```
               +---------------------------------------+
               |     OpenHarmony MultimodalInput       |
               |        (MMI Server Service)           |
               +---------------------------------------+
                                   |
                  (Unix Domain Socket / IPC Channel)
                                   |
                                   v
               +---------------------------------------+
               |      OpenHarmony Rosen::Session       |
               | (WindowScene Input Consumer Callback) |
               +---------------------------------------+
                                   |
              [OHOS::MMI::IInputEventConsumer::OnInputEvent]
                                   |
                                   v
+---------------------------------------------------------------------+
|                  liboh_input_bridge.so (Native)                     |
|                                                                     |
| 1. 解析 PointerEvent (Action, X, Y, Pressure, TouchArea, ToolType)  |
| 2. 无锁环形缓冲区 (SPSC Lock-free RingBuffer)                         |
| 3. 写入 Android Native InputChannel 服务端 socketpair fd            |
+---------------------------------------------------------------------+
                                   |
                                   v
+---------------------------------------------------------------------+
|                     Android Runtime Framework                       |
|                                                                     |
| 1. android.view.InputChannel (Client fd)                            |
| 2. InputEventReceiver (Native Looper fd callback)                   |
| 3. InputEventBridge.dispatchOnMainThread(async Handler)             |
| 4. ViewRootImpl.dispatchInputEvent(MotionEvent)                     |
| 5. DecorView -> ViewGroup -> Target View                            |
+---------------------------------------------------------------------+
```

#### 关键技术改造方案

1. **废弃脆弱的 libeventhandler 独立事件循环**：
   - 不在 A2OH 客户端内部手动 new `OHOS::AppExecFwk::EventHandler`（消除导致 `BUS_ADRALN` 的 ABI 陷阱）。
   - 直接向 OpenHarmony 的 `Rosen::Session` 注册 `IInputEventConsumer`：
     ```cpp
     class A2OHInputConsumer : public OHOS::Rosen::IInputEventConsumer {
     public:
         bool OnInputEvent(const std::shared_ptr<OHOS::MMI::PointerEvent>& pointerEvent) override;
         bool OnInputEvent(const std::shared_ptr<OHOS::MMI::KeyEvent>& keyEvent) override;
         bool OnInputEvent(const std::shared_ptr<OHOS::MMI::AxisEvent>& axisEvent) override;
     };
     ```
   - 当 SceneBoard 决定将焦点或触摸分配给当前 Activity 窗口时，系统原生回调 `OnInputEvent`，天然运行在合法对齐的线程上下文中。

2. **原生 InputChannel 双向 SocketPair 打通**：
   - 补齐 `android.view.InputChannel` 的 JNI 实现，提供合法的 `nativeCreateInputChannelPair`。
   - 服务端 fd 由 `liboh_input_bridge.so` 持有，将 OpenHarmony `PointerEvent` 高效转换为 AOSP 二进制 `InputMessage` 并写入 fd。
   - 客户端 fd 绑定到 `ViewRootImpl.mInputChannel`，完全恢复 AOSP 原生的 Looper Epoll 唤醒机制。

### 3.4 线程模型与非阻塞派发保证

- **保证 Choreographer 屏障免疫**：
  在 Java 侧保留 S1 验证的 `Handler.createAsync(Looper.getMainLooper())` 机制。即使 Choreographer 插入了同步屏障（Sync Barrier），输入事件依然能够被优先调度派发，防止在大列表布局时产生丢帧假死。

---

## 四、 课题三：安全与网络协议栈底座（从 2MB BCTLS 到系统级通信底座）

### 4.1 网络协议栈断裂根因与 TTNet 行为建模

今日头条主界面的核心新闻列表依赖字节跳动的 TTNet（基于 Chromium Cronet 深度定制的混合网络栈）。在 A2OH 上，TTNet 面临双重阻断：

```
                            [TTNet 路由引擎]
                                   |
            +----------------------+----------------------+
            | (首选链路: Cronet)                          | (回退链路: OkHttp/JSSE)
            v                                             v
     libsscronet.so                                 OkHttp / TTNet
            |                                             |
     [MUSL 动态加载]                               [SSLContext.getInstance("TLS")]
            |                                             |
            X <-- 缺 ALooper_* 符号 (libandroid.so)        X <-- 缺 Conscrypt / libjavacrypto
            v                                             v
      加载完全失败                                  DoConnect 抛 UnsupportedOperation
```

1. **Cronet 崩溃**：`libsscronet.so` 强依赖 Android NDK 的 5 个 ALooper 接口（`ALooper_prepare` 等）。OpenHarmony 的 `libandroid.so` 缺少这些符号，且 Musl 的命名空间隔离阻断了常规的 `LD_PRELOAD`。
2. **Conscrypt 缺失**：回退到 Java 层的 OkHttp 时，Android AOSP 原生的 Conscrypt（`libjavacrypto.so`）在底包中完全未被集成，而适配层原本只提供了一个空桩 `TlsShimProvider`（直接抛出 `TLS shim: no real networking`）。
3. **数据库阻断**：TTNet 在向服务端发送鉴权请求前，必须查询本地 SQLite 数据库获取 DeviceID 与 InstallID。由于 `android.database.sqlite.SQLiteConnection` JNI 全面缺失，导致网络请求在触发前即崩溃。

### 4.2 战场排雷复盘：SQLite JNI 攻坚与 BCTLS 纯 Java 握手

面对无网络导致的“纯白屏”，S2 连续攻坚并取得了里程碑突破：

1. **SQLite 本地数据通道全面贯通**：
   - 引入 Westlake 官方 C++ 源码，自写 15 个 AOSP 兼容头文件，配合 `sqlite3.c` 编译出 `libwlsqlite.so`。
   - 覆盖全部 27 个 `SQLiteConnection` + 21 个 `CursorWindow` 方法签名，成功让今日头条本地 10 个数据库正常读写。

2. **纯 Java BCTLS 栈全链路打通**：
   - 绕过缺失的 Conscrypt，引入 BouncyCastle 纯 Java TLS 栈，编写 `/dev/urandom` 强熵源提供者，烘焙板端 CA 证书至 PKCS12 信任库。
   - 绕开原先导致死锁与取消的 `RootTrustManager` 兼容缺陷，成功建立 TLS 1.3 / HTTP/2 连接，**直接拉取到了央视新闻、新华社等线上真实新闻流并渲染上屏（`wl-feed.jpeg`）**。

### 4.3 双阶段架构演进路径

尽管当前纯 Java TLS 创造了奇迹，但仍面临**物理瓶颈**：在 ART 纯解释模式（`-Xint`）下，初次加载并校验 5,403 个 BouncyCastle 类耗时高达 28~32 秒，极易触发客户端超时；纯 CPU 软解加解密消耗过多算力。

必须规划双阶段技术演进路线：

#### 4.3.1 近期路线（Phase 1）：BouncyCastle R8 深度裁剪与冷启动加速

**目标**：在不触碰底层 C++ 的前提下，通过代码瘦身将类加载时间从 30 秒压缩到 3 秒以内。

```
[原始 BouncyCastle Jar (2.1 MB)]
  - 包含 5,403 个类
  - 包含大量后量子密码 (pqc/ 6MB)、ASN.1 OER (1.3MB)
  - 包含完整的 CMS, S/MIME, PGP, TSP 模块
                   |
                   | (ProGuard / R8 语法树追踪)
                   v
[精简版 wl-tls-min.jar (< 450 KB)]
  - 保留类数: < 500 个类
  - 仅保留: BCJSSE Provider, TLS 1.2/1.3 状态机,
            X25519, ECDHE, AES-GCM, SHA256/384, X509 基础链
  - 类加载时间: ~2.1 秒 (缩短 90% 以上)
```

**实施规则（ProGuard 约束契约）**：
- `-keep class org.bouncycastle.jsse.provider.BouncyCastleJsseProvider { *; }`
- `-keep class org.bouncycastle.tls.** { *; }`
- `-dontwarn org.bouncycastle.pqc.**`
- `-dontwarn org.bouncycastle.oer.**`

#### 4.3.2 终态路线（Phase 2）：OpenHarmony 原生 BoringSSL 平台级 Provider

**目标**：彻底消灭 Java 类加载负担，实现零拷贝、硬件加速的原生密码底座。

OpenHarmony 系统本身在 `/system/lib64/` 下内置了 `libboringssl.z.so` 和 `libcrypto_shared.z.so`，具备极佳的硬件加速（ARMv8 Crypto Extensions / NEON）。

**架构设计**：
构建标准 JNI 库 `liboh_crypto_provider.so`：
1. **轻量 Java 门面**：定义 `com.android.org.conscrypt.OpenSSLContextImpl` 等标准类（仅 ~20 个精简类，体积 < 50KB）。
2. **JNI 映射层**：直接将加解密、握手状态机、证书解析映射至 OpenHarmony 系统的 `libboringssl.z.so`。
3. **性能对比预测**：
   - 类加载时间：< 10ms
   - 握手延迟：50~80ms（纯软解需要 300~500ms）
   - 内存占用：减少 ~15MB 解释器方法区驻留

---

## 五、 课题四：工业化基线与官方代码合流工程

### 5.1 a2hlab/manifest 体系剖析与闭包构建标准

`a2hlab/manifest` 是整个 A2OH 项目工程化交付的“定海神针”。它彻底解决了前线曾经出现的“本地代码跑通了，但换台机器无法编译”的断代悲剧。

```
+---------------------------------------------------------------------------------+
|                               a2hlab/manifest                                   |
|   - sources.lock.json (锁定 22 个子仓精确 Git Commit Hash)                         |
|   - toolchains.lock.json (锁定 clang-15、ohos-sdk 离线构建包 SHA256)             |
|   - tools/rebuild.py (多 Profile 统一构建流水线)                                  |
+---------------------------------------------------------------------------------+
          |                               |                               |
          v                               v                               v
   [Profile: native]               [Profile: apps]                 [Profile: art]
   - liboh_adapter_bridge.so       - Sample OpenHarmony Apps       - AOSP 15 libart.so
   - liboh_android_runtime.so      - AppSpawnX / Bridge Launcher   - libart-compiler.so
   - 系统级 Shims                   - APK 打包构建                   - ART 本地优化工具
```

### 5.2 战场补丁向官方源码库 (a2hlab/westlake) 的结构化映射

必须将 S1 和 S2 的前线火线补丁，系统性提炼并升华合入 `a2hlab/westlake` 官方代码树：

| 战场火线补丁（战地形态） | 涉及一线文件 | 官方源码归宿（正规形态） | 改造原则 |
|---|---|---|---|
| **子窗口中和与分类路由** | `amr/ActivityManagerRouting.java` | `framework/window/java/WindowSessionAdapter.java` | 移除反射，作为 WMS 规范逻辑实现，管理子窗口会话生命周期 |
| **Theme AXML 兜底解析** | `amr/ActivityManagerRouting.java` (`f0d251c`) | `framework/package-manager/` 与 `ActivityManagerAdapter.java` | 合入包管理解析流程，确保 `ActivityInfo.theme` 正常填充，杜绝启动即崩 |
| **VelocityTracker 桩** | `libwlveltrack.so` | `framework/input/jni/android_view_VelocityTracker.cpp` | 作为系统原生库内置编译，无需 App 侧显式 JNI_OnLoad 注入 |
| **SQLite JNI 完整桥** | S2 手写 compat + `libwlsqlite.so` | `framework/sqlite/` | 补齐官方 CMakeLists.txt，消除本地绝对路径依赖，纳入 native profile 自动化构建 |
| **TLS 安全通信栈** | `wl-tls.jar` + `TlsBootstrap.java` | `framework/security/` | Phase 1 提供裁剪版预编译 jar；Phase 2 构建官方 JNI Conscrypt 替代物 |

### 5.3 质量门禁与无依赖构建契约

1. **绝对路径零容忍**：所有 `CMakeLists.txt`、`Makefile`、`build.sh` 中严禁出现 `/Users/mac/...` 或 `/home/dspfac/...` 等绝对路径，统一使用基于 `$WORKSPACE` 或 `$PROJECT_ROOT` 的相对路径。
2. **纯源码闭包重现**：任何交付物必须可以通过：
   ```bash
   python3 manifest/tools/rebuild.py --workspace /opt/a2h/ws --profile <profile> --out <out_dir>
   ```
   一键无交互输出完全一致的二进制，才算达到工业级验收标准。

---

## 六、 系统演进决策树与行动路线图 (Decision Tree & Roadmap)

### 6.1 关键架构分叉决策树

针对当前及后续开发中可能遇到的核心矛盾，确立如下架构仲裁树：

```
                            [遇到新的适配断裂/崩溃]
                                       |
                   +-------------------+-------------------+
                   |                                       |
          [判定为规范标准行为]                    [判定为系统缺陷或死锁]
          (如: SQLite, VelocityTracker)            (如: 子窗口 EGL 崩溃, JIT 空指针)
                   |                                       |
         [架构决策: 必须源码级补齐]               [架构决策: 分阶段防御式隔离]
         严禁在应用字节码中 nop，                 1. 阶段一: 在适配层设安全网防崩
         必须在 Adapter JNI 补齐标准实现          2. 阶段二: 追溯上游 Native 根因
                   |                                       |
                   v                                       v
         [合流进 a2hlab/westlake]                [沉淀入 ARCHITECTURE_BLUEPRINT]
```

- **分支决策 1（子窗口）**：绝不长期维持 `sSubWindows` 静默；在 S1 验证完主界面多 Tab 后，全面启动 SSM 父子会话拓扑改造。
- **分支决策 2（网络通信）**：在 R8 瘦身完成前，继续使用解释模式保证稳定握手；严禁在未排查 `nterp` 挂起超时（Thread suspension timeout）前盲目在生产分支开启 JIT。
- **分支决策 3（代码交付）**：板端只用于冒烟与验证（S1 独占），所有代码资产化工作必须在 Git 和 Manifest 流水线闭环（S3 承接）。

### 6.2 三军协同作战演进路线图

```
时间轴          Session 1 (界面与交互)           Session 2 (协议与底层)           Session 3 (工程与总装)
---------------------------------------------------------------------------------------------------------
当前阶段        【板端独占】                     【纯离线作业】                   【纯离线 VM】
                合入 S2 SQLite/TLS 成果;         实施 BC R8 规则裁剪,             攻坚 manifest apps/art 构建;
                真机验证 Theme AXML 兜底;        产出 <450KB 极速 TLS jar;        解决 restool 构建缺口;
                打通搜索页与新闻详情;            输出 SQLite compat 补丁包        确立 native profile 闭环
                收集 frames/screens/ 截屏矩阵
       |
       v
演进阶段 1      【多页面穿透】                   【通信优化与协议深耕】           【源码正规化合流】
                探索真实新闻流点击与详情拉起;    分析 HTTP/2 RST_STREAM;          接纳 S1/S2 验证代码,
                开展 Dialog / Popup 解禁预研;    启动 Phase 2 原生 BoringSSL 预研 建立 westlake 官方编译集成;
                输出全功能操作录屏/截屏                                           消灭临时编译脚本
       |
       v
演进阶段 2      【原生 MMI 穿透】                【JIT 崩溃攻坚】                 【工业化一键发布】
                废弃 wl-input-pump，             深入 libart-compiler.so 根因;    22 仓完全纯源码自动化构建;
                打通原生 Rosen Session 输入通道; 修复 ParseCompilerOptions 空指针; 生成标准化发布镜像与
                实现物理触摸低延迟直通           释放 JIT 即时编译性能            CTS 兼容性测试套件
```

---

## 结语

兼容运行时的建设，从来不是零敲碎打的修修补补，而是一场在两个极其庞大且异构的操作系统之间进行精准力学平衡的工程战役。

前线每一条由崩溃日志铸就的战地补丁，都是无价的探索成果；而本架构白皮书的核心使命，就是将这些带着战火硝烟的探索，升华成骨骼健壮、血肉丰满、逻辑自洽的现代化兼容架构体系。

**代码是给人看的，只是机器恰好可以运行。以此为纲，A2OH 必成。**
