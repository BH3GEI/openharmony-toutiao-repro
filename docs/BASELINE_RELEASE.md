# 官方统一适配层运行时 · 标准基线出库记录

作业环境：Lima VM `a2h-x86`（隔离，全程未接触板端）

---

## 1. 统一运行库产物

```
prebuilts/oh-adapter-runtime.jar
  sha256   4e9e3d4669dfaeab3feee668570ad73062db368f101a6c2387e4b41028668579
  bytes    38597
  内容     classes.dex (82144 B, min-api 30) + META-INF/MANIFEST.MF
  时间戳   全部 1980-01-01（zip 存本地时间且无时区，不固定就不可复现）
```

**关键在于它「不含」什么。** 前线那个 jar 是把新编译的类合并进一份
字符串表被逐字节改写过的 `classes.dex`——永远无法从源码重建。这一份没有反编译 dex、
没有 base jar，每一个类都由本次编译产出。westlake 的清单闸门给出证明：

```
reference classes : 33
covered by source : 33
missing source    : 0
duplicate owners  : 0        （--require-complete 下 exit 0）
```

### 确定性

三次独立构建、不同输出目录、最后一次经 manifest recipe 从提交态重跑——**字节完全相同**。

### 功能闭环（在 **dex** 里查，不是在源码里查）

| 能力 | 探针 | 结果 |
|---|---|---|
| S1 窗口拦截 | `sSubWindows` `neutralizeSubWindow` `invalidateSubWindowSurface` | ✅ 3/3 |
| S1 WebView 安全代理 | `installWebViewGuard` `swapInGuardedProvider` | ✅ |
| S1 输入泵 | `wl-input-pump` `wl_input.cmd` | ✅ 2/2 |
| S2 TLS 动态注入 | `hijackTlsShim` `TlsShim` `westlake.tls.TlsBootstrap` | ✅ 3/3 |
| S2 ALooper 桥接 | `libwlalooper.so` `loadAlooperShim` | ✅ 2/2 |
| S2 SQLite 挂载 | `libwlsqlite.so` `loadSqliteShim` | ✅ 2/2 |
| S4 元数据扩展 | `enrichMetaData` `ensureInitialApplication` `WL-META` | ✅ 3/3 |
| S4 Provider 过滤 | `ensureProvidersClean` | ✅ |
| S4 微信门控 | `com.tencent.mm` | ✅ |

> 一处需要说明：探针里原本还有 `TTWebProviderWrapper`，dex 里查不到。
> 查证结果是**探针写错了，不是代码缺失**——该名字在源码里只出现在注释中，
> 守卫包装的是 `WebViewFactory.getProvider()` 返回的任意对象（在该 app 上恰好是它）。

---

## 2. 解决了「没有 dexer」这个卡点

上一轮的结论是本机无 d8，jar 打不出来。这次没有停在那里：Google 把 D8 装在
`r8.jar` 里发布在 `maven.google.com`（**不在** Maven Central，`com/android/tools/r8`
在那边是 404）。取 `9.4.17` 稳定版：

```
r8-9.4.17.jar  sha256 2100511344497f041644a4d63fb7be8a516ce9bace30b7d17ab27cc93a0e58d4
D8 9.4.17 (build c98358724c7e1cb666ffdf35d0808d9d79545e89)
```

`build_adapter_runtime.py` 因此新增 `--r8 <r8.jar>`（经 java 调用，正是 Google 的分发形态），
并把 **dexer 的 SHA256 与自报版本记进 build.json 和锁条目**——它决定产物字节，
而在此之前没有任何地方钉住它的身份。r8.jar 本身**没有入库**，它是宿主工具，同 javac。

---

## 3. `--framework` 为什么是 false，且这不是疏忽

`compiled_against_real_framework: false` 明写在锁里。原因是硬的：
适配层的 `framework.jar` 是 **dex 归档**（`classes.dex` / `classes2.dex` / `classes3.dex`），
而 `javac` 无法针对 dex 编译。

所以 `framework/activity/stubs` 里那 5 个 compile-only 替身**不是图省事的捷径，
而是当前唯一可走的路**，也正是前线在真机上验证过的那条路。涉及 5 个平台类型，
字节码按 name+descriptor 引用它们，形状一致的替身产生的常量池条目与真类一致。

这就是 `full-build-gaps.json` 里那条 adapter 冷启动依赖环从编译侧看到的样子。
把标志写进锁，是不让产物暗示一个它并不具备的闭包。

---

## 4. 全 profile 校验

| Profile | 结果 |
|---|---|
| `native` | ✅ `SOURCE_BUILD_PASS` |
| `apps` | ✅ `SOURCE_BUILD_PASS` |
| `art` | 见文末 |
| `full` | 按设计 fail closed（`complete_source_build: false`） |

两个 profile 的日志首行都是新装的预置件闸门在真流水线里生效：

```
VERIFIED_PREBUILT prebuilts/oh-adapter-runtime.jar 4e9e3d46…8579
VERIFIED_PREBUILT prebuilts/wl-tls-min.jar          5fd2e39f…a547c
```

单元测试：westlake **15/15**，manifest **24/24**，全部 PASS。

---

## 5. 锁与校验清单

`sources.lock.json` 的 `prebuilts` 数组现有两项，各自带 `sha256` / `bytes` /
`recipe` / 来源说明。`tools/verify_prebuilts.py` 在**任何 profile 之前**校验
大小、SHA256，并额外要求 recipe 文件存在于树内；`rebuild.py` 调用它并把结果写进
`build.json`。只记不查的哈希是装饰品。

`tools/recipes/` 现有两份可执行 recipe：`build_tls_min.sh`（含 5 个它真正需要的辅助文件）
与 `build_adapter_runtime.sh`。

---

## 6. 尚未闭合的（如实列出）

1. **`--profile full` 仍出不了镜像**，与补丁多少无关：manifest 没有实现 full 流水线，
   改 `complete_source_build` 会撞上 `raise ValueError(...)`。八条 gap 是跨团队架构项。
2. **本次所有改动均未在真机复验**——板端归 S1/S2/S4，我无权连接。
   统一 jar 与前线各自验证过的版本在能力上等价，但那是源码级论证，不是真机结论。
3. **`PmProxy` 仍无源码**。它不在本 jar 的 33 个类里（本 jar 自身闭合），
   但参考 jar 里有，语义待 S1 确认。
4. **ashmem 真机实验**：`WESTLAKE_OH_ASHMEM=1` 跑一次即可定论。
