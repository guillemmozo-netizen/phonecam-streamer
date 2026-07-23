# PhoneCam Streamer (alpha)

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
./.venv/Scripts/python -m pip install -r pc_receiver/requirements.txt

# terminal 1: the receiver (feeds a virtual camera other apps can select)
./.venv/Scripts/python -m pc_receiver.receiver --port 8787 --sink preview

# terminal 2: the sender, simulating a user who already watched 1 rewarded ad
./.venv/Scripts/python -m pc_receiver.demo_sender --port 8787 --simulate-ads 1
```

Drop `--simulate-ads 1` to see the free tier instead (1080p60, small
watermark). Use `--sink virtualcam` instead of `--sink preview` if you have
OBS installed (OBS Virtual Camera is the backend `pyvirtualcam` drives) —
that's what makes the phone show up as a normal webcam in Zoom/Meet/etc.

## Running the tests

```bash
./.venv/Scripts/python -m pytest
```

30/30 pass in this environment: the reward/credit engine (15 tests), the
wire protocol (6 tests), and the receiver/sender pipeline (9 tests,
including a real socket-based end-to-end handshake).

## Repository layout

```
reward_engine/    Reward/credit economy — pure logic, reference implementation, fully tested
pc_receiver/      PC-side receiver (virtual camera) + demo sender (webcam/synthetic) + wire protocol
android-app/      Kotlin/CameraX Android app skeleton — the real product, not compiled in this sandbox
docs/             Architecture, wire protocol, and reward-model design docs
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
`app/src/test/java/com/phonecam/streamer/rewards/RewardManagerTest.kt`
mirrors the already-passing Python test suite case-for-case. The H.264 path
is exercised on the PC side by real tests (encode a stream with PyAV, decode
it back with the receiver's own decoder — see
`pc_receiver/tests/test_h264_decoder.py`), but this sandbox has no camera
hardware or Android emulator, so the Android-side encoder itself — the
actual MediaCodec buffer/color-format handling — is not yet confirmed
against a real device.

### Connecting the PC (one-time setup, then zero commands, zero windows)

Double-click **`Install_PhoneCam.vbs`** once. It sets up a private Python
environment for `pc_receiver` and registers a lightweight watcher
(`PhoneCam_Service.vbs`) to run at login — no console window at any point,
just a confirmation popup at the end (see
[dist/PhoneCam_PC_Setup.zip](dist/PhoneCam_PC_Setup.zip) for the same thing
packaged as a standalone download: installer + README at the top, everything
else tucked into `pc_receiver/`).

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
installing anything permanently? `pc_receiver/PhoneCam_PC.bat` (or
`python -m pc_receiver.control_server`) starts the same thing in a normal
console window, without touching Windows Startup. `pc_receiver/install_startup.bat`
is the older, manual equivalent of step 2 above (Windows-startup
registration only, no venv/dependency setup) for anyone who already has a
working Python environment.

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
`audio_bitrate`/`audio_codec` remain decorative — there's still no audio
capture/transmission pipeline at all, only the local visual level meter.
