---
name: openharmony-toutiao
description: Guide for developing, debugging, reproducing, and enhancing the Android-to-OpenHarmony (a2oh) compatibility layer for Jinri Toutiao (今日头条). Use when working on OpenHarmony app reproduction, diagnosing a2oh runtime crashes, patching Toutiao APK dex files, modifying ActivityManagerRouting, or handling HWUI, EGL, and sub-window lifecycle on OpenHarmony.
disable-model-invocation: true
---

# OpenHarmony Toutiao (今日头条) Reproduction and Development Skill

## Overview

This skill provides essential domain knowledge, architecture patterns, debugging methods, and historical pitfall solutions for running Jinri Toutiao (com.ss.android.article.news) on OpenHarmony devices via the a2oh (Android-to-OpenHarmony) runtime bridge.

Target Hardware: DAYU200 (RK3568) / OpenHarmony 6.1.0.31 / aarch64.
Source Repository: https://github.com/BH3GEI/openharmony-toutiao-repro

---

## Architecture and Key Components

1. Runtime Stack:
   - Host OS: OpenHarmony (Linux kernel / ArkUI / SceneBoard).
   - AppSpawnX: OpenHarmony process spawner managing isolated Android runtime containers.
   - Framework Adapter: `oh-adapter-runtime.jar` located at `/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar`.
   - Dynamic Proxy Layer: `ActivityManagerRouting.java` intercepts `IWindowSession` and activity lifecycle calls to bridge Android WMS semantics to OpenHarmony WindowManager.

2. Repositories and Prebuilt Assets:
   - Release v1.0-firstframe: https://github.com/BH3GEI/openharmony-toutiao-repro/releases/tag/v1.0-firstframe
   - `base.final6.apk`: Fully patched runnable Toutiao APK (138MB).
   - `oh-adapter-runtime.jar`: Patched runtime adapter with sub-window neutralization and registry.
   - `libwlicu.so`: ICU 74 to 72 symbol bridge.
   - `libwestlake_stackgrow.so`: Native crash interceptor and stack expansion shim.

---

## Debugging and Operating Commands

All board operations are orchestrated from the workspace:

1. Board Shell and Communication:
   ```bash
   # Execute command on board
   ./bin/bsh <<'EOF'
   ps -ef | grep AppSpawnX
   EOF

   # Send file to board
   ./hdc-remote file send <local_file> <board_path>

   # Receive file from board
   ./hdc-remote file recv <board_path> <local_file>
   ```

2. WSL Build Environment (Windows Host):
   ```bash
   # Run command inside WSL Ubuntu container
   ./bin/wslsh <<'EOF'
   cd /home/yao/tt-work/amr
   # compile or inspect
   EOF
   ```

3. Board Stderr Logs:
   Container logs are written to `/data/service/el1/public/appspawnx/adapter_child_<pid>.stderr`.
   ```bash
   F=$(ls -t /data/service/el1/public/appspawnx/*.stderr | head -1)
   tail -n 100 $F
   ```

4. Keep Device Awake and Capture Screenshots:
   ```bash
   # Wakeup display
   power-shell wakeup

   # Capture display frame
   snapshot_display -f /data/local/tmp/screen.jpeg
   ```

---

## Critical Traps and Proven Fixes

### 1. Sub-Window Null LayoutParams Trap (attrs == null)

- Symptom:
  Crash with `java.lang.IllegalStateException: Surface was not locked` at `Surface.unlockSwCanvasAndPost` on the main thread during software fallback drawing.
- Root Cause:
  AOSP `ViewRootImpl.relayoutWindow()` sends `WindowManager.LayoutParams` only when layout attributes change. Subsequent visibility or size relayout calls pass `attrs == null`.
  Checking `getLayoutParamsType(attrs)` on the current call returns -1, causing the proxy to miss the sub-window on follow-up calls. The adapter returns `SURFACE_CHANGED (2)`, triggering an unsuppressed software draw on an unbacked surface.
