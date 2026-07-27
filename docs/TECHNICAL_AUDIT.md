# PhoneCam — Technical audit

**Date:** 2026-07-27
**Verdict:** **NOT READY FOR PUBLIC BETA**
**Test rig:** Galaxy S23 Ultra (SM-S918B, Android 16 / SDK 36) + Windows PC with RTX 3070, USB (`adb reverse`)

## How to read this document

Everything marked *measured* comes from real runs on the rig above. Everything
not tested is named as such and is **not** scored as though it had been. In
particular the following were **not** exercised: crash testing, device rotation
in all four orientations, Wi-Fi, slow/lossy networks, mid-stream reconnection,
memory-leak behaviour beyond 5 minutes, and **any device other than the S23
Ultra**.

Latency figures labelled "pipeline" are the sum of encoder + network + decode +
convert + sink. They are **not** glass-to-glass: sensor exposure, GL render, OBS
compositing and display presentation are excluded. Real perceived latency is
higher and has not been measured.

---

## 1. Architecture

```
MainActivity ──picks backend──┬─ CameraX (fallback) ─┐
                              └─ Camera2 (dev flag) ─┤
                                                     ├─► EncoderSurfaceRenderer (GL / OES texture)
                                                     │      └─► MediaCodec H.264 / HEVC (Surface input)
                                                     │             └─► StreamConnection (TCP, length-prefixed)
                                                     ▼
   PC: receiver.py ─► H264Decoder (NVDEC h264_cuvid / hevc_cuvid)
                        └─► _SinkWriter (single-slot mailbox)
                              └─► VirtualCamSink (NV12) ─► OBS Virtual Camera ─► OBS
```

Auxiliary PC services: `control_server` (TCP 8790), `discovery_server` (UDP
8789), `speed_test_server` (TCP 8788), `receiver` (TCP 8787). All four bind
`0.0.0.0`.

**Size:** 6,725 lines Kotlin (+533 debug-only), 1,950 lines Python receiver, 675
lines tests + reward engine. `MainActivity.kt` alone is **1,750 lines** — 26 % of
the Android code in one file.

### Single points of failure

| # | Component | Evidence |
|---|---|---|
| 1 | **OBS Virtual Camera** — the only real sink | No output at all without it |
| 2 | **NVDEC** | Software decode path **unmeasured at 4K**; code documents it could not sustain 4K60 |
| 3 | **Samsung vendor tag** `samsung.android.scaler.availableVideoConfigurations` | 4K60 and 8K30 depend on it entirely; without it the ceiling is 30 fps |
| 4 | **Single TCP connection**, no camera-session reconnect | Observed twice: app backgrounded → session ends |
| 5 | **Duplicated `control_server`** (venv + system Python) | Each spawns its own receiver; both contend for the virtual camera. Observed twice |

### Duplication and technical debt

- **Two parallel capability-detection systems** answering the same question
  differently: `DeviceCapabilities` (hardcoded model database, drives the
  Settings UI) vs `Camera2Capabilities` (vendor table, drives the pipeline). The
  UI promises what the database says; the pipeline delivers what the vendor
  table says.
- Preview and rotation logic duplicated across both capture backends.
- `H264Encoder` / `H264Decoder` now handle HEVC too — names are misleading.
- `TIER_CEILINGS["4k60"]` now caps at 7680×4320.
- `Camera2ProbeActivity` is throwaway diagnostic code in `src/debug`.
- Only 1 TODO marker in the whole repo — debt is real but simply not annotated.

---

## 2. Performance matrix — measured, 45 s per mode

