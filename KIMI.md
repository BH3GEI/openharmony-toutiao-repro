# Kimi Code Instructions - OpenHarmony Android Compatibility Project

## Project Overview

This workspace hosts the OpenHarmony Android compatibility runtime (a2oh / Westlake) designed to execute Android applications on OpenHarmony 6.1.0.31 / DAYU200 (aarch64).

Core Achievements:
1. Jinri Toutiao (com.ss.android.article.news): MainActivity feed, video stream playback, and simulated touch interaction.
2. WeChat (com.tencent.mm 8.0.77): Blue Marble welcome screen and login UI rendered without crashing.

## Operating Commands

```bash
# Check board connection and prebuilt binaries
./reproduce.sh check

# Execute Toutiao reproduction workflow
./reproduce.sh toutiao

# Execute WeChat reproduction workflow
./reproduce.sh wechat

# Execute full suite verification
./reproduce.sh all

# Board inspection utilities
./hdc-remote shell "uptime"
./hdc-remote shell "ps -ef | grep appspawn"
```

## Critical Technical Guidelines

1. Hardware Target:
   - Device: DAYU200 (RK3568), aarch64.
   - OS: OpenHarmony 6.1.0.31 (Release Freeze L01-02-VERSION-FREEZE-20260711).
   - Adapter runtime path: `/data/pr03-74e6-portable/android/framework/oh-adapter-runtime.jar`.

2. Domain Conventions:
   - Dynamic Proxy: All window interception and activity lifecycle adaptation must go through `ActivityManagerRouting.java`.
   - Sub-Window Registry: Handle second-relayout `attrs == null` using the `sSubWindows` weak registry.
   - ELF Sanitization: Precompiled Android native libraries containing trailing empty sentinels in `.init_array` must be cleaned with `tools/wechat/sanitize_elf.py`.
   - Instrumentation Unhook: WeChat's `SplashHackInstrumentation` must be dynamically unwrapped at runtime to restore genuine activity instantiation.

3. Engineering Principles:
   - Code is for human understanding first. Always write clear, maintainable, well-structured logic.
   - Do not use emojis in code, commits, or documentation.
   - Verify every state change with visual frame captures stored under `frames/`.
