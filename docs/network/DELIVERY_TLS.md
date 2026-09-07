# TLS stack + ALooper shim — delivery package

The pure-Java network protocol stack and the ALooper native shim, packaged for
intake into the official build pipeline (S3). Sources build byte-for-byte;
prebuilt reference artifacts are included for verification. Architecture and
rationale: `docs/NETWORK_ARCHITECTURE.md`.

Build the package: `bash scripts/package_tls.sh` → `out/wl-tls-delivery-<date>.tar.gz`.

## Artifacts

### 1. Pure-Java TLS stack → `wl-tls-min.jar` (653 KB)

R8-shrunk BouncyCastle (bcprov + bcutil + bctls + BCJSSE) plus the `westlake.tls`
glue, installed as a real JCA provider. Replaces the absent conscrypt/JSSE.

| Input | Role |
|---|---|
| `src/westlake/tls/*.java` (9 files) | the provider glue — see table below |
| `scripts/build_tls.sh` | full (unshrunk) build → `wl-tls.jar` |
| `scripts/build_tls_min.sh` | R8 shrink → `wl-tls-min.jar` (verified: 5397→1733 classes) |
| `scripts/tls-shrink.pro` | R8 rules (`-dontobfuscate` is correctness-critical) |
| `scripts/patch_bc_provider.py` | empties `loadPQCKeys()`, trims algorithm arrays |
| `scripts/patch_bc_curves.py` | trims `CustomNamedCurves` to the curves TLS uses |
| `scripts/check_tls_min.py` | structural gate: every advertised algorithm resolves |
| `out/wl-cacerts.pem` | 133-anchor trust bundle (PEM, not PKCS12 — §5 of arch doc) |

`westlake.tls` classes:

| Class | Role |
|---|---|
| `TlsBootstrap` | installs the providers, warmup, trust bundle, phase timing, `bake` |
| `WlProvider` | front-end `WLTLS` provider (SSLContext.TLS, TrustManagerFactory.PKIX) |
| `WlSslContextSpi` | substitutes the absent-conscrypt `RootTrustManager` |
| `WlTrustManagerFactorySpi` | answers `init((KeyStore) null)` from memory |
| `UrandomEntropySourceProvider` | fixes `DRBG$Default` bootstrap via `/dev/urandom` |
| `WlProvider`/`WlSSLSocketFactory`/`WlTapSocketFactory` | factory wiring |
| `WlTapSocket` / `H2Observer` | diagnostic tap (debug-marker gated; not in the hot path) |

Build requires a JDK 11+ and `d8`/`r8` (Android command-line tools). The shrink
build runs a **live six-host handshake self-test** and the structural gate before
dexing; a broken shrink fails the build rather than shipping.

### 2. ALooper native shim → `libwlalooper.so` (deployed as `compat.so`)

| Input | Role |
|---|---|
| `native/alooper/wl_alooper.cpp` | the shim source (forwards to `android::Looper` in libutils) |
| `native/alooper/build.sh` | cross-compile (NDK r23b, `SONAME=…`) |
| `native/alooper/patch_libandroid.py` | inject a `DT_NEEDED` on the shim into `libandroid.so` |
| `out/libwlalooper.so` | reference binary (ALooper-only) |

Provides the NDK `ALooper_*` C entry points the board's `libandroid.so` lacks, by
forwarding to the `android::Looper` the Java `MessageQueue` already drives. This
is what lets `libsscronet.so` (cronet) relocate and load. See §4 of the
architecture doc for the ABI notes (the `sp<Looper>` non-trivial-destructor
requirement) and the `DT_NEEDED` injection rationale.

> The current `wl_alooper.cpp` also carries `ALooper_pollOnce/forThread` and
> `ASensor*` stubs added during the anti-abuse-SDK investigation (abandoned —
> §2.53–§2.58). They are harmless supersets for the ALooper/cronet purpose; the
> delivered contract of this shim is the `ALooper_*` family.

### 3. Adapter-layer graceful degradation → `amr/src/adapter/net/`

| Input | Role |
|---|---|
| `WlH2Path.java` | stateful HPACK decoder — reads `:path` from live outbound h2 HEADERS |
| `WlDegrade.java` | policy + fallback content for non-anonymous channels |
| `amr/test/adapter/net/*.java` + `real_paths.txt` + `h2_vectors.tsv` | test suite + real-traffic vectors |

Classifies device-credential-dependent channels (hot-list etc.) and supplies a
friendly fallback so their UI degrades gracefully instead of showing a blank
screen. Decision pipeline is complete and validated (HPACK decode 38/38 vs
oracle, 37/37 real requests safe, 1/1 hot-list degraded); live fallback delivery
is gated off pending on-board validation. Full design + enablement runbook:
`docs/DEGRADATION.md`.

## Verification

Every build of `wl-tls-min.jar` self-verifies before dexing:

```
classes: 5397 staged -> 1733 after r8
structural check: 294 advertised, 250 present, 44 removed on purpose  -> OK
live handshake: 6/6 probes MATCH, handshake=OK, okhttpPath=OK
```

The delivered artifacts were produced by exactly these scripts. `SHA256SUMS` in
the package pins every file.

## Reproduce

```sh
export JAVA_HOME=/path/to/jdk        # 11+
export D8=$(command -v d8) R8=$(command -v r8)
bash scripts/build_tls_min.sh        # -> out/wl-tls-min.jar (+ self-test)
NDK=/path/to/ndk-r23b SONAME=compat.so OUT=/tmp/compat.so bash native/alooper/build.sh
```
