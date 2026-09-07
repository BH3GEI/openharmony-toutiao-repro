# GitHub Copilot & Codex Instructions

## Project Context
OpenHarmony Android Compatibility Layer (Westlake a2oh).
Target: DAYU200 (RK3568 / aarch64) running OpenHarmony 6.1.0.31.
Apps: Jinri Toutiao and WeChat 8.0.77.

## Key Rules
1. Code Style: Human-readable, clear architecture, descriptive naming, no obscure tricks. "Code is for humans to read, and only incidentally for machines to execute."
2. No Emojis: Strictly avoid using emojis in code, logs, and commit messages.
3. Sub-window lifecycle: Always check `sSubWindows` registry when handling `ViewRootImpl` relayouts where `attrs == null`.
4. Musl vs Bionic: Account for Musl dynamic linker semantics (no `getprogname`, strict `.init_array` traversal).
5. Verification: All functional claims must be verified by screenshots captured to `frames/`.