| Mode | Backend | Real camera output | capture | encode | shown | Pipeline lat. | Bitrate | AP °C | PC CPU | PC RAM |
|---|---|---|---|---|---|---|---|---|---|---|
| 1080p30 | camera2 | 1920×1080 | 29.9 | 29.9 | 30.0 | 5.9 ms | 45.0 | 44→45 | 6.1 % | 204 MB |
| 1080p60 | camera2 | 1920×1080 | 59.8 | 59.8 | 59.5 | 5.0 ms | 43.0 | 44→48 | 10.1 % | 204 MB |
| 1440p30 | camera2 | 2560×1440 | 29.9 | 29.9 | 30.0 | 6.7 ms | 43.6 | 44→46 | 8.0 % | 232 MB |
| **1440p60** | **camerax** | **2160×1216** | **0.0** | **29.9** | 30.0 | 6.0 ms | 45.2 | 46→49 | 8.9 % | 232 MB |
| 4K30 | camera2 | 3840×2160 | 29.9 | 29.9 | 30.0 | 10.6 ms | 46.0 | 46→48 | 16.1 % | 298 MB |
| 4K60 | camera2 | 3840×2160 | 59.7 | 59.8 | 59.5 | 9.4 ms | 44.6 | 47→53 | 33.1 % | 298 MB |
| 8K30 | camera2 | 7680×4320 | 29.9 | 29.9 | 30.0 | **31.8 ms** | 48.6 | 47→51 | **72.6 %** | **685 MB** |

**Zero dropped frames on all five counters, in all seven modes.**

Two findings from this table:

- **1440p60 is the only mode that falls back to CameraX** (CameraX has no 1440p
  quality bucket) and delivers **2160×1216 upscaled to 2560×1440 at 30 fps**. The
  user selects 1440p60 and receives less than 1440p at half the frame rate, with
  no warning.
- **`send fps` is a misleading metric.** At 4K30 it reads 21.0 against encode
  29.9. Nothing is lost — it counts network *write batches*, not frames, which
  `shown=30.0` confirms.

### 5-minute soaks

**4K60** (720p viewfinder, 152 windows): capture median 59.7 / avg 59.63 / p5
58.9; shown median 59.5 / avg 59.40; zero drops; thermal LIGHT → MODERATE
(AP 54.3 → 57.5 °C); PC CPU 27.9 → 36.5 %; RAM 297 → 299 MB.

**8K30** (150 windows): capture median 30.0 / avg 30.01 — flawless. But the PC
degrades progressively:

| by thirds of the run | 1st | 2nd | 3rd |
|---|---|---|---|
| shown fps | 29.68 | 29.96 | **26.38** |
| decode (NVDEC) ms | 8.69 | 8.98 | 9.25 |
| convert (CPU) ms | 8.80 | 9.07 | **12.81** |
| sink ms | 5.28 | 5.59 | 6.48 |

PC CPU 51 → 73.8 → **82.2 %**, still climbing at 5 minutes. 20 of 150 windows
below 29 fps, 7 below 20 — **none of which any counter reports**, because the
single-slot mailbox discards silently.

---

## 3. Android camera

**CameraX vs Camera2 — root cause confirmed** by reading the CameraX 1.4.2
sources and by on-device verbose logging:

1. CameraX validates frame rate against `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`
   (max 30 on this device) and never asks the HAL for more. Camera2 passes the
   range as a **session parameter** and gets a measured 59.8 fps.
2. `StreamingVideoOutput` did not implement `getMediaCapabilities()`, whose
   default returns `VideoCapabilities.EMPTY`; `VideoCapture` then logged
   `Can't find any supported quality on the device.` and never inserted
   `OPTION_CUSTOM_ORDERED_RESOLUTIONS`, so the `ResolutionSelector` was ignored.
   Fixed; verified 1080p → 1920×1080 and 2160p → 3840×2160.
3. A remaining filter: the 16:9 `ViewPort` crops the correctly-sized stream to
   2160×1216 — a portrait slice of the landscape sensor frame. Proven by
   disabling it (all cases then deliver 3840×2160).

