# Architecture

## Goal

A DroidCam-style "use your Android phone as a PC webcam" app, monetized via
opt-in rewarded ads instead of a forced paywall, differentiated by USB
connection quality/latency rather than by the ad system (see
[REWARD_MODEL.md](REWARD_MODEL.md) for why the monetization design looks the
way it does — that's not the hard or differentiating part of this product).

## Components

```
┌─────────────────────────┐         USB (adb reverse)         ┌──────────────────────────┐
│   Android app (Kotlin)  │   or plain TCP over Wi-Fi LAN      │   PC receiver (Python)   │
│                          │ ───────────────────────────────▶  │                          │
│  CameraX capture         │    length-prefixed H.264 Annex-B   │  TCP server (loopback)   │
│  RewardManager (tier)    │    access units + AAC audio, both  │  H.264 decode (PyAV)     │
│  Watermark overlay       │    timestamped — see PROTOCOL.md   │  AAC decode + A/V sync   │
│  H.264 encode (MediaCodec)│                                   │  pyvirtualcam output ──▶ │ OBS / Zoom / Meet / etc.
│  AudioRecord + AAC encode│                                     │  audio ──▶ virtual cable │ see the phone as a normal
│  TCP client              │                                     │                          │ webcam + microphone
└─────────────────────────┘                                     └──────────────────────────┘
```

Audio shares that one connection rather than opening a second — see
[AUDIO.md](AUDIO.md) for the whole path, including the virtual audio cable
the PC side needs to reach a conferencing app, and what A/V sync costs in
video latency.

The PC-only demo path (`pc_receiver/demo_sender.py`, see below) still speaks
plain JPEG — it stands in for the phone without needing an H.264 encoder on
the PC side, and Hello.codec defaults to "jpeg" for exactly this reason. The
real Android app always sends codec="h264".

Both sides share one design principle: **the reward/quality logic and the
network protocol are pure, tested modules with no UI or hardware
dependencies**, so the economy and the wire format can be verified without a
phone, an emulator, or even a JDK.

## What's real vs. stubbed in this alpha

| Piece | Status |
|---|---|
| Reward/credit engine (`reward_engine/`) | **Real, 15/15 tests passing** in this environment |
| Wire protocol (`pc_receiver/protocol.py`) | **Real, 6/6 tests passing**, plus a live process-to-process smoke test (see below) |
| PC receiver + virtual camera (`pc_receiver/receiver.py`, `sinks.py`) | **Real.** `pyvirtualcam` import succeeds here; actually feeding OBS Virtual Camera needs OBS (or another backend) installed on the machine that runs it — untested on *this* machine since that's a runtime dependency, not a code dependency |
| PC demo sender (`pc_receiver/demo_sender.py`) | **Real and exercised end-to-end** — reads the PC webcam if present, else falls back to a synthetic generated pattern (works headless), speaks plain JPEG (Hello.codec="jpeg") |
| H.264 decode (`pc_receiver/h264_decoder.py`) | **Real**, PyAV/FFmpeg-backed, with its own encode→decode round-trip tests (`pc_receiver/tests/test_h264_decoder.py`) using PyAV to stand in for the phone's encoder |
| Android app (`android-app/`) | **Compiles and its unit tests pass** (`./gradlew compileDebugKotlin testDebugUnitTest`) wherever a JDK + Android SDK are installed. CameraX capture, RewardManager, watermarking, and the H.264 encoder (`H264Encoder.kt`, MediaCodec) are all written and type-check correctly, but this sandbox has no camera hardware or emulator, so the actual on-device camera → MediaCodec → network path hasn't been run for real yet |
| Audio capture/transmission | **Real** — `AudioRecord` capture, MediaCodec AAC-LC (ADTS-framed) with a raw-PCM fallback, multiplexed onto the existing socket as timestamped chunks, decoded on the PC and played into a virtual audio cable. A/V sync and the audio path are verified end to end on a PC alone (`demo_sender --audio`); the on-device capture half needs a real phone, same as video. Every audio setting now does something real — including "wind filter", a 100Hz low-cut whose frequency response is unit tested. Needs VB-CABLE or VoiceMeeter installed to reach a conferencing app, checked at install time — see [AUDIO.md](AUDIO.md) |
| Auth on the socket | Token required for non-loopback senders; see PROTOCOL.md |
| Real AdMob rewarded-ad integration | **Wired up** (`AdMobAdController`) using Google's public test ad unit — real load/show/reward flow, just pointed at test IDs until you register your own AdMob app. See [ADS_SETUP.md](ADS_SETUP.md) |
| GDPR/UMP consent gate | **Wired up** (`ConsentManager`) — ads are only initialized/requested after `canRequestAds()` is true, per Google's UMP contract. The AdMob-console-side consent message content still needs configuring before a public EU release — see ADS_SETUP.md |

## OBS composition sync

With Settings > "Sync OBS settings" on (the default), OBS's canvas is reshaped
to match what the phone actually streams, over obs-websocket v5 (built into
OBS 28+, including 31 and 32; it has to be enabled once under Tools > WebSocket
Server Settings).

**The frame's shape comes from Composition, not from Resolution.** Resolution
decides how big the frame is, Composition decides its shape, and the short edge
is what a preset name refers to. At 1080p:

| Composition | Streamed, and OBS canvas |
|---|---|
| 16:9 | 1920x1080 |
| 4:3 | 1440x1080 |
| 1:1 | 1080x1080 |
| 9:16 | 1080x1920 |
| 3:4 | 1080x1440 |

`StreamConfig.outputSizeFor` is the single source of truth for that, and
`CameraStreamer.effectiveTarget` is what both the encoder and the Settings push
to OBS go through — so the size announced to the PC is always the size that was
encoded, reward-tier ceiling included. The ceiling scales the frame down
uniformly; it never reshapes it.

**Only PhoneCam's own source is ever modified.** The scene item is matched by
*exact* name — `PhoneCam` by default, or whatever `PHONECAM_OBS_SOURCE` is set
to — never by substring. Webcams, capture cards and other streamers' sources
are never touched, and if no source with that exact name is in the current
scene, nothing is modified and the reason is logged with the names that were
found. Name your PhoneCam source `PhoneCam` in OBS for the auto-fit to apply to
it.

**A change that can't be applied yet is kept, not dropped.** Reshaping OBS's
video pipeline while any output is running crashes it, and PhoneCam's own feed
is a virtual-camera output — so a resolution change made mid-session, or while
OBS is closed, is saved to `%LOCALAPPDATA%\PhoneCam\obs_pending.json` and
applied on the next connection or as soon as the output stops. The canvas
change logs both shapes:

```
OBS canvas updated: 1920x1080 -> 1440x1080 @60fps
```

## Proof this actually works today, without a phone

```bash
# terminal 1
.venv/Scripts/python -m pc_receiver.receiver --port 8787 --sink preview

# terminal 2 — simulate a user who already watched a rewarded ad
.venv/Scripts/python -m pc_receiver.demo_sender --port 8787 --simulate-ads 1
```

This was run in this sandbox with `--sink null` (headless) instead of
`preview`, and confirmed:

- Free tier run: receiver correctly reports `1920x1080@60fps quality=1080p60
  watermark=True`, 10/10 frames decoded cleanly.
- Premium run (`--simulate-ads 1`): receiver correctly reports
  `3840x2160@60fps quality=4k60 watermark=False`, 20/20 frames decoded
  cleanly, and the sender's reward balance visibly drained from 3600s
  towards 0 over the run.

That means the full loop — reward state → resolution/watermark/JPEG-quality
selection → encode → length-prefixed TCP framing → decode → sink — is
verified end-to-end, independent of whatever happens on the Android side.

## Next steps, roughly in order of value

1. **Validate the CameraX → MediaCodec (H.264) → TCP path on a real device**
   against the already-working PC receiver over real Wi-Fi first (simplest
   to debug), then over `adb reverse` USB — the encoder (`H264Encoder.kt`)
   compiles and the PC-side decode is tested, but the actual on-device
   camera/encoder round trip has never run outside this sandbox.
2. **Register a real AdMob app/ad unit** and configure the consent message
   in the AdMob console — the SDK integration on both fronts
   (`AdMobAdController`, `ConsentManager`) is done; see ADS_SETUP.md for the
   remaining account-creation and console-configuration steps.
3. **Tune `RewardConfig.seconds_per_ad`** against real usage/retention data
   once there are actual users, per the note in REWARD_MODEL.md.
4. **Validate the audio path on a real device.** Everything from the wire
   format inwards is exercised on a PC alone (`demo_sender --audio`), but
   `AudioRecord` capture and the MediaCodec AAC encoder have never run on
   real hardware — the same gap video had, and the same way to close it.
   Worth measuring lip-sync on the target PC while doing it: the audio
   device's own latency dominates the video delay A/V sync applies, and
   PortAudio's default Windows host API measured 182ms of it.
