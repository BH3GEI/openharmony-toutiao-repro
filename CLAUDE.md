# Claude Code Instructions - OpenHarmony Android Runtime

This repository contains the reproduction baseline, diagnostic tools, and compatibility runtime for running commercial Android applications (Toutiao and WeChat 8.0.77) on OpenHarmony 6.1.0.31 / DAYU200 (aarch64).

## Primary Commands

```bash
# Verify environment, hdc connectivity, and local assets
./reproduce.sh check

# One-step reproduction: Toutiao feed & live video stream
./reproduce.sh toutiao

# One-step reproduction: WeChat welcome and login UI
./reproduce.sh wechat

# Run both applications sequentially and capture verification frames
./reproduce.sh all

# Inspect remote board processes and logs
./hdc-remote shell "ps -ef | grep -E 'appspawn|article|tencent'"
./hdc-remote shell "ls -lt /data/service/el1/public/appspawnx/*.stderr | head -3"
```

## Architecture and Key Files

- `reproduce.sh`: Central human-readable reproduction and deployment orchestrator.
- `amr/src/adapter/activity/ActivityManagerRouting.java`: Java dynamic proxy intercepting AMS, WMS, and window lifecycle.
- `docs/ARCHITECTURE_BLUEPRINT.md`: Comprehensive system architecture whitepaper.
- `docs/wechat/PROGRESS.md`: 19-layer diagnostic log for WeChat 8.0.77.
- `docs/BASELINE_RELEASE.md`: Standard baseline release specifications from `a2hlab/manifest`.
- `tools/wechat/`: ELF sanitizers (`sanitize_elf.py`, `elf_weaken.py`) and APK rebuilding scripts.
- `frames/`: Visual verification screenshots (`screens/` for Toutiao, `wechat/` for WeChat).

## Coding Principles and Constraints

1. Code Readability: Code is written for human inspection; execution by machines is secondary. Avoid dense, obscure one-liners. Use descriptive variable names and explicit comments.
2. Emoji Policy: Strictly do not use any emojis in commit messages, logs, code, or technical notes.
3. Anti-Brick Guard: Before modifying or deploying `oh-adapter-runtime.jar`, verify that core platform entry classes (such as `com.android.internal.os.AppSpawnXInit`) are strictly preserved.
4. Acceptance Standard: Every milestone requires concrete display frame evidence saved in `frames/` (e.g., `frames/verified_<app>_<timestamp>.jpeg`). Claims without screenshots are invalid.
