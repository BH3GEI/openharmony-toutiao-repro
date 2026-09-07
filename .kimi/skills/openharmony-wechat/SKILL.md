---
name: openharmony-wechat
description: Guide for developing, debugging, reproducing, and enhancing the Android-to-OpenHarmony (a2oh) compatibility layer for WeChat 8.0.77 (com.tencent.mm). Use when diagnosing WeChat startup crashes, Musl vs Bionic libc incompatibilities, ELF sanitization, SplashHackInstrumentation unhooking, WCDB/CSO native loading, and Android runtime shims on OpenHarmony.
disable-model-invocation: true
---

# OpenHarmony WeChat (微信 8.0.77) Reproduction and Development Skill

## Overview

This skill provides essential domain knowledge, architecture patterns, debugging methods, and historical pitfall solutions for running WeChat 8.0.77 (`com.tencent.mm`) on OpenHarmony devices via the a2oh (Android-to-OpenHarmony) runtime bridge.

- Target Hardware: DAYU200 (RK3568) / OpenHarmony 6.1.0.31 / aarch64.
- Milestone: Successfully booted to the iconic Welcome / Login screen ("Blue Marble" Earth background + "Login / Register" buttons, 1200x1920) without crashing.
- Source Repository: https://github.com/BH3GEI/openharmony-toutiao-repro

---

## Key Technical Traps and Proven Fixes (The 19 Layers)

### 1. Musl vs. Bionic Dynamic Linker Incompatibilities

OpenHarmony uses Musl libc while WeChat native libraries assume Android Bionic:

1. **Trailing Empty Sentinels in ELF `init_array`**:
   - Symptom: SIGSEGV during `dlopen` of `libstlport_shared.so` inside Musl's `do_init_fini`.
   - Root Cause: Bionic allows non-zero trailing pointers in `init_array` that do not point to executable code. Musl attempts to execute them unconditionally.
   - Solution: Use `tools/wechat/sanitize_elf.py` to zero out trailing invalid pointers in `.init_array`.

2. **Missing `getprogname` Symbol**:
   - Symptom: `MUSL-LDSO: relocating failed: symbol not found: getprogname` in `libwechatbacktrace.so`.
   - Solution: Use `tools/wechat/elf_weaken.py` to turn `getprogname` into a WEAK reference, or neutralize Matrix APM hooks.

3. **Stack Canary Mismatch (`__stack_chk_guard`)**:
   - Symptom: Premature `__stack_chk_fail` aborts.
   - Solution: Preload `libwestlake_stackgrow.so` to synchronize TLS offsets.

### 2. WeChat Custom Launch Defense (SplashHack)

WeChat implements an aggressive defensive startup architecture:

1. **`SplashHackInstrumentation` Wrapper**:
   - Symptom: App hangs or displays a blank screen because `SplashHackActivity` is instantiated instead of `WelcomeActivity`.
   - Root Cause: WeChat wraps system `ActivityThread.mInstrumentation` with `SplashHackInstrumentation`. Its `newActivity()` overrides all launcher intents to return `SplashHackActivity`.
   - Solution: In `ActivityManagerRouting.java`, poll `ActivityThread.mInstrumentation` and dynamically unwrap it to restore the original `android.app.Instrumentation`.

2. **Async Inflate Deadlock on Accessibility Transit**:
   - Symptom: Main thread hangs in `ForkJoinTask.get()` waiting for worker thread `wc_srvinit_5`.
   - Root Cause: `AccProviderFactory.onInflateRootAsync` invokes `AccUtil.isAccessibilityEnabled()`, triggering uninitialized plugin life-cycle transitions.
   - Solution: Dex-patch `AccUtil.isAccessibilityEnabled()` to always `return false`.

### 3. Framework & Platform Service Shims

1. **Empty External Storage Volume**:
   - Symptom: Crash on `Environment.getExternalStorageDirectory()` due to empty volume array.
   - Solution: Provide mock mount entry for `/storage/emulated/0` via `ensureMountVolumeShim()`.

2. **Timezone Data Format Discrepancy (48 vs. 52 bytes)**:
   - Symptom: `ZoneInfoDb.getBufferIterator` crashes on platform `tzdata`.
   - Solution: Convert 48-byte index format to 52-byte format and mount `tzdata_52`.

3. **Missing System Telephony / Priority JNI**:
   - Symptom: `UnsatisfiedLinkError` on `Process.getThreadPriority` / `setThreadPriority`.
   - Solution: Implement missing native stubs in `libwlnatives.so`.

---

## Operating and Reproduction Workflow

### 1. One-Click Reproduction

From the root of the repository:

```bash
# Check connectivity and prebuilts
./reproduce.sh check

# Launch WeChat and verify
./reproduce.sh wechat
```

Verification Criteria:
- Target process `com.tencent.mm` remains active (`alive=1`).
- Log output shows `WelcomeSelectView VISIBLE 1200x1920`.
- Verified screenshot saved to `frames/verified_wechat_<timestamp>.jpeg`.

### 2. Manual APK Patching Workflow

```bash
cd tools/wechat
./rebuild_apk.sh
```
This script automates:
- ELF header sanitation on `libstlport_shared.so` and `libwechatbacktrace.so`.
- Injection of `wl-metadata.properties` into asset bundle.
- DEX neutralization of `AccUtil` and `NativeCrash` hooks.
