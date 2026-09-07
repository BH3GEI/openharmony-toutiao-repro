# OpenHarmony Android Compatibility Layer - Multi-Agent Operating Guide

This guide establishes the universal multi-agent operating rules, domain patterns, and reproduction workflows for AI coding agents (Cursor, Claude Code, Kimi Code, OpenAI Codex, Windsurf, Copilot).

Target Hardware: DAYU200 (RK3568) / OpenHarmony 6.1.0.31 / aarch64
Dual Engines:
- Jinri Toutiao (今日头条, com.ss.android.article.news)
- WeChat (微信 8.0.77, com.tencent.mm)

---

## 1. Core Architectural Mental Model

The runtime bridge (a2oh / Westlake) runs unmodified or minimally patched Android applications on OpenHarmony using an isolated container managed by AppSpawnX.

### Key Architecture Components:
1. Host OS: OpenHarmony 6.1.0.31 (Linux kernel 5.10 / ArkUI / SceneBoard).
2. Spawner: `appspawn-x` manages runtime initialization, namespaces, and bind mounts.
3. System Adapter JAR: `oh-adapter-runtime.jar` located at `/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar`.
4. Dynamic Proxy: `ActivityManagerRouting.java` intercepts `IWindowSession`, `IActivityManager`, and window relayout requests.
5. Native Shims:
   - `libwlsqlite.so`: SQLite and CursorWindow native bridge.
   - `libwlalooper.so`: NDK ALooper compatibility layer for Cronet.
   - `libwlveltrack.so`: Android VelocityTracker JNI bridge for touch gestures.
   - `libwestlake_stackgrow.so`: Musl pthread vs FFRT coroutine stack monitor.
   - `libwlicu.so`: ICU 74 to 72 symbol translation bridge.

---

## 2. Standard Operating Commands

Agents should prefer using repository scripts over manual ad-hoc shell commands:

```bash
# 1. Environment and connectivity verification
./reproduce.sh check

# 2. Reproduce Toutiao (Feed stream + live video stream)
./reproduce.sh toutiao

# 3. Reproduce WeChat (Welcome / Login screen)
./reproduce.sh wechat

# 4. Full dual-engine reproduction and visual verification
./reproduce.sh all

# 5. Remote board communication
./hdc-remote shell "ps -ef | grep -E 'appspawn|article|tencent'"
./hdc-remote file send <local_path> <board_path>
./hdc-remote file recv <board_path> <local_path>
```

---

## 3. The 19 Critical Technical Traps and Solutions

When diagnosing runtime issues or extending features, consult this table:

| No. | Layer / Trap | Symptom | Root Cause and Solution |
|---|---|---|---|
| 1 | AccessToken / seccomp | Socket EPERM / Network unreachable | AppSpawnX maps INTERNET permission to empty token. Write authorization entry directly into access_token.db. |
| 2 | Sub-Window LayoutParams | Surface was not locked crash | ViewRootImpl sends attrs=null on follow-up relayouts. Use sSubWindows WeakRegistry to track sub-windows across all relayout phases. |
| 3 | HWUI EGL_NO_SURFACE | RenderThread abort exit 134 | Sub-window aliases main surface without its own EGL surface. Strip FLAG_HARDWARE_ACCELERATED and destroy hardware renderer. |
| 4 | FFRT Coroutine Stack | ART Check failed: FindStackTop | FFRT worker thread runs on swapped coroutine stack. Intercept pthread_getattr_np in libwestlake_stackgrow.so to return true coroutine bounds. |
| 5 | Stack Canary Mismatch | __stack_chk_fail abort | Bionic and Musl TLS stack guard slots differ. Weakify or patch symbols using tools/wechat/elf_weaken.py. |
| 6 | Musl init_array Sentinel | SIGSEGV in do_init_fini | Bionic allows non-zero trailing pointers in .init_array. Sanitize ELF headers with tools/wechat/sanitize_elf.py. |
| 7 | SplashHack Defense | App hangs on placeholder activity | WeChat wraps ActivityThread.mInstrumentation. Use installInstrumentationUnhook() in ActivityManagerRouting to unwrap the original instrumentation. |
| 8 | Async Inflate Deadlock | Main thread parks in ForkJoinTask.get | Accessibility transit triggers uninitialized plugin loading. Dex-patch AccUtil.isAccessibilityEnabled() to return false. |
| 9 | SQLite Native Layer | UnsatisfiedLinkError on SQLiteConnection | Android database native bridge absent. Load libwlsqlite.so via curAppShim. |
| 10 | Conscrypt / TLS Shim | Cleartext HTTP forbidden / TLS stub exception | OpenHarmony adapter defaults to dummy TLS provider. Deploy pure Java BCTLS/WLTLS provider stack with /dev/urandom entropy. |
| 11 | Timezone Data Format | ZoneInfoDb.getBufferIterator crash | 48-byte vs 52-byte index format mismatch. Mount converted tzdata_52. |
| 12 | Empty External Storage | NullPointerException on getExternalStorageDirectory | Platform volume array returns empty. Inject mock volume for /storage/emulated/0. |

---

## 4. Engineering Discipline

1. Cleanliness and Readability: Code is written for human review; execution by machines is secondary. Always write readable, self-explanatory code with clear comments.
2. Anti-Brick Safety Gate: Never blindly replace `oh-adapter-runtime.jar` on the board without validating the class hierarchy using `tools/check_runtime_replacement.py`. Essential classes like `AppSpawnXInit` must never be dropped.
3. Verification Rigor: A task is not accepted without real display captures. Always capture actual screenshots to `frames/` for visual verification.