**Lenses:** four rear lenses resolved by focal-length order (0.6× / 1× / 3× /
10×) plus front. 8K exists only on physical camera id 5; top-level id 56 lists it
but is not enumerable (`openCamera("56")` throws `Unknown camera ID`).

**Zoom, autofocus, tap-to-focus, torch, ISO, shutter, EV, HDR, stabilisation:**
implemented **for CameraX only**. With the Camera2 backend active they are all
inert (`camera` is null). Not a defect — a known limitation of the experimental
backend. **Not functionally re-tested in this audit.**

**Rotation:** Camera2 computes it from `SENSOR_ORIENTATION` + physical
orientation. **Implemented but not verified in all four orientations.**

**Edge case, observed twice:** backgrounding the app revokes the camera
(`ERROR_CAMERA_DISABLED`) and ends the Camera2 session; the app falls back to
CameraX and **keeps streaming at 30 fps without telling the user**.

---

## 4. Streaming

Per-stage latency: see matrix. Bitrate sits at 43–48.6 Mbps in every mode because
that is the configured value — **there is no adaptive bitrate**; the network
modulates nothing.

- **USB (`adb reverse`)**: measured, `net_lat` 0.8–1.4 ms across all modes,
  sustained at 48 Mbps.
- **Wi-Fi**: not tested in this audit.
- **Slow networks**: not tested. With no rate adaptation, the expected behaviour
  is growing backlog; the receiver drops stale frames but the sender never
  reduces quality.
- **Reconnection**: initial connect retries 5 × 2 s and an `auto_reconnect`
  preference exists. **Mid-stream reconnection is untested.**

---

## 5. Encoding

H.264 up to 4K, **HEVC automatically above 4K** (`mimeTypeFor`). Encoder latency
1.7–2.7 ms in every mode — never the bottleneck. Verified maxima: 7680×4320@30
(HEVC) and 3840×2160@60 (AVC). Phone CPU/GPU/RAM not measured directly; only
temperature as a proxy.

**Measured bottleneck: none on the phone.** The only system limit is the PC at 8K.

---

## 6. PC receiver

NVDEC confirmed active (`h264_cuvid` / `hevc_cuvid`). Memory scales with
resolution (204 → 685 MB) and grew +2 MB (4K60) / +5 MB (8K30) over 5 minutes —
**no leak evidence at 5 minutes, no data beyond that**.

A GIL defect was found and fixed during this work: `pyvirtualcam.send()` does not
release the GIL, so a 24 ms RGB→NV12 conversion serialised against the decode
loop instead of overlapping. That alone took 4K60 from 3 fps shown to 59.4.

The single-slot mailbox does not deadlock, but it **hides loss** — see the 8K30
figures above.

---

## 7. Security

| Finding | Detail |
|---|---|
| **Four services on `0.0.0.0`, no authentication** | 8787 video, 8788 speed-test, 8789 discovery (UDP), 8790 control |
| **`control_server` exposes `/start`, `/stop`, `/adb-reverse` unauthenticated** | Service list is fixed (not arbitrary RCE), but anyone on the LAN can start/stop services |
| **`Access-Control-Allow-Origin: *`** | A simple POST from **any website the user visits** reaches `http://<their-ip>:8790/start`. Real browser-based CSRF vector |
| **Video in the clear, no TLS, no authentication** | Anyone on the LAN can connect to the receiver |
| **`usesCleartextTraffic="true"`** | Global |
| **`RECORD_AUDIO` requested, audio not implemented** | Sensitive permission with no feature behind it |
| **AdMob ships Google's public test App ID** | Documented in the manifest; must be replaced before release |

---

## 8. Release checklist

### CRITICAL

1. **CORS wildcard on process-control endpoints.**
   *Impact:* a malicious web page starts/stops services on the user's PC.
   *Likelihood:* high whenever the PC is on and the user browses.
   *Repro:* `fetch('http://<ip>:8790/start',{method:'POST',mode:'no-cors'})` from any page.
   *Fix:* drop the wildcard CORS, require a local token, bind to `127.0.0.1` unless explicitly opted in.
