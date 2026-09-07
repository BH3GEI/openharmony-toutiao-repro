# OpenAI Codex Instructions - OpenHarmony Android Runtime Project

## Context & Objectives

This project establishes the reproduction and development baseline for running commercial Android applications on OpenHarmony 6.1.0.31 (DAYU200 / RK3568 / aarch64) using the Westlake `a2oh` compatibility layer.

Supported Applications:
1. Jinri Toutiao (`com.ss.android.article.news`)
2. WeChat 8.0.77 (`com.tencent.mm`)

## Commands Reference

```bash
# Verify connection to DAYU200 via hdc
./reproduce.sh check

# Run Toutiao reproduction pipeline
./reproduce.sh toutiao

# Run WeChat reproduction pipeline
./reproduce.sh wechat

# Run full reproduction pipeline
./reproduce.sh all
```

## Key Architectural Patterns

- Window Management:
  AOSP `ViewRootImpl.relayoutWindow` passes `attrs == null` on non-initial relayout calls. The proxy layer in `amr/src/adapter/activity/ActivityManagerRouting.java` tracks sub-windows via `sSubWindows` registry to prevent `Surface was not locked` and `EGL_NO_SURFACE` crashes.
- Dynamic Linker:
  OpenHarmony uses Musl libc. Trailing invalid sentinels in Android Bionic `.init_array` trigger crashes during `do_init_fini`. Always sanitize with `tools/wechat/sanitize_elf.py`.
- Runtime Preservation:
  Never overwrite `oh-adapter-runtime.jar` on the target device if it removes system entry points like `AppSpawnXInit`.

## Guidelines for Generated Code

- Readability is paramount: "Code is for humans to read, and only incidentally for machines to execute."
- Zero emojis: Do not include emojis in code, commits, or documentation.
- Visual proof: Always assert success with real frame captures located in `frames/`.
