# 今日头条网络栈静态分析 —— 为 TLS 打通铺路

对 `base.apk`（sha256 `b6423fdc…3e48b`，24801 条目 / 21 个 dex）做的**纯静态**分析，
未修改板端运行时。目的：确定「要让首屏信息流有内容，到底该打通哪条链路」。

**结论先行：不建议先去移植 conscrypt。** App 自带了完整的原生 TLS 引擎
（`libsscronet.so` + `libttboringssl.so`），只是当前一次都没被启用。
把 cronet 拉起来，比给适配层补一套 Java JSSE 要短得多，而且能绕开 Java TLS。

---

## 1. TTNet 有三条传输路径

| 路径 | 实现 | TLS 来源 | 当前状态 |
|---|---|---|---|
| **A. 原生 Cronet**（主） | `com.bytedance.ttnet.HttpClient$CronetImpl` / `$SsCronetHttpClientWrap`，底层 `libsscronet.so` (4.3 MB) | **自带 BoringSSL**（`libttboringssl.so`，380 KB），**不经过 Java** | **从未启用**（见 §3） |
| **B. OkHttp**（回退） | `com.bytedance.frameworks.baselib.network.http.ok3.impl.SsOkHttp3Client` | Java JSSE：`SSLContext.getInstance("TLS")` | 当前实际走的就是它，因此撞上适配层的 TLS 桩 |
| C. JavaCronetEngine | `com.ttnet.org.chromium.net.impl.JavaCronetEngine`（纯 Java cronet 壳） | 同样落到 Java JSSE | 未观察到被使用 |

**App 不使用平台的 `com.android.okhttp`**（即 `HttpURLConnection` 那套 BCP 栈）：
全 21 个 dex 里 `com.android.okhttp` 引用数为 **0**。所以适配层的 BCP `okhttp.jar` 与首屏无关，
不必在它上面花力气。

---

## 2. 除 `SSLParametersImpl` 外还反射了哪些 BCP 类

全量扫描 21 个 dex，conscrypt 家族的类名**一共只有 4 个**，且**全部集中在 `classes16.dex`**
（即 okhttp 所在的 dex），全部出现在 okhttp 的 platform 探测里：

| 类名 | 出处 | 现状 |
|---|---|---|
| `com.android.org.conscrypt.SSLParametersImpl` | `Android10Platform.buildIfSupported()` / `AndroidPlatform.buildIfSupported()` | **已用空类 shim 满足**（`patches/prebuilt/classes22.dex`） |
| `org.apache.harmony.xnet.provider.jsse.SSLParametersImpl` | `AndroidPlatform.buildIfSupported()` 的 legacy 回退 | 未命中也无妨（前一个已命中） |
| `com.google.android.gms.org.conscrypt.SSLParametersImpl` | `AndroidSocketAdapter.factory("com.google.android.gms.org.conscrypt")` | 可选，`DeferredSocketAdapter` 容忍缺失 |
| `org.conscrypt.Conscrypt` | `ConscryptPlatform` / `isConscryptPreferred()` | 可选，仅当偏好 unbundled conscrypt 时 |

**没有** `NativeCrypto`、`OpenSSLSocketImpl`、`OpenSSLProvider` 等更深的 conscrypt 内部类引用。

> 也就是说：**okhttp 侧的"类存在性"探测已经被我们完全满足了**，
> 现在卡住的不是找不到类，而是**真的没有可用的 TLS 实现**。

### okhttp 最终要什么

```
Platform.getSSLContext():
    System.getProperty("java.specification.version") == "1.7"
        ? SSLContext.getInstance("TLSv1.2")
        : SSLContext.getInstance("TLS")        <-- 就是这里
```

即需要 `java.security.Security` 里注册一个能提供 `TLS` 的 **JSSE Provider**。
适配层目前的实现是个只能构造、不能通信的桩：

```
BaseException{errorCode=1000, errorMsg='[d-ex]:DoConnect-
  java.lang.UnsupportedOperationException:
  TLS shim: no real networking (construct-only SSLContext on OH)'}
```

另有一个可用的开关：

```
isConscryptPreferred():
    System.getProperty("okhttp.platform").equals("conscrypt")   -> true
    否则扫描 Security.getProviders() 找 conscrypt
```

如果将来真的接入 unbundled conscrypt（`org.conscrypt`），
设置系统属性 `okhttp.platform=conscrypt` 即可让 okhttp 优先走它，
**不需要改 App**。

---

## 3. 为什么 Cronet 一次都没跑起来（核心靶标）

板端日志里 **`cronet` 出现次数为 0** —— 不是初始化失败，是**根本没走到**。

`com.bytedance.ttnet.HttpClient.isCronetClientEnable()` 的判定（反汇编还原）：

