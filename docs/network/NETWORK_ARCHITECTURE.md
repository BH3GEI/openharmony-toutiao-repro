# Network protocol stack — architecture and rationale

The compatibility layer runs an Android app (Toutiao) on OpenHarmony/musl, where
the platform ships **no usable TLS**: `core-oj.jar` carries the `javax.net.ssl`
API but not one line of `sun.security.ssl`; the bundled `bouncycastle.jar` is
AOSP's crypto-only bcprov; and conscrypt — which `security.properties` still names
as `security.provider.1/4` and as `ssl.SocketFactory.provider` — is absent,
classes and `libjavacrypto.so` alike. So `SSLContext.getInstance("TLS")` has no
implementation to find, every TTNet `DoConnect` dies, and the feed stays empty.

This document records the design that fixes it: a **pure-Java TLS stack** built on
upstream BouncyCastle (bcprov + bcutil + bctls + BCJSSE), installed as a real JCA
provider, plus the two native adaptations the JVM side depends on. It is the
consolidation reference for the assets handed to the official build pipeline
(`wl-tls-min.jar`, `libwlalooper.so`).

---

## 1. Provider architecture

```
app / TTNet / okhttp
        │  SSLContext.getInstance("TLS")  /  TrustManagerFactory.init(null)
        ▼
  WLTLS  (WlProvider, slot 1)      ── front-end: SSLContext.TLS, TrustManagerFactory.PKIX
        │  substitutes RootTrustManager, answers init(null) from memory
        ▼
  BCJSSE (BouncyCastleJsseProvider) ── the real JSSE, bound explicitly to ...
        ▼
  WLBC   (BouncyCastleProvider)     ── the crypto, renamed from "BC" to dodge AOSP's
                                        crypto-only "BC" already on the boot classpath
```

Three deliberate choices, each of which was a bug before it was a fix:

- **Renaming BC → WLBC.** Upstream `BouncyCastleProvider` also calls itself `"BC"`,
  and the board already has an AOSP crypto-only `"BC"` on the boot classpath, so
  `Security.addProvider` silently no-ops on the name clash. The upstream provider
  is renamed by reflection on `Provider.name` to `"WLBC"`, and BCJSSE is
  constructed against that explicit instance rather than resolved by name.

- **A `WLTLS` front-end at slot 1.** okhttp/TTNet ask the *platform* for trust
  managers via `TrustManagerFactory.getInstance(getDefaultAlgorithm()).init((KeyStore) null)`.
  On this board the platform answers with
  `android.security.net.config.RootTrustManager`, whose `checkServerTrusted()`
  constructs conscrypt's `TrustManagerImpl` — absent here — so mid-handshake it
  throws `NoSuchMethodError`. Being an `Error`, it slips past okhttp's
  `catch (Exception)`, unwinds through `connectTls()`, and the finally-block closes
  the socket: that is the `user_canceled(90)` seen on nearly every connection.
  `WlSslContextSpi.substitute()` swaps that one trust manager for ours;
  `WlTrustManagerFactorySpi` answers `init(null)` from the anchors already loaded.

- **`ssl.SocketFactory.provider` blanked.** `security.properties` points it at a
  conscrypt class that is not present. `SSLSocketFactory.getDefault()` resolves it
  with `Class.forName` / the system loader, neither of which can see a class
  loaded from our DexClassLoader, and the resulting `NoClassDefFoundError` is an
  `Error` that escapes libcore's `catch (Exception)`. Blanking the property turns
  that into a plain `ClassNotFoundException`, which *is* caught, so `getDefault()`
  falls through to the `SSLContext` we install.

Verified end to end against six production hosts: `handshake=OK
TLSv1.3/TLS_CHACHA20_POLY1305_SHA256`, `roots=133 trust=bundle`, `okhttpPath=OK`,
and TTNet `DoConnect` failures **4 → 0**.

---

## 2. TLSv1.3 handshake latency — the Warmup mechanism

### The problem

Under the board's forced-interpreter runtime (`-Xint`), the *first* TLS handshake
is dramatically slower than steady state: the JSSE engine, the cipher suites, the
named-group (X25519) key-agreement classes and the DRBG are all lazily
class-loaded and initialised on first use, on the critical path of the app's first
network request — inside TTNet's connect timeout. Measured cold first-handshake
was multiple seconds; TTNet times out and marks the connection `user_canceled`.

### The mechanism

`TlsBootstrap.warmUp()` runs **two throwaway handshakes** immediately after the
provider is installed, before the app issues any request:

