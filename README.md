# FrameCast (alpha)

Use an Android phone as a PC webcam over USB (or Wi-Fi), monetized with
opt-in rewarded ads instead of a forced paywall: free tier is
1080p60/watermarked/ad-supported, watching a rewarded ad temporarily unlocks
4K60/no watermark/no ads. See [docs/REWARD_MODEL.md](docs/REWARD_MODEL.md)
for the economy design and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for
the full picture, including exactly what's real vs. stubbed in this alpha.

## What you can run right now (no phone needed)

This repo includes a PC-only demo that exercises the *entire* pipeline —
reward-tier gating, watermarking, JPEG encoding, the TCP wire protocol, and
virtual-camera output — using the PC's own webcam (or a synthetic test
pattern if no webcam is attached) standing in for the phone.

```bash
python -m venv .venv
./.venv/Scripts/python -m pip install -r pc_receiver/requirements-dev.txt

# terminal 1: the receiver (feeds a virtual camera other apps can select)
./.venv/Scripts/python -m pc_receiver.receiver --port 8787 --sink virtualcam

# terminal 2: the sender, simulating a user who has watched a full ad batch
./.venv/Scripts/python -m pc_receiver.demo_sender --port 8787 --simulate-ads 3
```

`--simulate-ads 3` because a reward takes a full batch of three ads
(`RewardConfig.ads_per_reward`) — anything less leaves you on the free tier
(1080p60, small watermark), which is what you get with the flag dropped
entirely. `--sink virtualcam` is what makes the phone show up as a normal
webcam in Zoom/Meet/etc. (OBS Virtual Camera is the backend `pyvirtualcam`
drives, so OBS has to be installed). `--sink preview` opens a plain OpenCV
window instead, but needs a non-headless OpenCV — see requirements-dev.txt.
`--sink null` decodes and discards, which is the one that works anywhere.

## Running the tests

```bash
./.venv/Scripts/python -m pytest
```

The Android module's Android-free unit tests run too, without Gradle or an SDK:

```bash
tools/run_kotlin_tests.sh
```

120 pass in this environment: the reward/credit economy (18), the receiver,
wire protocol, H.264 decode path and network auth surface (83, including a
real socket-based end-to-end handshake and a PyAV encode/decode round trip),
and the packaging itself (19 — that the zip is complete, that its
dependency list covers every import in it, and that every module in it
imports from the layout it extracts into).

## Repository layout

```
reward_engine/    Reward/credit economy — pure logic, reference implementation, fully tested
pc_receiver/      PC-side receiver (virtual camera) + demo sender (webcam/synthetic) + wire protocol
android-app/      Kotlin/CameraX Android app skeleton — the real product, not compiled in this sandbox
brand/            Generated icon assets (see tools/make_brand_assets.py) — the Android launcher icon is their source
tools/            Build scripts: the PC download zip and the brand assets, plus their tests
dist/             The built PC download, committed so the link below resolves
docs/             Architecture, wire protocol, reward-model design, and the release checklist
```

## Android app