- Proven Solution:
  Maintain a synchronized weak registry:
  ```java
  private static final Set<Object> sSubWindows =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
  ```
  Identify sub-windows across three tiers:
  1. Current `attrs != null && type >= FIRST_SUB_WINDOW` (register into `sSubWindows`).
  2. `sSubWindows.contains(window)`.
  3. Reflective probe via `window.mViewAncestor -> ViewRootImpl.mWindowAttributes.type`.
  On all sub-window relayouts:
  - Zero the result: `return 0`.
  - Destroy HardwareRenderer: `vri.destroyHardwareRenderer()`.
  - Suppress draw: post-invoke `vri.mSurface.release()` to make `surface.isValid() == false`.
  - Clear draw trigger: `vri.mReportNextDraw = false` and `vri.setWindowStopped(true)`.

### 2. HWUI Native Crash (EGL_NO_SURFACE on RenderThread)

- Symptom:
  `ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE msg=drawRenderNode called on a context with no surface!` causing `abort()` (exit code 134).
- Root Cause:
  PopupWindow (type=1000) arrives with null token, gets assigned fallback `session=1`. Its `SurfaceControl` aliases the main window's native window. RenderThread tries to draw hardware nodes without a dedicated EGL surface.
- Solution:
  Drop hardware acceleration flags (`maskFlags`, remove `FLAG_HARDWARE_ACCELERATED`) and invoke `destroyHardwareRenderer()` before calling native adapter methods.

### 3. Dex Static Initialization Traps

When upgrading or rebuilding `base.apk`, verify the following bytecode patches:
- `classes20.dex`: `PrivateApiLancetImpl.<clinit>` references 8 missing `MediaStore` fields. Patch method body to `return-void`.
- `classes8.dex`: `HeadsetHelperOpt.p()` and `r()` call unlinked `AudioPortEventHandler.native_setup`. Patch method body to `return-void`.
- `classes15.dex`: `ArticleMainActivity.delayInit()` calls `getAppName()` on null context. Patch method body to `return-void`.
- `classes6.dex`: `AsyncImageView.<clinit>` invokes unoptimized `ColorMatrixColorFilter`. Neutralize static initializer.
- `classes16.dex`: `AppLog` initialization requires null-safe app name check.

Use `patches/patch_base_apk.py` in the repository for one-shot deterministic patching.

### 4. Native ICU Symbol Bridge (libwlicu.so)

- Symptom:
  Crash in `platform-single` thread with SIGSEGV due to missing ICU 74 symbols (`u_errorName_74`, `ucnv_open_74`, etc.).
- Solution:
  Load `libwlicu.so`, which exports ICU 74 symbol names and forwards them to system `libicu.so` (version 72).

### 5. ART JIT Crash Avoidance

- Symptom:
  Process aborts within 20 seconds when JIT is active due to a null pointer in `JitCompiler::ParseCompilerOptions`.
- Workaround:
  Keep interpreter mode active in `/system/etc/init/appspawn_x.cfg`:
  ```json
  { "name" : "APPSPAWNX_NO_JIT", "value" : "1" },
  { "name" : "APPSPAWNX_FORCE_INT", "value" : "1" }
  ```

---

## One-Shot Reproduction Workflow

To verify the app on a fresh device:

1. Fetch Prebuilt Assets:
   ```bash
   ./fetch_prebuilts.sh
   ```
2. Verify Hashes:
   ```bash
   sha256sum -c prebuilts/SHA256SUMS
   ```
3. Deploy Runtime and APK:
   ```bash
   ./scripts/deploy_and_run.sh
   ```
4. Verify Success Criteria:
   - Target process maintains `alive=1` for over 120 seconds.
   - Screenshot size transitions from 38KB (splash) to ~72KB (MainActivity).
   - Display shows red header, search bar, navigation tabs, and bottom bar.
   - Zero occurrences of `EGL_NO_SURFACE` or `Surface was not locked` in adapter logs.

---

## Future Enhancement Roadmap

When extending functionality beyond the initial frame render:
1. TLS and Network Stack: Replace the presence-only `conscrypt-shim` with a real Conscrypt/BoringSSL JNI bridge to allow HTTPS network requests to fetch article streams.
2. Sub-Window Scene Sessions: Enable true OpenHarmony SubWindow allocation in `WindowSessionAdapter.java` so popup dialogs and menus can render and receive touch input.
3. Media Framework: Bridge `AudioTrack` and `SurfaceView` to OpenHarmony audio/video hardware sinks for multimedia playback.
4. JIT Compiler Fix: Resolve the null pointer in `libart-compiler.so` to restore JIT compilation performance.
