# Parked features

Settings that were visible but did nothing. They are hidden from the UI rather
than deleted — the preferences, the code around them and the switches
themselves are all still there, so re-enabling any of them is a matter of
flipping `android:visibility` in `activity_settings.xml` and building the part
that was missing.

Each entry says what exists today and what "done" would actually require, so
nobody has to re-derive it.

---

## Video codec picker (H.264 / H.265 / AV1)

**Was:** a three-way picker whose preference (`video_codec`) nothing read.

**Today:** H.264 and H.265 are both real and the PC decodes both — but the
choice is made by resolution, not by the user: `H264Encoder.mimeTypeFor` picks
HEVC above 4K and AVC otherwise. AV1 is not implemented anywhere.

**To finish:** thread the preference through `StreamConfig` into
`CameraStreamer`'s encoder construction, and gate each option on
`MediaCodecList` actually reporting an encoder for it on that device — the
picker should not offer HEVC on a phone whose encoder can't do it at the
selected resolution. AV1 additionally needs `MIMETYPE_VIDEO_AV1` support
(hardware AV1 encoding is still rare on phones) and an AV1 decode path in
`pc_receiver/h264_decoder.py`.

---

## Transport protocol (TCP / UDP / WebRTC)

**Was:** a three-way picker whose preference (`protocol`) nothing read.

**Today:** TCP only. The UDP in `PcDiscovery.kt` is the discovery broadcast,
not the stream.

**Worth knowing before building either alternative:**

TCP guarantees delivery and ordering, so a lost packet stalls the stream until
it is retransmitted and the delay accumulates. Over USB (`adb reverse`) there
are no losses and no jitter, so for FrameCast's primary path TCP is the right
choice and neither alternative would improve anything.

**UDP** avoids that stall, but a 4K frame does not fit in a datagram, so it
means implementing fragmentation, reassembly, reordering, loss detection and
some form of error concealment. That is RTP, rewritten. It only pays off on
lossy Wi-Fi.

**WebRTC** already solves all of it — jitter buffer, FEC, congestion control,
NACK/PLI — and is what a serious product would use for Wi-Fi. The cost is
~10–20 MB of libwebrtc in the APK, a signalling path, and replacing the PC
receiver with something like `aiortc`. Weeks, not days.

**Recommendation:** leave both parked while USB is the primary path. If Wi-Fi
becomes the main case, go straight to WebRTC — a hand-rolled UDP transport
would be most of WebRTC's work for a fraction of its reliability.

---

## HDR

**Was:** a switch and an on-screen badge.

**Today:** it reaches the viewfinder only. `MainActivity` binds a 10-bit HLG
preview when it is on and `DeviceCapabilities.supports10BitHdr` gates it, but
`H264Encoder` sets no Main10 profile and no colour-transfer keys, so what
reaches the PC is 8-bit SDR. The badge said otherwise.

**To finish:** HEVC Main10 profile plus `KEY_COLOR_TRANSFER`/`KEY_COLOR_STANDARD`
on the encoder format, a 10-bit-capable path through `pc_receiver`'s decoder
and sinks (`pyvirtualcam` is 8-bit), and a decision about what OBS should do
with it. Needs a real HDR-capable device to verify: HDR that is signalled
wrongly looks worse than SDR — washed-out or green-tinted — rather than
failing cleanly.

The `hdr` preference defaults to false and nothing has shipped, so the badge
cannot appear in practice while the switch is hidden.

---

## Wind filter

**Was:** a switch whose preference (`wind_filter`) nothing read.

**Today:** nothing.

**Why it is not a quick win:** unlike noise reduction — which is now real, via
`android.media.audiofx.NoiseSuppressor` — Android has no wind-filter API.
Wind rejection is either a vendor-specific `AudioManager.setParameters` key
(different on every OEM, undocumented, unavailable on most) or a high-pass
filter applied to the PCM yourself. The latter is the honest option: wind noise
is concentrated below ~100 Hz, so a steep high-pass in `AudioCapture` before
encoding would do something real and measurable. That is a DSP change with
audible consequences for voice, so it wants listening tests on real recordings,
not a switch wired to an API that does not exist.

---

## Low latency

**Was:** a switch whose preference (`low_latency`) nothing read.

**Today:** everything that actually reduces latency is unconditional — a zero
dequeue timeout in `H264Encoder` so the camera thread never blocks on the
codec, CBR rate control, and the receiver dropping stale frames to stay caught
up with real time. The toggle implied those were optional.

**To finish, if it should exist at all:** it would have to name a real
trade-off. The candidates are `KEY_LATENCY`/`KEY_PRIORITY` on the encoder, a
shorter I-frame interval (faster recovery after a drop, more bitrate), and the
receiver's stale-frame threshold. Each trades bandwidth or resilience for
delay, which is a genuine choice — unlike a switch that turns on something
already on.
