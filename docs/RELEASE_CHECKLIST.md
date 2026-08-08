# Release checklist

Where FrameCast actually stands against "can this be uploaded to Google Play
and handed to users". Written after the PhoneCam → FrameCast rename; re-check
it before every submission, since several entries are things Play tightens over
time rather than one-off tasks.

Two things could not be verified in the environment this was written in and are
called out where they matter: `dl.google.com` is blocked by network policy, so
the Android project was never compiled or run here, and there is no Windows
machine, so the installer's VBScript was never executed. Everything about the
PC side below was verified by actually installing and running the built zip on
Linux.

---

## Blocking — Play will reject the upload

### 1. `targetSdk` is below what Play accepts

`app/build.gradle.kts` sets `targetSdk = 34`. Play requires new apps and
updates to target a recent API level, raised every August; 34 has been below
the floor since 31 August 2025. The Play Console rejects the artifact at upload
time, before any review.

Deliberately **not** bumped here. From `targetSdk = 35` Android enforces
edge-to-edge, so the system bars stop reserving space and the app draws behind
them. `MainActivity` has no inset handling at all (`grep` for `WindowInsets` in
`app/src/main` finds nothing outside `activity_settings.xml`'s
`fitsSystemWindows`), so its overlay controls — the record button, the gear,
the status pills — would move under the status and navigation bars. That is a
visual regression that has to be seen on a device to fix, and it cannot be seen
from here.

What it needs: bump `targetSdk` to whatever Play's current floor is, apply
`WindowInsets.Type.systemBars()` padding to the control overlays in
`activity_main.xml` (not to `previewView`, which should stay full-bleed), then
check both a gesture-navigation and a 3-button-navigation device.

### 2. The AdMob application ID is Google's public test ID

`AndroidManifest.xml` ships
`ca-app-pub-3940256099942544~3347511713`, which is Google's sample App ID — the
manifest says so itself. It is correct for development and a policy violation in
production: it serves test ads, earns nothing, and is not yours.

Needs a real AdMob account, a registered app, and the real App ID plus real ad
unit IDs. [docs/ADS_SETUP.md](ADS_SETUP.md) covers the swap.

### 3. The consent message is not configured

`ConsentManager` runs Google's UMP flow, but the actual consent message and
privacy-policy URL are set up in the AdMob console, not in code. Serving
personalised ads in the EEA/UK without it is a policy violation. Play also
requires a reachable privacy policy URL on the store listing itself.

### 4. Nothing has run on a real device

The app has never been built or run on hardware. The camera → MediaCodec →
network path in particular has no coverage anywhere: the H.264 tests in
`pc_receiver/tests/test_h264_decoder.py` exercise the *decode* half against
PyAV-encoded streams, never the phone's encoder. Buffer handling and colour
formats are exactly where MediaCodec implementations differ per OEM.

---

## Handled

- **Naming.** `com.framecast.streamer` for `applicationId`, `namespace` and
  every Kotlin package; `FrameCast` for `app_name` in all 21 locales, the
  theme and style names, the Gradle project, the wire-protocol discovery
  constants (`FRAMECAST_DISCOVER`/`FRAMECAST_HERE`), the control-server auth
  header, the Windows Startup shortcut, and every file name. `grep -ri
  phonecam` returns nothing.

  The `applicationId` change is one-way: it can never be changed again once a
  build with it has been published, and publishing under a different one
  creates a separate listing rather than an update.

- **Icon.** A FrameCast mark — viewfinder brackets around a cast glyph — as an
  adaptive icon with a `<monochrome>` layer for Android 13+ themed icons. Key
  art stays inside the 72dp safe circle, so the tightest launcher mask cannot
  clip it. `tools/make_brand_assets.py` derives `brand/framecast.ico` (Windows)
  and `brand/framecast_icon_512.png` (the Play listing icon) from the Android
  drawables themselves, so the phone and the PC cannot end up with different
  marks.