```java
if (sHttpClientConfig == null) {                       // 闸 1
    SsOkHttp3Client.setFallbackReason(<code>);
    TNCManager.getInstance().handleCronetInitFailed();
    return false;
}
if (!sHttpClientConfig.isChromiumOpen()) return false;  // 闸 2
if (sIsCronetException || !sCronetBootSucceed) {        // 闸 3
    SsOkHttp3Client.setFallbackReason(<code>);
    SsOkHttp3Client.setFallbackMessage(sCronetExceptionMessage);
    return false;
}
return true;
```

三道闸：

| 闸 | 含义 | 由谁置位 |
|---|---|---|
| 1 | `sHttpClientConfig` 未注册 | 宿主调用 `HttpClient.setHttpClientConfig(IHttpClientConfig)` |
| 2 | `isChromiumOpen()` 为 false | TTNet settings / AB 开关。**注意先有鸡后有蛋**：这个开关本身通常要联网拉取 |
| 3 | 原生 cronet 未启动成功 | `CronetLibraryLoader.loadCronetLibrary()` → `System.load("sscronet")`，成功后 `HttpClient.setCronetBootSucceed(true)` |

**现成的诊断钩子**：TTNet 自己记录了回退原因，
`SsOkHttp3Client.getFallbackReason()` / `getFallbackMessage()`。
下一阶段第一件事就是在运行时把这两个值打出来 —— 它直接告诉你卡在哪一道闸，
不必再猜。可以用现成的 `ActivityManagerRouting` 进程内探针反射读取。

### Cronet 起来之后会遇到的第二片雷区

原生 cronet 会通过 JNI 回调 Chromium 的 Java 胶水层
（`org.chromium.net.*`，本 apk 里有 **377** 个类），其中大量直接调 Android framework：

- `AndroidNetworkLibrary`（+ `$NetworkSecurityPolicyProxy`、`$SocketFd`）
- `AndroidCellularSignalStrength`、`AndroidTrafficStats`、`AndroidKeyStore`
- `AndroidCertVerifyResult`

这些正是适配层最容易缺桩的地方 —— 注意我们已经踩过
`android.net.TrafficStats.getMobileRxBytes()` 缺失（见 `docs/ROOT-CAUSES.md`）。
建议起 cronet 前先按这个清单预检一遍。

---

## 4. APK 内与网络/加密相关的原生库

| 库 | 大小 | 说明 |
|---|---|---|
| `libsscronet.so` | 4.3 MB | ByteDance 版 Cronet（Chromium 网络栈） |
| `libttboringssl.so` | 380 KB | **自带 BoringSSL —— 真正的 TLS 引擎** |
| `libtnet-3.1.14.so` | 363 KB | TTNet 原生部分 |
| `libttcrypto.so` | 1.2 MB | 加密工具 |

`libttboringssl` 的引用同时出现在 `classes8.dex` 与 `classes16.dex`。

---

## 5. 给下一阶段的建议（按性价比）

1. **先读回退原因。** 运行时反射 `SsOkHttp3Client.getFallbackReason()` /
   `getFallbackMessage()`，确定卡在闸 1 / 2 / 3 的哪一道。**这一步几乎零成本。**
2. **优先走 Cronet 路线。** BoringSSL 已经在包里，走通后 TLS 完全不经过 Java，
   等于绕开适配层缺失的整个 JSSE。预计工作量集中在：
   让 `System.load("sscronet")` 成功 + 补齐 `org.chromium.net.Android*` 依赖的 framework 桩。
3. **闸 2 的鸡蛋问题**要留意：`isChromiumOpen()` 来自 TTNet settings，
   而 settings 又要联网。可能需要先让**明文 HTTP** 通（见下）打破循环，或本地强制该开关。
4. **conscrypt 移植放到最后。** 只有在 cronet 路线走不通时才值得做；
   届时接 unbundled `org.conscrypt` 并设 `okhttp.platform=conscrypt` 即可，App 侧无需改动。

### 附带发现

日志里出现过
`java.io.IOException: Cleartext HTTP traffic to bdsp.x.jd.com not permitted`
—— 说明**明文 HTTP 也被 NetworkSecurityPolicy 拦了**。
如果要用明文链路做连通性验证/打破 §5.3 的循环，需要同时放开 cleartext 策略
（`AndroidNetworkLibrary$NetworkSecurityPolicyProxy` 是 cronet 侧的入口）。

---

## 复现本分析

```bash
# 类名扫描（21 个 dex 全量正则）
python3 - <<'PY'
import zipfile, re
z = zipfile.ZipFile('base.apk')
pat = rb'com\.android\.org\.conscrypt\.[A-Za-z0-9_$.]+'
for n in (x for x in z.namelist() if x.endswith('.dex')):
    for m in set(re.findall(pat, z.read(n))):
        print(n, m.decode())
PY

# 反汇编判定逻辑
python3 patches/tools/dexclass.py 'Lcom/bytedance/ttnet/HttpClient;' isCronetClientEnable
python3 patches/tools/dexclass.py 'Lokhttp3/internal/platform/Platform;' getSSLContext
```

> `patches/tools/dexclass.py` 顶部的 apk 路径是常量，按需修改。
