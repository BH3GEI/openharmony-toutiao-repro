# Network protocol stack — closeout & asset seal

Formal closeout of the network protocol-stack effort: on-board audit of S3's
unified build, integrity seal of the delivery archive, and the phase-closure
record. Companion to `NETWORK_ARCHITECTURE.md` (design) and `DELIVERY_TLS.md` /
`DEGRADATION.md` (delivery + degradation).

## 1. Official unified-build audit (on-board, read-only)

S3's unified `oh-adapter-runtime.jar` is deployed on the board and observed
read-only (no push, no restart by this side).

**Deployment — two builds observed this session; the module state DIFFERS.**
S3 is actively iterating: the live jar changed mid-audit.

- Build **`069a55dc…c9cd`** (observed first, both framework paths): its
  `classes.dex` **contained all four** net descriptors —
  `Ladapter/net/WlDegrade;`, `…$Kind;`, `Ladapter/net/WlH2Path;`, `…$Entry;`. In
  that build the degradation module was compiled into the official unified jar.
- Build **`9ba3ca0d…58eff`** (the CURRENT live jar, single `classes.dex`,
  superseded `069a55dc` during this audit): it contains `adapter/activity` (41
  descriptors), `adapter/core`, `adapter/packagemanager`, `adapter/window` — but
  **zero `adapter/net/` descriptors**. `WlDegrade` / `WlH2Path` are **NOT** in the
  current unified build.

> **AUDIT FINDING (action required by S3).** Task 1's confirmation — "the
> degradation module is in effect in the official unified build" — **cannot be
> affirmed for the current live build `9ba3ca0d`**: the module regressed out of
> it. It was present in the earlier `069a55dc`, so this is an integration
> inconsistency in S3's build, not a defect in the delivered asset (the archive's
> `adapter/net/*` sources are intact and pass 5/5 tests, §2). S3 must re-include
> `adapter/net/{WlDegrade,WlH2Path}.java` in the unified build (the sources +
> `net.bp` + `INTEGRATION.md` in the delivery archive make this turn-key), after
> which the on-board confirmation can be re-run.

**Runtime init observed** (child `adapter_child_14965.stderr`):
```
[WL-TLS] hijacked 'TlsShim' provider: 2 service(s) re-pointed at the gate
[WL-TLS] using prebuilt trust bundle wl-cacerts.pem (133 anchors)
[WL-TLS] phases: crypto=270ms jsse=11ms truststore=1118ms trustmgr=71ms context=4067ms
[WL-TLS] ok providers=WLBC/BCJSSE roots=133 trust=bundle ms=5554
[WL-TLS] install: ok ... loadMs=5687
[WL-AMR] provider-aware IActivityManager active
[SQLiteConnection] registered 27/31 framework-declared natives
[WL-SQLITE] JNI_OnLoad: CursorWindow=OK SQLiteConnection=OK
```
The TLS gateway hijack, provider install (`WLBC/BCJSSE`, 133 anchors), SQLite JNI
and AMR routing all come up under the unified build.

**Cross-app generality (notable):** the observed run is `com.tencent.mm`
(WeChat), not Toutiao — `[WL-SQLITE] load …/com.tencent.mm/…/libwlsqlite.so OK`.
The same network stack initialises for a second, unrelated app, confirming these
are app-agnostic platform assets, not a Toutiao-specific patch.

**Degradation enforcement state:** the `/data/local/tmp/wl-degrade` marker is
absent and there is zero `WL-DEGRADE` runtime activity — i.e. the module is
present and loadable but enforcement is **OFF by design** (the documented safety
posture; enforcement is opt-in and pending on-board validation, `DEGRADATION.md`).

**One observation to carry forward:** in this run the TLS *warmup* handshakes
failed with `SecurityException: Permission denied (missing INTERNET permission?)`
— logged as harmless, and provider install still succeeded. Warmup is best-effort;
this is a per-run sandbox-permission condition, not a stack defect, but it means
the warmup latency benefit did not apply on this particular launch. Worth a glance
when the app process is granted INTERNET in the unified sandbox.

## 2. Delivery archive integrity (sealed)

`out/wl-tls-delivery-20260906.tar.gz` — SHA-256 `65a0990d…a9c3`, 867 209 bytes.

- **36 files, `SHA256SUMS` ALL VERIFIED.**
- Key artifacts present: `wl-tls-min.jar` (652 978 B ≈ 653 KB), `libwlalooper.so`,
  `wl-cacerts.pem`, `adapter/net/{WlDegrade,WlH2Path}.java`, `INTEGRATION.md`,
  `net.bp`, and the docs.
- **5/5 automated tests pass** when compiled and run from inside the extracted
  archive:
  `WlDegradeTest` · `WlH2PathTest` (HPACK 38/38) · `RealPathCheck` (37/37 safe) ·
  `PipelineTest` (benign safe, hot-list degraded, decode exact) · `RobustnessTest`
  (no exception escapes any simulated-disconnect/malformed case).
- `NETWORK_ARCHITECTURE.md` archives all three required industrial write-ups:
  TLSv1.3 **Warmup** (§2), **entropy source reconstruction** (§3), **Musl syscall
  compatibility** industrial method (§8).

## 3. Phase closure

The network protocol-stack **assets are sealed and verified** (§2), and the
architecture/design work is complete. Phase closure is, however, **held on one
integration item**: the current official unified build (`9ba3ca0d`) does not
contain the degradation module (§1), so the "module in effect in the unified
build" confirmation cannot be signed off until S3 re-includes it. Everything else
is delivered and verified:

| Asset | Where | Verified |
|---|---|---|
| Pure-Java TLS stack (R8-min) | `wl-tls-min.jar` (653 KB) | live install `ok providers=WLBC/BCJSSE`; structural + 6-host handshake gate |
| Trust bundle | `wl-cacerts.pem` (133 anchors) | loaded on-board |
| ALooper shim | `libwlalooper.so` / `wl_alooper.cpp` | forwards to `android::Looper`, symbol-matched |
| Degradation module | `adapter/net/{WlDegrade,WlH2Path}` | asset intact, 5/5 tests, real-traffic-safe; **present in build `069a55dc` but MISSING from current live `9ba3ca0d` — S3 re-include required (§1)** |
| Integration kit | `INTEGRATION.md` + `net.bp` | dexes into real adapter jar, no conflict |
| Architecture white paper | `NETWORK_ARCHITECTURE.md` | Warmup + entropy + Musl compat archived |
| Delivery archive | `wl-tls-delivery-20260906.tar.gz` | 36 files, checksums verified |

**Open items handed off (not network-stack blockers):**
- Degradation enforcement activation (opt-in, board-gated) — turn-key via the
  `wl-degrade` marker once a UI-rendering board and two hot-list captures are
  available (`DEGRADATION.md`).
- Warmup INTERNET-permission condition in the unified sandbox (§1) — a sandbox
  grant question for the platform, observed harmless.
- Anti-abuse-gated channels (hot-list) remain out of scope by decision
  (`WHITEBOARD_BRIDGE.md` §2.53–§2.58); the recommended feed works anonymously.

With the assets sealed and the unified build audited, the network protocol stack
transitions from active development to the overall architecture asset archive.
