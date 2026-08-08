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
2. Every message after that is one encoded video chunk in whatever
   `hello.codec` names — see "Frame payload" below, the two codecs frame
   differently.

### Hello payload

```json
{
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "quality": "1080p60",
  "watermark": true,
  "device_name": "Galaxy S23 Ultra",
  "codec": "h264"
}
```

`quality` is one of `"1080p60"` (free tier) or `"4k60"` (premium, see
[REWARD_MODEL.md](REWARD_MODEL.md)). The receiver uses `width`/`height`/`fps`
to size the virtual camera; it does not re-derive them from `quality` so the
two sides can never disagree about actual resolution. `codec` is `"h264"`
(the real Android app, always) or `"jpeg"` (the PC-only demo sender, and the
default when the field is missing — old senders that predate this field
still work unchanged).

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
| Android client | [`StreamProtocol.kt`](../android-app/app/src/main/java/com/framecast/streamer/streaming/StreamProtocol.kt), [`CameraStreamer.kt`](../android-app/app/src/main/java/com/framecast/streamer/streaming/CameraStreamer.kt), [`H264Encoder.kt`](../android-app/app/src/main/java/com/framecast/streamer/streaming/H264Encoder.kt) | Compiles and type-checks in this environment; the actual on-device MediaCodec round trip hasn't run outside this sandbox (no camera hardware/emulator here) |

## Deliberately out of scope for alpha

- **No audio.** Video only — Settings has `audio_bitrate`/`audio_codec`
  controls, but they're decorative (no capture, encode, or wire support).
- **No encryption/auth on the socket.** It's loopback-only (USB) or assumed
  trusted LAN (Wi-Fi); this would need attention before ever exposing the
  port beyond loopback.
- **Single connection at a time.** The receiver accepts one client; a second
  connection attempt while one is active will just hang until the first
  disconnects. Fine for "one phone, one PC" alpha use.


## Audio (optional, negotiated in Hello)

Audio is off unless the phone sets `audio: true` in Hello. That is not a
courtesy to old clients — it decides the framing of everything after Hello:

- **`audio: false`** — every message is one encoded video frame, exactly as
  described above. Unchanged, and what `demo_sender.py` and the test suite
  still produce.
- **`audio: true`** — audio and video share the socket, so every message now
  begins with a one-byte type:

  | Byte | Meaning |
  |------|---------|
  | `0x01` | video frame (the payload the message used to carry whole) |
  | `0x02` | AAC `AudioSpecificConfig` (MediaCodec's `csd-0`) |
  | `0x03` | AAC access unit |

  The type byte lives *inside* the length-prefixed payload, so the framing
  itself is identical and only the interpretation changes.

Hello carries `audio_codec` (always `"aac"`), `audio_sample_rate`,
`audio_channels` and `audio_bitrate_bps`, describing what the phone actually
resolved — not what its settings requested. A Bluetooth microphone reports
16 kHz here however the user's settings are configured, because that is what a
Bluetooth voice link can carry (see `MicrophonePolicy` on the phone).

Two rules that are easy to get wrong:

- **The config message is mandatory and repeated.** The stream is raw access
  units with no ADTS headers, so a decoder that never saw `0x02` cannot decode
  a single frame. The phone re-sends it on every reconnect, because a reconnect
  gives the PC a fresh decoder.
- **Audio is never dropped for backlog.** Video frames are skipped when the
  receiver has fallen behind real time; a dropped audio block is an audible
  gap, and audio is a rounding error next to video on this link anyway.
