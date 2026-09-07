# Non-anonymous channel graceful degradation

## Goal

The recommended feed works (anonymous cold-start). The hot-list and other
non-recommended channels do not: they require a server-issued `device_id` and/or
the MSSDK `X-Gorgon`/`X-Argus` signature, which are unobtainable on this compat
layer (the signer crashes intrinsically; `device_id` is server-assigned and
validated — `WHITEBOARD_BRIDGE.md` §2.53–§2.58). This feature keeps those channels
from presenting a blank / indefinitely-loading screen, degrading them instead to a
defined, friendly outcome: an empty-but-valid result, or the app's own
"network error, retry" state.

## Design

The module has two parts, both pure logic (no network I/O, no live-stream
manipulation, so neither can itself destabilise a connection):

- **`amr/src/adapter/net/WlH2Path.java`** — a stateful HPACK decoder that reads
  the request pseudo-headers (`:path`, `:method`, `:authority`) from an outbound
  HTTP/2 HEADERS block. One instance per h2 connection, fed each outbound HEADERS
  block in order so the dynamic table stays in sync. Tables generated from RFC
  7541. This is what lets the classifier operate on live wire frames rather than
  pre-decoded paths.
- **`amr/src/adapter/net/WlDegrade.java`** — the policy + content core. It answers
  three questions and supplies the fallback body:

| API | Purpose |
|---|---|
| `enforcementEnabled()` | master switch — the `/data/local/tmp/wl-degrade` marker file, so enforcement toggles without a rebuild (default off) |
| `isDeviceRegistered()` | reads `applog_stats` SP for a valid `device_id`+`install_id` (the `NetUtil.isBadId` contract). When true the whole module is inert |
| `shouldDegrade(host, path)` | true only when unregistered AND the path is a positively-identified non-anonymous channel AND not the recommended feed |
| `fallbackJson(kind)` / `http11Response(kind)` | the friendly body / a complete HTTP/1.1 response |

### Two safety invariants

1. **Registration gate.** Degradation only ever applies while the device is
   unregistered. The instant a real `device_register` succeeds, `WlDegrade`
   becomes a no-op and every endpoint behaves normally — so shipping this cannot
   mask a future real fix.
2. **Allow-list, not host-block.** The recommended feed and the hot-list share
   `api.toutiaoapi.com`, so blocking by host would break the working feed.
   Classification is by path: `isRecommendedFeed()` (query_category=__all__) is
   explicitly exempt, and an unknown path fails open (behaves normally). A
   misclassification can therefore only *under*-degrade; it can never break a
   working path.

## Delivery / enforcement hook

`WlDegrade` decides and supplies content; something must consult it and deliver
the fallback. The app negotiates HTTP/2 for these hosts, so the primary delivery
is a synthesised single-stream h2 response built from `fallbackJson`:

```
outbound HEADERS (client) ──▶ classify path via WlDegrade.shouldDegrade()
   if degrade:  do NOT forward the stream to the server; instead play back
                SETTINGS-ack + HEADERS(:status 200, content-type) + DATA(json) + END_STREAM
   else:        pass through untouched   (this is the default today)
```

The seam is the adapter's TLS layer (`WlSslContextSpi` / the socket it hands the
app), the same place the trust-manager substitution already lives.

**Enforcement is gated off by default** (the `wl-degrade` marker is absent) and
the h2 response-playback builder is intentionally **not shipped enabled**, for two
honest reasons:

- Synthesising h2 frames (HPACK, flow control, stream lifecycle) into a live
  connection is fragile, and an error there could disturb the *working*
  recommended feed on the shared host. It must be validated on a stable board
  before it is turned on.
- Two facts needed to make the fallback correct are not yet captured (below), so
  enabling now would be guessing.

Until then the module ships as tested policy + content, and the fast, safe subset
that can be enabled first is **fail-fast mapping**: for a matched non-anonymous
request when unregistered, close the stream cleanly so okhttp/TTNet surface a
normal `IOException` and the app renders its built-in retry UI — no h2 synthesis,
no risk to other streams. This is the recommended first enablement step once the
board is stable.

## Unknowns to close on a stable board

Both require driving the app to the hot-list tab and capturing one real
request/response (the runs to date never issued a hot-list request, and the
current board UI renders blank, so neither could be captured):

1. **Exact hot-list request path/params.** `isNonAnonymousChannel()` currently
   matches the documented ByteDance hot-board markers plus "any non-`__all__`
   feed channel". Reconcile against the real captured `:path` before enabling, so
   the classifier is exact rather than heuristic.
2. **Exact response schema the channel's parser accepts.** `fallbackJson(FEED_LIST)`
   uses the fields common to the TT feed envelope (`data[]`, `has_more`,
   `base_resp`, `tips.display_info`, `now`). Confirm against a real success
   response so the app renders the empty state instead of discarding it.

## Validation on real traffic

Three levels, all green:

1. **Unit** (`WlDegradeTest`, 11/11): the policy on synthetic paths.
2. **Path corpus** (`RealPathCheck`, `real_paths.txt`): the classifier over the
   **68 distinct request paths captured from the board** — **0 of 68 degraded**.
3. **End-to-end pipeline** (`PipelineTest`, `h2_vectors.tsv`): raw HPACK HEADERS →
   `WlH2Path` decode → `WlDegrade` decision, over **37 real captured outbound
   HEADERS blocks + 1 synthetic hot-list request**:
   - HPACK decode matches the Python `hpack` oracle **38/38** (including
     dynamic-table-dependent later blocks);
   - **37/37 real benign requests → not degraded** (safe);
   - **1/1 hot-list request → degraded** (correct).

This confirms the "can only under-degrade, never break a working path" invariant
on real wire frames, and that a genuine hot-list request is correctly classified.

## Status

- Classifier (HPACK decode + policy + content): **implemented and validated
  end-to-end** — `WlH2Path` decode 38/38 vs oracle, 37/37 real benign not
  degraded, 1/1 hot-list degraded, plus 11/11 policy unit tests.
- Enforcement wiring: **specified, seam identified, gated off** pending on-board
  validation and the two captures above.
- Recommended first step when a stable board is available: enable fail-fast
  mapping behind the `wl-degrade` marker, capture the hot-list request/response,
  then (optionally) switch to h2 response-playback with the confirmed schema.