2. **Unauthenticated services across the LAN.**
   *Impact:* a third party on the same network connects to the receiver or consumes the stream.
   *Likelihood:* high on shared networks.
   *Repro:* connect to `<ip>:8787` and send a Hello.
   *Fix:* shared token established at pairing + loopback binding by default.
3. **Validated on a single device.**
   *Impact:* the entire fast path depends on a Samsung vendor tag; behaviour on a Pixel is unknown.
   *Likelihood:* certain to surface problems.
   *Fix:* test on ≥3 non-Samsung devices before opening the beta.

### HIGH

4. **1440p60 delivers 2160×1216@30 with no warning.** *Repro:* Settings → 1440p + 60 fps → record → log shows `backend=camerax camera=2160x1216`. *Fix:* route 1440p60 through Camera2 (vendor table supports 1440p at 30) or remove the combination from the UI.
5. **ViewPort crops the whole CameraX path to 2160×1216.** *Fix:* move composition cropping into the GL renderer, which the project already owns.
6. **Camera loss on minimise silently degrades to CameraX.** *Repro:* recents gesture during a stream. *Fix:* retry Camera2 on return to foreground and surface it in the UI.
7. **No adaptive bitrate.** *Impact:* growing backlog on poor Wi-Fi. *Fix:* rate control driven by receiver feedback.

### MEDIUM

8. Camera controls inert under Camera2 (zoom / focus / ISO / EV / torch).
9. Single-slot mailbox hides loss (7 windows below 20 fps at 8K with no counter).
10. `send fps` is misleading.
11. Duplicated PC services from two Python environments.
12. `RECORD_AUDIO` with no feature; AdMob test App ID.
13. Experimental-flag strings are English-only (app is localised to 20 languages).

### LOW

14. `H264Encoder` / `H264Decoder` names and `TIER_CEILINGS["4k60"]` key no longer match reality.
15. `MainActivity.kt` at 1,750 lines.
16. `Camera2ProbeActivity` throwaway code in `src/debug`.
17. `handle_connection` propagates `RuntimeError` if the virtual camera fails during the final flush.

---

## 9. Scores

| Dimension | Score | Basis |
|---|---|---|
| **Beta readiness** | **42 / 100** | Pipeline performs excellently; security and single-device validation are blocking |
| **Stability** | **55 / 100** | 5 clean minutes across 7 modes with zero drops, but no crash testing, no proven reconnection, and demonstrated silent degradation |
| **Performance** | **88 / 100** | Measured and strong: 59.8 fps at 4K, 9.4 ms pipeline, 33 % PC CPU. Only 8K penalises it (72–82 % CPU) |
| **User experience** | **38 / 100** | Controls inert on the fast path, modes that lie (1440p60), no backend indicator, warnings via toast |
| **Maintainability** | **52 / 100** | Excellent comments and 37 green tests on the PC side, but two parallel capability systems, zero Camera2-backend tests, and a 1,750-line file |

## Conclusion — NOT READY FOR PUBLIC BETA

Not for performance reasons: on that axis the project is objectively good, with
genuine end-to-end 4K60 and zero measured loss across seven modes. Three
verified reasons:

1. **Insecure-by-default network surface** — four unauthenticated services on
   `0.0.0.0` and a control endpoint reachable from any web page. The only finding
   that can actively harm the user, hence CRITICAL.
2. **A single validated device**, with the fast path depending on a proprietary
   Samsung tag.
3. **Modes that deliver something other than what they promise** (1440p60, and
   the whole cropped CameraX path), with no communication in the UI.

None of the three requires research; all have known, bounded fixes. With the
CRITICAL items and HIGH 4–6 resolved, plus a test pass on 2–3 non-Samsung
devices, the assessment would reasonably move to **READY FOR BETA WITH
CAVEATS**.