- `warmUpDirect()` — `sContext.getSocketFactory().createSocket(host, 443)` then
  `startHandshake()`. Uses the `(host, port)` factory form, not the no-arg one,
  so the peer name reaches SNI (without it a CDN edge serves its default cert, not
  the host's).
- `warmUpLayered()` — the same handshake but layered over an already-connected
  raw `Socket` via `createSocket(raw, host, port, true)`. This is the exact shape
  okhttp uses (`connectTls()` layers TLS over a pooled TCP socket), so it warms
  the layered code path specifically, not just the direct one.

Both are wrapped so a failure is logged and swallowed (`"harmless, path is still
warm"`): the point is to force class-load + JIT-independent first-use
initialisation, not to reach any particular server. Gated by
`-Dwestlake.tls.warmup=false` for measurement.

### Effect

The steady-state handshake after warmup is ~200–250 ms; the app's first real
request no longer pays the cold-init cost and stops tripping the connect timeout.
The `phases:` log line (`crypto/jsse/truststore/trustmgr/context` ms) is emitted
at install so the cost of each init stage is measurable per run.

---

## 3. Entropy source reconstruction

### The problem

BC registers its own `SecureRandom.DEFAULT` →
`org.bouncycastle.jcajce.provider.drbg.DRBG$Default`, and BCJSSE asks the crypto
provider for exactly that during a handshake. `DRBG$Default`'s static initialiser
calls `createBaseRandom(true)`, which — with no override configured — bootstraps
its entropy by probing a hardcoded list of providers
(`DRBG.initialEntropySourceNames`):

```
sun.security.provider.Sun                   — not in AOSP libcore
org.apache.harmony…CryptoProvider           — not present
com.android.org.conscrypt.OpenSSLProvider   — absent
org.conscrypt.OpenSSLProvider               — absent
```

All four are missing, so the static initialiser throws, and every later touch of
the class surfaces as `NoClassDefFoundError: …DRBG$Default` — which killed TTNet's
`DoConnect` even after the provider was installed.

### The reconstruction

`westlake.tls.UrandomEntropySourceProvider` implements BC's
`EntropySourceProvider`, reading raw bytes straight from `/dev/urandom`. Naming it
in the `org.bouncycastle.drbg.entropysource` system property makes
`createBaseRandom()` take its first branch — building an SP800-90A hash DRBG over
this source — so the provider-probing path never runs.

Two properties make this correct and safe:

- BC instantiates it by name via `ClassUtil.loadClass(DRBG.class, …)`, i.e. with
  DRBG's own class loader — the same dex this class ships in — so it resolves.
- It reads the device **directly**, never through JCA. Any fallback that
  re-entered JCA could re-enter the very provider still being constructed.

`isPredictionResistant()` returns true because `/dev/urandom` is continuously
reseeded by the kernel pool.

---

## 4. ALooper adaptation under musl

### The problem

The board's `libandroid.so` exports 750 dynamic symbols but **no `ALooper_*`**;
the only NDK families it provides are ANativeWindow / ASurfaceControl /
ASurfaceTransaction / AFileDescriptor. Native consumers that import `ALooper_*`
therefore fail to relocate under the musl dynamic linker:

```
MUSL-LDSO: relocating failed: symbol not found.  s=ALooper_prepare
```

`libsscronet.so` (cronet) imports five (`prepare/acquire/release/addFd/removeFd`);
`libmetasec_ml.so` imports three (`prepare/pollOnce/forThread`) plus the `ASensor*`
family. A missing symbol here aborts `System.loadLibrary`, and the app disables
the dependent component.

### The adaptation

`libwlalooper.so` (source `native/alooper/wl_alooper.cpp`) supplies the missing
NDK C entry points. These are **not stubs**: AOSP's real `libandroid.so`
implements `ALooper_*` as a thin C wrapper over `android::Looper` in `libutils`,
and this board *has* that `libutils` — it exports 86 `android::Looper` symbols
(`prepare`, `pollOnce`, `getForThread`, `addFd`, `removeFd`) plus
`RefBase::incStrong/decStrong`. So the shim forwards each C entry point to the
very `Looper` object the Java `MessageQueue` drives, which is what makes the fd
callbacks actually fire rather than merely satisfying the linker.

Two ABI details are load-bearing and documented in the source:

- **`sp<Looper>` must have a non-trivial destructor.** `Looper::prepare()` and
  `getForThread()` return `sp<Looper>` (a refcounted smart pointer). On AArch64 a
  non-trivially-destructible return type is returned *indirectly* through `x8`; a
  trivially-copyable stand-in would silently pick the wrong ABI and read the
  result from `x0`. The stand-in `sp<T>` therefore declares a real destructor.
- **The forwards are by mangled name**, verified against `libutils.so`'s dynamic
  symbol table (`_ZN7android6Looper7prepareEi`, `_ZN7android6Looper8pollOnceEiPiS1_PPv`,
  `_ZN7android6Looper12getForThreadEv`, …). The C++ class declarations in the
  shim exist only to make the compiler emit those exact mangled names and the
  correct calling convention; they define no Looper behaviour.

### Delivery / injection route

The correct way to put `ALooper_*` into a consumer's lookup scope is a
**`DT_NEEDED`**, not `LD_PRELOAD` or `Runtime.nativeLoad`. Recorded because both
simpler routes were tried and failed:

- `appspawn_x.cfg` `LD_PRELOAD` — appspawn-x is a musl binary; preloading a
  bionic-linked library there stopped it starting at all.
- `Runtime.nativeLoad()` with the app's ClassLoader — loads (measured ~4 s), but
  the loader maps it `RTLD_LOCAL`, so its symbols never join the global lookup
  scope and the consumer's relocations still fail.

`native/alooper/patch_libandroid.py` adds a `DT_NEEDED` on the shim to the
adapter's `libandroid.so` without growing the file: it rewrites the redundant
`DT_FLAGS` entry (its `DF_BIND_NOW` is already expressed by `DT_FLAGS_1`'s
`DF_1_NOW`) into the new `DT_NEEDED`, and names the shim after a tail of a string
already in `.dynstr` (`"libbionic_compat.so"` contains a NUL-terminated
`"compat.so"` at +10) — hence the shim is deployed as `compat.so`. Zero byte
growth, no section moves.

> Namespace caveat (measured): even via `DT_NEEDED`, a shim reached only as a
> *dependency of a globally-preloaded* `libandroid.so` loads `RTLD_LOCAL`, so its
> symbols are not promoted into a consumer's namespace on this musl. The reliable
> route for a specific consumer is to make the shim that consumer's **own**
> first-order `DT_NEEDED`. This bounded the cronet effort and is the reason the
> anti-abuse-SDK signing route was ultimately out of scope (see
> `WHITEBOARD_BRIDGE.md` §2.53–§2.58).

---

## 5. Trust store: PEM bundle, not PKCS12

The board's 133 CA roots are baked on the host into a single concatenated PEM
bundle (`wl-cacerts.pem`) and read once with `CertificateFactory.generateCertificates`.
This replaced an earlier PKCS12 KeyStore whose iterated-MAC integrity check was
measured at **81 % of the entire TLS install** (3066 ms of 3796 ms on a desktop
JVM; the app waits on that install). One `CertificateFactory` pass over the plain
bundle costs ~30 ms and skips the MAC, the 133 separate file opens and the
KeyStore layer entirely. The bundle is regenerated by `TlsBootstrap bake`.

An offline experiment (`research/trust-prune/`) showed the parse is linear in
anchor count (133 → ~5 ms/cert) and every production host currently chains to a
single root (DigiCert Global Root G2), so a pruned bundle could remove ~800 ms
more — but it is **not shipped**: the okhttp `init(null)` path failed to validate
with a pruned set on the host harness, and that behaviour diverges host-vs-board,
so pruning needs on-board confirmation before it is safe. The 133-anchor bundle
is the shipped default.

---

## 6. R8 shrink of the TLS jar

`wl-tls.jar` carries all ~5400 BouncyCastle classes; under `-Xint`, constructing
`BouncyCastleProvider` eagerly loads 94 algorithm-family `$Mappings` and every
family they register, costing seconds of cold-start class loading. `wl-tls-min.jar`
is the R8-shrunk build: **5397 → 1733 classes, 2.16 MB → 653 KB**, with eager
`$Mappings` cut 94 → 17 and, under `-Xint`, the class-loading install phases
reduced ~1737 ms → ~510 ms (3.4×).

Two rules are correctness-critical and enforced by the build:

- **`-dontobfuscate`.** BC's JCA provider registers every SPI by *string name*
  (`addAlgorithm("Cipher.AES", PREFIX + "$ECB")`), which R8 cannot see through;
  renaming a class breaks the lookup. The output does not work without this.
- **A structural gate** (`scripts/check_tls_min.py`) reads the string constants
  out of every surviving `$Mappings` and checks each advertised class against the
  jar, so an over-shrink that would fail silently at first algorithm use fails the
  build instead. Complemented by a live six-host handshake self-test before dexing.

Post-quantum (`pqc`, 975 classes), OER (315) and the unused symmetric/digest/
asymmetric families are removed at source via `scripts/patch_bc_provider.py`
(empties `loadPQCKeys()`, trims the algorithm-name arrays) because their
references are static and cannot be expressed as R8 keep rules.

Reaching the aspirational <600-class / <5 s target is **not** achievable by
further BC shrinking: a live handshake executes ~909 BC classes (measured floor),
so that target requires a native provider (BoringSSL), which is the blueprint's
Phase 2, not more R8.

---

## 7. Non-anonymous channel degradation

Anti-abuse-gated channels (hot-list and other non-recommended channels) require a
server-issued `device_id` and/or the MSSDK `X-Gorgon`/`X-Argus` signature, both of
which are anchored in ByteDance's anti-abuse machinery and are out of scope for
the compat layer (see `WHITEBOARD_BRIDGE.md` §2.53–§2.58 for the evidence). Rather
than leave those channels in a broken UI state, the adapter maps their failure to
the app's own graceful "network error / retry" path — see
`docs/DEGRADATION.md` and `amr/src/adapter/net/WlDegrade.java`.

## 8. Musl syscall / environment compatibility — the industrial method

Everything above shares one root cause: the app is a bionic/AOSP binary running on
OpenHarmony's **musl** libc and dynamic linker, where the libraries, providers and
kernel ABIs it assumes are subtly or wholly different. This section consolidates
the musl-specific adaptations into the repeatable method used across the stack, so
the pattern — not just the individual fixes — is what gets industrialised.

### The decision rule

For every missing platform facility, the choice was made explicitly by this rule:

1. **A real backend exists on the board → forward to it.** Don't stub what can be
   made real; a stub that merely satisfies the linker produces silent
   misbehaviour later.
2. **No backend exists, and the caller has a documented degrade path → fail
   deliberately down that path.** Make the failure the *designed* one, visible in
   the log, not an accidental crash.
3. **Placement is a linker problem, not a code problem → solve it in the ELF.**
   Symbol visibility and namespace membership are decided by `DT_NEEDED`, scope
   flags and namespaces, and must be reasoned about as such.

### Applied across the stack

| Missing facility | musl reality | Adaptation | Rule |
|---|---|---|---|
| JSSE/conscrypt provider | absent | install BouncyCastle as a real JCA provider (§1) | 1 |
| DRBG entropy bootstrap | probes glibc/conscrypt providers that don't exist | read `/dev/urandom` directly via a BC `EntropySourceProvider` (§3) | 1 |
| `ALooper_*` NDK symbols | `libandroid.so` lacks them | forward to `android::Looper` in the board's `libutils` (§4) | 1 |
| `ashmem` (CursorWindow, blob FD) | no `/dev/ashmem`, no ioctl | grow the window with `malloc`; `nativeExecuteForBlobFileDescriptor` throws `IOException` | 2 |
| server-issued `device_id` / MSSDK signature | anti-abuse SDK crashes under the emulated env | classify + degrade the dependent channels gracefully (§7) | 2 |
| symbol/namespace placement | musl linker, `sealed.child`, `RTLD_LOCAL` | `DT_NEEDED` injection; consumer's own first-order dependency | 3 |

### Rule 3 in detail — the musl dynamic linker

The musl linker on this board behaves differently from bionic/glibc in three ways
that dictated every native-injection decision:

- **Per-app namespaces.** Libraries load into namespaces such as
  `westlake.anl.app.<pid>.1` and a `westlake.sealed.child`; a library reached from
  one namespace is not automatically visible to another. Injecting a shim into the
  process is necessary but not sufficient — it must land in the *consumer's*
  namespace.
- **`RTLD_LOCAL` for dependencies of a global preload.** When the adapter preloads
  `libandroid.so` "by soname, global", `libandroid`'s own symbols become global,
  but a library it pulls in via `DT_NEEDED` loads with local scope — its symbols
  do **not** join the global lookup. Measured directly: a shim reached only as a
  dependency of the preloaded `libandroid` was invisible to the consumer.
  Consequence: to serve a specific consumer, make the shim **that consumer's own
  first-order `DT_NEEDED`**, so it is in the consumer's dependency closure.
- **No room to grow a prebuilt `.so` in place.** Adding a `DT_NEEDED` or a string
  to a stripped system library without moving sections requires reuse:
  `patch_libandroid.py` rewrites the redundant `DT_FLAGS` entry (its `DF_BIND_NOW`
  is already carried by `DT_FLAGS_1`) into the new `DT_NEEDED`, and names the shim
  after a NUL-terminated tail of a string already present in `.dynstr`
  (`"libbionic_compat.so"` → `"compat.so"`), or converts a spare trailing
  `DT_NULL` slot. Zero byte growth, no section moves — reproducible and reversible.

### Why this is the industrial form

Each adaptation is (a) driven by a measured failure, not a guess; (b) chosen by
the rule above rather than ad hoc; (c) reversible (every on-board change backs up
the original and can be restored to stock); and (d) verified — the TLS stack by a
live six-host handshake + structural gate, the ALooper forwards by matching
`libutils`' dynamic symbol table, the degradation path by a real-traffic test
suite. That combination — measured cause, ruled choice, reversibility, verification
— is what makes these musl-compat fixes shippable rather than experimental.
