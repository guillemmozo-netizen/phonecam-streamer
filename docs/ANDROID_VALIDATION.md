# Android hardware validation — 2026-07-30

Executed on a real device. Nothing in this document is inferred, simulated or
argued from code reading: every "verified" line has a log excerpt behind it,
and everything that could not be executed on hardware is listed in §Pending
rather than quietly omitted.

**Device:** Samsung SM-S918B (Galaxy S23 Ultra), Android 16, arm64-v8a
**Transport:** USB, `adb reverse` tunnels on 8787-8790
**Build under test:** debug APK built 15:50, containing the audit fixes
(F8 thread leak, F9 socket timeouts) plus the crash fix found here
**PC side:** receiver on 8787, NVDEC decode, OBS 32.2.1 with Virtual Camera

---

## Headline

**One crash found, root-caused, fixed and re-validated on hardware.** It was a
regression I introduced myself in the previous audit round: the F8 thread-leak
fix made the app crash on every stop of a session with audio.

| | Before fix | After fix |
|---|---|---|
| FATAL EXCEPTION on stop with audio | **every time** | **0 in 6 consecutive cycles** |
| Android unit tests | 105 | **111** |

---

## Bug found: app crashes when stopping a session with audio

**Severity: critical** — every user stopping a recording with audio enabled.

### Procedure

Launch app, tap record, stream 45s at 1080p30 with audio, tap stop.

### Result

```
FATAL EXCEPTION: AudioStreamer
Process: com.phonecam.streamer, PID: 20204
java.util.concurrent.RejectedExecutionException: Task ... rejected from
  ThreadPoolExecutor[Shutting down, pool size = 1, active threads = 1,
  queued tasks = 1, completed tasks = 3468]
	at CameraStreamer.sendAudioPacket(CameraStreamer.kt:652)
	at AudioStreamer.captureLoop(AudioStreamer.kt:156)
	at java.lang.Thread.run(Thread.java:1572)
```

### Root cause

Mine, from the previous round. `CameraStreamer.stop()` did:

```kotlin
networkExecutor.execute { audioStreamer?.stop() }   // queued
... 
networkExecutor.shutdown()                          // immediate
```

The comment I wrote claimed this "joins the capture thread, so no further
audio can be posted". It does not — `execute` only *queues* the stop, while
`shutdown()` runs immediately. The audio capture thread was therefore still
alive, still producing a packet every ~21ms, and handing it to an executor
that had already stopped accepting. `ThreadPoolExecutor.execute` signals that
by throwing, the exception unwound `captureLoop`, and an uncaught exception on
a non-main thread kills the process.

Two independent defects: **ordering** (shutdown before the producer stopped)
and **no tolerance** (nothing treated rejection as the ordinary teardown race
it is).

### Correction

Three layers, because this is a crash:

1. `submitIfAccepting()` — new, in `ExecutorSubmit.kt`. Returns a boolean
   instead of throwing when the executor is shutting down.
2. Shutdown is now **queued last** (`submitIfAccepting(networkExecutor) {
   networkExecutor.shutdown() }`), so it runs after the audio stop has
   completed and joined the capture thread.
3. `AudioStreamer.captureLoop` guards the `send` callback, so nothing the
   socket owner throws can kill capture.

The video path got the same guard: the GL thread also outlives the network
thread at teardown.

### Tests added

`ExecutorSubmitTest` — 6 tests, including the exact "Shutting down" state
named in the crash, 100 consecutive rejections, and one pinning the premise
that raw `execute()` really does throw.

### Post-fix validation on hardware

6 consecutive start/stop cycles, same process throughout:

```
pid inicial: 24790
  ciclo 1: pid=24790 fatales=0
  ciclo 2: pid=24790 fatales=0
  ciclo 3: pid=24790 fatales=0
  ciclo 4: pid=24790 fatales=0
  ciclo 5: pid=24790 fatales=0
  ciclo 6: pid=24790 fatales=0
```

6 audio sessions opened, 6 closed cleanly, no residual `CameraStreamerNet` or
`AudioStreamer` threads — which also validates the original F8 leak fix on
hardware.

---

## Objective 1 — CameraX and Camera2 · **VERIFIED**

### CameraX

```
MainActivity: camera bound: 1080p · 16:9 · lens=1 · streaming=true
CameraStreamer: session: backend=camerax encoder=1920x1080 target=30fps camera=1920x1080
CameraStreamer: metrics[camerax] capture=0,0 frames=30,1 encode=30,1 send=30,1
  | enc_lat=1,8ms net_lat=0,6ms | bitrate=18,0Mbps
  | dropped: throttled=0,no_connection=0,encoder_failed=0,encode_exception=0,send_failed=0
```

30.0 fps sustained, encode latency 1.6–2.9 ms, network latency 0.4–1.0 ms,
**zero drops of any kind**. `capture=0` is by design on this backend — the
capture counter is fed by Camera2's capture callbacks only.

### Camera2