The Android app under `android-app/` is a complete Gradle project — CameraX
capture, the same reward engine ported 1:1 to Kotlin, watermark overlay,
real H.264 encoding (`MediaCodec`, see `H264Encoder.kt` — replaced the
original per-frame JPEG transport; `video_bitrate` in Settings is now the
encoder's actual configured bitrate, not just a saved preference), and the
TCP client. It builds cleanly (`./gradlew compileDebugKotlin` and
`testDebugUnitTest` both pass) wherever a JDK + Android SDK are installed;
open `android-app/` in Android Studio to build an APK. The JUnit suite in
`app/src/test/java/com/framecast/streamer/rewards/RewardManagerTest.kt`
mirrors the already-passing Python test suite case-for-case. The H.264 path
is exercised on the PC side by real tests (encode a stream with PyAV, decode
it back with the receiver's own decoder — see
`pc_receiver/tests/test_h264_decoder.py`), but this sandbox has no camera
hardware or Android emulator, so the Android-side encoder itself — the
actual MediaCodec buffer/color-format handling — is not yet confirmed
against a real device.

### Connecting the PC (one-time setup, then zero commands, zero windows)

Double-click **`Install_FrameCast.vbs`** once. It sets up a private Python
environment for `pc_receiver`, checks that every file it needs actually
survived extraction, and registers a lightweight watcher
(`FrameCast_Service.vbs`) to run at login — no console window at any point,
just a confirmation popup at the end.

That watcher does nothing — no ports bound, no processes spawned — until it
sees `obs64.exe`/`obs32.exe` running, polling every few seconds via WMI.
The moment OBS opens, it starts `control_server.py`; the moment OBS closes,
it stops it again (kills the whole process tree, not just the top-level
one). So while OBS is running:

- **USB**: plug the phone in. A background watcher in `control_server.py`
  polls `adb devices` and pushes the `adb reverse` tunnels itself the moment
  it sees an authorized device — no `adb reverse` command, no Python command,
  nothing to tap in Settings. Just plug in and tap "Start streaming".
- **Wi-Fi**: open the app on the same network and tap "Start streaming" —
  it broadcasts for the PC and connects automatically (discovery/receiver
  services are started eagerly by `control_server.py`, not only on request).
  The manual "Discover"/"Start PC" buttons in Settings still exist for
  troubleshooting, but shouldn't be needed day-to-day.

Nothing shows a window by design, so if something isn't working, the place
to look is `pc_receiver/logs/` (`control_server.log`, `receiver.log`,
`discovery.log`, `speed_test.log`) and `pc_receiver/setup_log.txt` for the
install step itself.

Prefer to run things visibly by hand, or just for one session without
installing anything permanently? `pc_receiver/FrameCast_PC.bat` (or
`python -m pc_receiver.control_server`) starts the same thing in a normal
console window, without touching Windows Startup. `pc_receiver/install_startup.bat`
is the older, manual equivalent of step 2 above (Windows-startup
registration only, no venv/dependency setup) for anyone who already has a
working Python environment.

[**dist/FrameCast_PC_Setup.zip**](dist/FrameCast_PC_Setup.zip) is the same
thing packaged as a standalone download: installer, README and the FrameCast
icon at the top, everything else tucked into `pc_receiver/` and
`reward_engine/`. Rebuild it after changing anything on the PC side —

```bash
python tools/build_pc_zip.py
```

— and commit the result: the build is byte-reproducible and a test compares
the committed zip against what the current sources produce, so a stale
download fails the suite rather than reaching a user.

## Honest status

This is an alpha skeleton, not a finished product. What's solid: the reward
economy and the network protocol, both real code with passing tests and a
live end-to-end run. Rewarded ads are wired up for real (AdMob load/show/
reward flow, gated behind GDPR/UMP consent) using Google's public test ad
unit — see [docs/ADS_SETUP.md](docs/ADS_SETUP.md) for how to see a real test
ad render today and how to swap in your own ad unit once you have an AdMob
account. What's not yet done: running the Android app on real
hardware (it compiles and its unit tests pass, and the new H.264 path has
real PC-side decode tests, but the encoder itself — the actual camera →
MediaCodec → network round trip — hasn't been validated against a real
device yet) and configuring the actual consent message copy in the AdMob
console. `video_codec` in Settings still only actually produces H.264
regardless of which option is picked (H.265/AV1 are unimplemented), and
`audio_codec` remains decorative (AAC is the only codec produced).

Audio itself is real now: pick a microphone — built-in, USB-C or Bluetooth —
and the app reports what it can actually do rather than what was asked for
(a Bluetooth mic is capped at 16 kHz by Bluetooth, not by a setting), captures
it, encodes AAC and multiplexes it onto the same socket as video. On the PC it
plays out of a device you choose; pointing that at VB-CABLE is what makes the
phone's mic selectable as an input in Zoom/Meet/OBS, since Windows ships no
virtual audio device of its own. "Noise reduction" now drives the platform's
`NoiseSuppressor` instead of nothing.

Settings that never did anything — the codec and protocol pickers, the wind
filter, low latency, and HDR (which only ever reached the viewfinder) — are
hidden rather than deleted, with what each would take to finish written down in
[docs/PLANNED.md](docs/PLANNED.md).

Before submitting anything to Google Play, work through
[docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) — it lists what is
already handled, and the handful of things that still block an upload.
