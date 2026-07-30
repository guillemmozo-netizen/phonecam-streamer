# Streaming protocol

## Transport: why `adb reverse` instead of a driver

The differentiator worth building (per the original discussion) is "connect
your phone over USB and get lower latency / better quality than DroidCam."
The USB part does **not** require writing a custom USB accessory protocol or
a kernel driver. Android already exposes a normal TCP loopback interface to
`adb`, and `adb reverse` tunnels a port from the PC's `127.0.0.1` to the
phone's `127.0.0.1` over the existing USB debugging connection:

```
adb reverse tcp:8787 tcp:8787
```

After that, the phone app can `connect()` to `127.0.0.1:8787` *on the
phone*, and that connection is transparently forwarded over USB to
`127.0.0.1:8787` on the PC, where `pc_receiver/receiver.py` is listening.
No Wi-Fi, no pairing, no custom driver, no root. This is the same trick a
number of shipping "USB webcam" Android apps use. Wi-Fi mode is the same
protocol, just pointed at the PC's LAN IP instead of `127.0.0.1`.

The PC receiver is always the TCP **server**; the phone app is always the
**client**. This means the receiver can be started once and left listening,
and multiple phone-app connection attempts (reconnect after a dropped USB
cable, etc.) just work without the PC needing to know the phone's address.

## Framing

Every message on the wire — reused identically for both the "hello" and
every subsequent video frame — is:

```
[4 bytes: big-endian uint32 length] [that many bytes: payload]
```

1. Immediately after connecting, the client sends exactly one **hello**
   message: a UTF-8 JSON object.
2. Every message after that is one encoded media chunk — video in whatever
   `hello.codec` names (see "Frame payload" below, the two codecs frame
   differently), and audio too once `hello.audio_codec` is set (see
   "Chunk tagging").

### Hello payload

```json
{
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "quality": "1080p60",
  "watermark": true,
  "device_name": "Galaxy S23 Ultra",
  "codec": "h264",
  "audio_codec": "aac",
  "audio_sample_rate": 48000,
  "audio_channels": 2,
  "audio_bitrate_bps": 192000
}
```

`quality` is one of `"1080p60"` (free tier) or `"4k60"` (premium, see
[REWARD_MODEL.md](REWARD_MODEL.md)). The receiver uses `width`/`height`/`fps`
to size the virtual camera; it does not re-derive them from `quality` so the
two sides can never disagree about actual resolution. `codec` is `"h264"`
(the real Android app, always) or `"jpeg"` (the PC-only demo sender, and the
default when the field is missing — old senders that predate this field
still work unchanged).

`audio_codec` is `"aac"`, `"pcm_s16le"`, or `""` (default) for a sender with
no audio. It is the **single switch for the framing of everything after the
hello** — see below. The three `audio_*` format fields describe what the
phone's microphone really opened, which is not always what its Settings
asked for (stereo can come back mono, 96kHz can come back 48kHz), so the
receiver sizes its decoder and output device from these rather than from any
assumption. A hello naming a codec but no format is rejected outright.

### Chunk tagging

When `audio_codec` is empty, every message after the hello is a bare video
payload, byte for byte as it always was. **Nothing changes for a sender
without audio**, which is what keeps older phones and the JPEG demo sender
working against a current receiver.

When it is non-empty, each of those payloads is prefixed with 9 bytes:

```
[4 bytes: length] [1 byte: kind] [8 bytes: big-endian int64 pts_us] [encoded data]
```

`kind` is 1 for video, 2 for audio. `pts_us` is microseconds from the
phone's `System.nanoTime()` — **one clock shared by both streams**, which is
the whole basis for A/V alignment on the PC (see
[AUDIO.md](AUDIO.md#av-sync)). It is *signed*: nanoTime's origin is
arbitrary and starts negative on some devices, and only differences are ever
taken.

One socket rather than two, because the transport is often an `adb reverse`
tunnel — one forwarded port is one thing for the user and the installer to
get right, and both streams then take an identical path, so neither can be
delayed relative to the other by an unrelated route. The cost is head-of-line
blocking, which at these frame sizes and link speeds (a 4K60 frame is ~100KB
on a link measured at 80-300+ Mbps) is well under a millisecond — far below
one audio packet's own duration. Two sockets would trade that for clock skew
between the streams, which is the harder problem.

Audio chunks are one ADTS-framed AAC-LC access unit (~21ms at 48kHz), or one
block of raw interleaved 16-bit little-endian PCM. ADTS re-states the format
on every frame so a decoder can join the stream anywhere — which matters
because every reconnect gives the receiver a brand-new decoder.

### Frame payload

- **`codec: "h264"`** (real devices): one Annex-B chunk per message —
  either the codec config data (SPS/PPS, sent once up front) or one frame's
  encoded access unit. Produced by `H264Encoder.kt` (Android `MediaCodec`,
  buffer-mode input, bitrate = the user's Settings `video_bitrate` — a real
  encoder parameter now, not just a saved preference) and decoded by
  `pc_receiver/h264_decoder.py` (PyAV/FFmpeg). Not every chunk yields a
  decoded frame 1:1 (the config chunk yields none, encoder/decoder
  pipelining can occasionally batch), so both sides treat "0 frames out of
  this chunk" as normal, not an error.
- **`codec: "jpeg"`** (PC-only demo sender only): raw JPEG bytes
  (`cv2.IMWRITE_JPEG_QUALITY` 80 for free tier, 95 for premium). One frame
  per message, no batching — unchanged from the original alpha design,
  kept because it needs no decoder dependency on the PC side to exercise
  the rest of the pipeline (reward gating, watermark, framing) standalone.

## Implementations

| Side | File | Notes |
|---|---|---|
| PC receiver (server) | [`pc_receiver/protocol.py`](../pc_receiver/protocol.py), [`receiver.py`](../pc_receiver/receiver.py), [`h264_decoder.py`](../pc_receiver/h264_decoder.py) | Actually running + tested in this environment, H.264 path included (`pc_receiver/tests/test_h264_decoder.py`) |
| PC demo sender (client, stand-in for the phone) | [`pc_receiver/demo_sender.py`](../pc_receiver/demo_sender.py) | Uses the PC's own webcam, or a synthetic pattern if none is attached; always sends `codec: "jpeg"` |
| Android client | [`StreamProtocol.kt`](../android-app/app/src/main/java/com/phonecam/streamer/streaming/StreamProtocol.kt), [`MediaChunk.kt`](../android-app/app/src/main/java/com/phonecam/streamer/streaming/MediaChunk.kt), [`CameraStreamer.kt`](../android-app/app/src/main/java/com/phonecam/streamer/streaming/CameraStreamer.kt), [`H264Encoder.kt`](../android-app/app/src/main/java/com/phonecam/streamer/streaming/H264Encoder.kt) | Compiles and type-checks in this environment; the actual on-device MediaCodec round trip hasn't run outside this sandbox (no camera hardware/emulator here). `MediaChunk`'s byte layout is unit tested against the same `>Bq` decoding the receiver applies |
| Audio, both sides | see [AUDIO.md](AUDIO.md#implementation) | The wire format, decode and A/V sync are exercised end to end on a PC alone via `demo_sender --audio` |

## Deliberately out of scope for alpha

- **No encryption/auth on the socket.** It's loopback-only (USB) or assumed
  trusted LAN (Wi-Fi); this would need attention before ever exposing the
  port beyond loopback.
- **Single connection at a time.** The receiver accepts one client; a second
  connection attempt while one is active will just hang until the first
  disconnects. Fine for "one phone, one PC" alpha use.