Required enabling `experimental_camera2`; the gate then reported
`eligible=true ... supported=true`.

```
CameraStreamer: session: backend=camera2 encoder=1920x1080 target=30fps camera=1920x1080
CameraStreamer: metrics[camera2] capture=30,0 frames=30,0 encode=30,0 send=30,0
  | enc_lat=2,1ms net_lat=0,9ms | bitrate=17,1Mbps
  | size=1920x1080->1920x1080@[30, 30] | dropped: all zero
```

`capture=30,0` confirms the camera-side callbacks are firing, and
`@[30, 30]` is the fps range negotiated as a session parameter — the whole
point of this backend.

---

## Objectives 2 & 3 — AudioRecord and MediaCodec AAC · **VERIFIED**

The largest previously-unvalidated surface in the project.

```
AudioCapture:  capturing 48000Hz 2ch source=7
AudioEncoder:  AAC-LC encoder: 48000Hz 2ch 192kbps
AudioStreamer: audio session: Format(codec=aac, sampleRate=48000, channels=2, bitrateBps=192000)
```

Stereo at 48 kHz opened on the first attempt — no fallback to mono, no
fallback to PCM. Receiver side:

```
AudioDecoder: aac 48000Hz 2ch
connection closed: ReceiverStats(frames_received=370, frames_decoded_failed=0,
  audio_packets_received=578, audio_frames_decoded=591872, audio_packets_failed=0)
```

**Zero failed audio packets**, so every ADTS frame the phone's MediaCodec
produced was decodable by PyAV on the PC.

### Proving the audio was real, not silence

A first recording came back at peak 12 / RMS 0 — digital silence. Rather than
accept "the room was quiet", the microphone was tested against a known
signal: a 300–2000 Hz sweep played through the PC speakers while recording.

```
12.3s  pico=7028  rms=688
energía en 300-2000Hz (banda del barrido reproducido): 89%
frecuencia dominante: 1029 Hz
```

89% of the energy in exactly the band played back. That closes the loop end to
end: microphone → device AAC encoder → ADTS → USB → PC decoder → audible WAV.

The initial silence was a genuinely quiet room plus `VOICE_COMMUNICATION`'s
noise gating, which suppresses ambient by design. **Not a defect** — but worth
knowing that this source is aggressive, and that a user in a quiet room will
measure near-zero on a level meter.

---

## Objective 4 — Full pipeline phone → USB → PC → OBS · **VERIFIED**

Receiver with `--sink virtualcam`, OBS 32.2.1 running.

```
pipeline: in=30.0fps decoded=30.0fps shown=30.0fps backlog_max=0 sink_send=0.3ms
  backend=h264_cuvid (NVDEC) decode=0.8ms convert=0.8ms audio=48.1kHz skew=+0ms held=0
```

30 fps all the way to the OBS Virtual Camera, `sink_send` 0.0–0.3 ms, zero
backlog, audio at 48 kHz alongside. OBS remained responsive and **produced no
new crash dump** — the newest on the machine is still 2026-07-28 20:24, before
the F15 fix.

Note on F15: `obs_sync` took the "canvas already matches" short circuit
(`OBS canvas already 1920x1080@30fps`), so **the `_active_output` guard itself
was not exercised** in this run. It is unit tested but not yet observed live.

---

## Objective 5 — Reconnection · **VERIFIED**

### Procedure

Kill the receiver process mid-stream, wait 6 s, restart it.

### Result

```
CameraStreamer: send failed, reconnecting
CameraStreamer: connection state: RECONNECTING (host=127.0.0.1:8787)
CameraStreamer: connection state: CONNECTED (host=127.0.0.1:8787)
CameraStreamer: re-sending codec config (32 bytes) to the reconnected receiver
```

then

```
pipeline: in=30.0fps decoded=30.0fps shown=30.0fps backlog_max=0
```

The `re-sending codec config` line is the audit's reconnect fix working in
production: without it the new decoder never receives SPS/PPS and produces no
frames at all, which looks identical to a dead camera.

---

## Objective 8 — Resolution and FPS changes · **VERIFIED**

Changed via SharedPreferences (the APK is debuggable), app restarted between
each, receiver's announced format checked against the request.

| Requested | Encoder | Receiver saw |
|---|---|---|
| 720p30 | 1280x720 @30 | `1280x720@30fps` |
| 1080p60 | 1920x1080 @60 | `1920x1080@60fps` |
| 1440p30 | **1920x1080** @30 (camera 2560x1440) | `1920x1080@30fps` |

Zero fatals across all changes. The 1440p case is correct, not a defect: the
free reward tier caps the encoder at 1080p while the camera still captures
1440p, and the GL renderer scales — exactly what `effectiveTarget` documents.

---

## Objective 9 — Thermal stability · **VERIFIED, with a caveat**

Sustained 8-minute 1080p60 session, sampled once a minute:

```
  min 1: frames=59,9  Thermal Status: 2
  min 2: frames=59,7  Thermal Status: 2
  min 3: frames=59,9  Thermal Status: 2
  min 4: frames=59,7  Thermal Status: 2
  min 5: frames=59,8  Thermal Status: 2
  min 6: frames=59,7  Thermal Status: 2
  min 7: frames=59,8  Thermal Status: 2
  min 8: frames=59,6  Thermal Status: 2
```

**Throttling was active from the first sample and never moved the frame
rate.** Spread across the whole run: 59.6–59.9 fps, i.e. under 0.5%. Every
drop counter stayed at zero:

```
dropped: throttled=0,no_connection=0,encoder_failed=0,encode_exception=0,send_failed=0
```

PC side confirms it end to end: `in=60.0fps decoded=60.0fps shown=60.5fps
backlog_max=1`.

Thermal zones at the end of the run:

| Zone | Temp | Status |
|---|---|---|
| AP (SoC) | **53.9 °C** | 0 |
| PA | 48.6 °C | 0 |
| **SKIN** | **43.1 °C** | **2 — throttling** |
| BAT | 40.5 °C | 0 |
| USB | 37.5 °C | 0 |

`Thermal Status: 2` = MODERATE throttling, and the zone reporting it is
**SKIN at 43.1 °C** — the surface temperature limit, not the SoC. Battery rose
from 36.1 °C to 40.5 °C across the whole validation.

**The caveat: 8 minutes is not a long session.** The device sat at
`Thermal Status: 2` (MODERATE) for the entire run without losing a frame,
which is a genuinely good result — but status 2 throttles lightly. A longer
run could reach status 3 (SEVERE), where the governor cuts clocks hard, and
nothing here says what happens then. **Sessions beyond ~10 minutes at 1080p60
are untested**, and that is exactly the duration a real video call has.

Also worth noting: SKIN at 43.1 °C is the surface the user holds. It is below
the ~48 °C burn threshold but well into "noticeably hot".

## Objective 10 — Battery · **VERIFIED — and it discharges while plugged in**

```
USB powered: true
status: 2
level: 37
current now: -495
```

**`current now` is negative while `USB powered: true`.** At 1080p60 the phone
draws roughly 495 mA more than the USB port supplies, so it discharges *while
connected*. Battery went 39% → 37% during testing.

This matters for a product whose normal use is "plugged into the PC for a long
call": the phone will slowly drain rather than hold charge. Not a defect in
the code, but a real product characteristic worth stating — and a reason a
long meeting on a low battery could end mid-call.

---

## Finding: the phone's `send` metric under-reports · **cosmetic, not fixed**

Observed: `encode=59,9` but `send=51,0` — as if 9 fps were vanishing, with
every drop counter at zero.

**No frames are actually lost.** The PC received 59.5–60.5 fps continuously
throughout. The defect is in the counter: `metrics.onSent()` is called **once
per batch** in `CameraStreamer.onFrameAvailable`, but MediaCodec's drain
pattern routinely returns 2 chunks from one `encode()` call (the codec
pipelines a frame deep — the same behaviour the PC's backlog logic already
accounts for). Two frames sent, one counted.

Not fixed here, deliberately: it is cosmetic, and changing the instrumentation
mid-validation would invalidate the measurements just taken. The fix is to
count per chunk rather than per batch.

---

## Pending — NOT verified on hardware

Stated explicitly, because the instruction was to claim nothing unexecuted.

### Objective 6 — Orientation · **NOT VALIDATED**

Display rotation was forced through all four positions via
`settings put system user_rotation`. The app survived all four (same pid, 30
fps continuous, 0 fatals) — **but no rotation line appeared in the streamer
log at all**, because the app tracks orientation through
`OrientationEventListener` (the physical accelerometer), not the system
rotation setting. So what was tested is "the app survives a display rotation",
not the rotation path itself: `RotationPolicy`, the encoder's rotation
argument and the resulting image orientation in OBS are all **unverified**.

**This needs the phone physically rotated by hand.**

### Objective 7 — Screen lock/unlock · **NOT EXECUTED**

Locking the screen is trivial via adb; unlocking requires entering the device
PIN, which I do not do. Needs a human at the device.

### Other gaps

- **Physical USB disconnect.** `adb kill-server` was used earlier to simulate
  tunnel loss, but that is not the same event as pulling the cable, which cuts
  data and power at once.
- **The `_active_output` guard (F15)** — unit tested, not yet observed
  suppressing a real reset against a live OBS output.
- **Wi-Fi transport.** Everything here went over USB.
- **Front camera, telephoto, ultra-wide.** Only the main back camera at
  `lens=wide` was exercised; the Camera2 backend explicitly excludes the
  others.
- **4K and 8K.** Only 720p/1080p/1440p were tested. The Camera2 backend exists
  primarily for 4K60/8K30, which remain unvalidated.
- **HEVC path.** All sessions negotiated `codec=h264`; the >4K HEVC switch was
  never triggered.
