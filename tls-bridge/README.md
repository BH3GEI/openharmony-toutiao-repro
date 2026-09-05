# TLS 桥接层

给适配层补上一套**真实可用的 Java TLS**，让 TTNet/okhttp 的 HTTPS 请求真的能发出去。

## 为什么需要

适配层的 Java 侧**整个没有 TLS 实现**：

- `core-oj.jar` 只有 `javax.net.ssl` 的 API 面，**没有一行 `sun.security.ssl`**
- `bouncycastle.jar` 是 AOSP 那份只剩加解密原语的 bcprov（**没有 JSSE**）
- conscrypt 连类带 `libjavacrypto.so` 都不存在
- 但 `security.properties` 还照抄着 `security.provider.1=…conscrypt.OpenSSLProvider`

于是 `SSLContext.getInstance("TLS")` 拿到的是一个**构造得出来、一用就抛**的桩：

```
BaseException{errorCode=1000, errorMsg='[d-ex]:DoConnect-
  java.lang.UnsupportedOperationException:
  TLS shim: no real networking (construct-only SSLContext on OH)'}
```

## 做法

把上游 BouncyCastle 的**纯 Java JSSE（bctls）**接进 app 进程：

```
ActivityManagerRouting.attachApplication()        ← 进程内最早的钩子
  ├─ hijackTlsShim()      同步，仅改 Map          ← 顶替适配层的假 provider
  └─ 后台线程
       └─ DexClassLoader("/data/local/tmp/wl-tls.jar")
            └─ westlake.tls.TlsBootstrap.install()
                 ├─ WLBC    上游 BouncyCastle（改名，避开 AOSP 的 "BC"）
                 ├─ BCJSSE  上游 bctls，显式绑定到 WLBC，插到 provider 第 1 位
                 ├─ 信任锚  wl-cacerts.p12（板子自己的 133 个根，宿主机预烘焙）
                 └─ warmUp() 丢弃一次握手，把冷代码路径捂热后再放行
```

三个关键设计（都是被真机打脸之后才定下来的）：

- **网关顶替，而不是抢跑。** okhttp 会把第一次拿到的 `SSLSocketFactory` **缓存住**，
  所以「把真 provider 插到第 1 位」不够——晚到一步就永远输。改成把 shim 自己的
  服务条目重新指向我们的网关类：来早的调用者在 latch 上等，**于是不存在错误答案可供缓存**。
- **`android.net.ssl.SSLSockets` 必须进 app dex。** okhttp 的 `Android10Platform`
  只看 `SDK_INT>=29` 就去调它，而这块属于 conscrypt 的 API 面，板上没有。
  它由 **app 的类加载器**解析，放在 adapter jar 里够不着（已用探针证实），
  所以作为 `classes23.dex` 追加进 `base.apk`。
- **先预热再放行。** 完整握手 **5195 ms → 159 ms（约 33×）**，
  省下的是类加载与校验、不是会话复用（预热与自检打的是不同域名，排除了这个混淆项）。

## 已达成（均有真机凭据）

| 项 | 证据 |
|---|---|
| 适配层具备真实 TLS | `providers=WLBC/BCJSSE`，`handshake=OK TLSv1.3/TLS_CHACHA20_POLY1305_SHA256` |
| 证书链校验 | `roots=133 trust=prebuilt`，用板子自己的根证书验真实站点证书 |
| okhttp/TTNet 那条路可用 | `okhttpPath=OK anchors=133 TLSv1.3` |
| TTNet 不再报错 | `DoConnect` 失败 **4 → 0** |
| 连得上真实业务域名 | `api.toutiaoapi.com` / `is.snssdk.com` / `ib.snssdk.com` / `abtest-ch.snssdk.com` |
| 未破坏既有成果 | 每轮进程 180 s 全程 `alive=1`，主界面首帧照常渲染 |

## 尚未达成

**信息流仍是空的。** App 自己发起的连接大多在 0–3 s 内被它自己 `user_canceled` 掉
——0 s 更像「上层压根没在等握手」，而不是「等超时」。
下一步应顺 `X.QvG.A → X.Qw9.q → X.QwC.t` 这条 TTNet 调用链反查它的取消逻辑，
而不是继续从 TLS 这侧猜。

另一条高性价比方向是 **nterp**：`APPSPAWNX_FORCE_INT` 对应 `-Xint`、
`APPSPAWNX_NO_JIT` 对应 `-Xusejit:false`，**两者是独立的**。
之前只试过两个一起关（那会打开 JIT 并撞上 `JitCompiler::ParseCompilerOptions` 空指针），
而只把 `FORCE_INT` 改成 0 可以在**完全不碰 JIT 崩溃路径**的前提下换用快速解释器。
该项需要重启板子。

## 目录

```
prebuilt/wl-tls.jar        BouncyCastle bctls + 我们的 TlsBootstrap，DexClassLoader 加载
prebuilt/wl-cacerts.p12    133 个根证书（从板子自己的信任库预烘焙）
prebuilt/classes23.dex     android.net.ssl.SSLSockets（792 B，追加进 base.apk）
src/westlake/tls/          TlsBootstrap / WlSSLSocketFactory / UrandomEntropySourceProvider
src/SSLSockets.java        classes23.dex 的源码
build_tls.sh               拉 BouncyCastle → 编译 → dex → 打包 + 烘焙信任库
```

## 用法

TLS 桥接需要三样东西同时到位：

```bash
# 1. 带 classes23.dex 的 apk
python3 patches/patch_base_apk.py /path/to/base.apk --tls -o prebuilts/base.final7.apk

# 2. TLS 载荷推到板子（deploy_and_run.sh --tls 会自动做）
hdc file send tls-bridge/prebuilt/wl-tls.jar     /data/local/tmp/wl-tls.jar
hdc file send tls-bridge/prebuilt/wl-cacerts.p12 /data/local/tmp/wl-cacerts.p12

# 3. 带 TLS 网关的 adapter jar（ActivityManagerRouting 里的 hijackTlsShim）
scripts/deploy_and_run.sh --tls
```

三者缺一不可：apk 缺 `classes23.dex` → okhttp 的 Android10Platform 崩；
板上缺 `wl-tls.jar` → `TlsBootstrap` 加载不到；
jar 里缺网关 → 假 provider 不会被顶替。

自己重建 `wl-tls.jar`（需要联网拉 BouncyCastle）：

```bash
export JAVA_HOME=/path/to/jdk   D8=$(command -v d8)
tls-bridge/build_tls.sh
```