- **Release signing.** `app/build.gradle.kts` reads the keystore from
  `android-app/keystore.properties` (gitignored) or `FRAMECAST_*` environment
  variables. A machine with neither still builds — unsigned, so useless for
  upload, but debug builds and unit tests keep working.

- **The PC download.** `dist/FrameCast_PC_Setup.zip`, built by
  `tools/build_pc_zip.py`, verified by `tools/tests/test_pc_zip.py` and by an
  end-to-end run: extracted clean, `python -m venv` + `pip install -r
  requirements.txt` exactly as `_setup_helper.bat` does it, then receiver and
  demo sender streamed 20 frames over the real wire protocol with zero decode
  failures, at both tiers (free → 1080p60 watermarked, three ads → 4K60 clean).

---

## Fixed on the way through

Each of these was live in the code being audited.

- **The H.264 decoder produced nothing on any PC without an NVIDIA GPU.**
  `h264_cuvid` can be *created* on any machine whose FFmpeg build includes it;
  it only fails later, inside `avcodec_open2`, on the first packet. That error
  arrives as an `FFmpegError`, the same type `decode()` deliberately swallows
  for corrupt chunks — so the fallback to software decoding never fired and
  every frame of the session was silently dropped. A black virtual camera and
  an empty log. Now falls back at first use.

- **A single corrupt chunk could hang the receiver permanently.**
  Frame-threaded decoding parks its worker threads on a packet it cannot parse,
  and freeing the context joins them, so `close()` without a `flush()` first
  blocked forever and took the connection teardown with it. `close()` now
  drains before releasing.

- **`FrameCast_PC.bat` broke OBS sync.** It launched `control_server.py` by
  path, so the absolute package imports in its OBS handlers raised
  `ModuleNotFoundError` — "Sync OBS settings" silently did nothing on that path
  only. Now launched as `-m pc_receiver.control_server`, like the service
  already was.

- **`--sink preview` failed with an OpenCV internal error**, because
  `requirements.txt` ships the headless wheel. Now says so, and says what to
  install instead.

- **The receiver's end-to-end tests only passed on Windows.** `_peer_ip`
  indexed `getpeername()` blindly, which raises `IndexError` on the AF_UNIX
  sockets `socket.socketpair()` returns on POSIX.

- **`demo_sender` crashed its own closing log line** by passing a bound method
  where a number was expected.

- **`pytest` was in `requirements.txt`**, so every user installed it. Test and
  build tooling moved to `requirements-dev.txt`.

---

## Worth doing, not blocking

- **Dependency versions are unpinned.** `pc_receiver/requirements.txt` names
  five packages with no constraints, and the installer resolves them fresh on
  every user's machine — so a breaking release of numpy, OpenCV or PyAV reaches
  users directly, and two users installing a week apart do not get the same
  thing. Pin them once there's a Windows machine to verify the pinned set on;
  pinning blind risks pinning something that doesn't work there.

- **Wi-Fi control requests can't authenticate.** `control_server` accepts an
  `X-FrameCast-Token` header for non-loopback callers, but `PcControl.kt` never
  sends one — it only fetches the token over the USB tunnel. Any control call
  over Wi-Fi gets a 403 once a token exists on disk. Day-to-day this is
  invisible because the services start eagerly and the video socket carries its
  own token, but the Settings screen's "Discover"/"Start PC" buttons are
  affected.

- **R8 is off** (`isMinifyEnabled = false`), so the APK ships larger than it
  needs to. Turning it on needs keep rules worked out against a real device for
  the AdMob, UMP and CameraX reflective paths.

- **Play listing assets.** `brand/framecast_icon_512.png` is the 512×512
  listing icon. Still needed: a 1024×500 feature graphic, phone screenshots,
  short and full descriptions, a privacy policy URL, and the Data safety form —
  which has to declare the advertising ID, since the manifest requests
  `AD_ID`.
