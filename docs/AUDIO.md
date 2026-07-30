# Audio

Video-only was the alpha's deliberate scope; this documents the audio path
that replaced it. The short version: the phone captures with `AudioRecord`,
encodes AAC-LC with `MediaCodec`, and interleaves it with video on the one
existing TCP connection. The PC decodes it and plays it into an audio device.

## The one thing you have to install

Video has somewhere obvious to go — `pyvirtualcam` publishes a system webcam
and Zoom/Meet/OBS simply see it. **There is no equivalent for audio.** OBS's
virtual camera carries no audio track, and nothing in Python can register a
system *microphone*: on Windows that is a kernel-mode driver, which is why
DroidCam ships one of its own.

So the arrangement is:

```
phone mic ──▶ PC receiver ──▶ virtual audio cable ──▶ Zoom/Meet/OBS pick the cable as their mic
```

Install [VB-CABLE](https://vb-audio.com/Cable/) (free) or VoiceMeeter, and
the receiver finds it by name with no configuration. Then set your
conferencing app's microphone to that cable. `Install_PhoneCam.vbs` checks
for one at setup time and says so if it's missing; you can re-check any time
with:

```bash
python -m pc_receiver.check_audio_setup
```

**Without a cable, audio is not played at all** — deliberately. Falling back
to the default output device sounds friendly and isn't: this receiver
auto-starts as a background service whenever OBS opens, so that fallback
means a user's phone microphone starts coming out of their PC speakers, back
into the phone, from a service they never knowingly started and have no
window to close. Staying silent leaves them exactly where they were before
audio existed.

`--audio-speakers` (or naming a device with `--audio-device`) opts into it
deliberately, which is the right shape for something only useful while
debugging. `--audio-sink wav` is the better way to check audio is arriving.

```bash
python -m pc_receiver.receiver --list-audio-devices
```

### Which route to the device matters as much as which device

Windows exposes the same physical device once per *host API*, and they are
not equivalent. Measured on the reference PC:

| Host API | Latency |
|---|---|
| Windows WASAPI | 3 ms |
| Windows WDM-KS | 10 ms (but takes the device exclusively) |
| MME | **90 ms** — PortAudio's default |
| Windows DirectSound | 120 ms |

Since the delay A/V sync applies to video *is* the audio device's latency,
picking the wrong route is a lip-sync bug rather than a preference — and
asking sounddevice for a device by name gets MME's copy. The receiver
therefore selects by index, ranked by host API, which took the measured
device latency on this PC from **182 ms to 22 ms**. WDM-KS ranks below the
default despite being fast: taking the device exclusively would lock out the
very app meant to be listening to the cable.

Off Windows the ranking is a no-op — unknown host API names score neutral,
so ALSA/CoreAudio simply sort by their reported latency.

## Receiver options

| Flag | Effect |
|---|---|
| `--audio-sink device` | Default. Plays into a virtual cable, or nothing if there isn't one |
| `--audio-sink wav` | Records to `--audio-wav` instead. No driver needed — the way to check audio without installing anything |
| `--audio-sink null` | Decodes and discards. For tests |
| `--audio-sink none` | Ignores audio entirely, even if the phone sends it |
| `--audio-device NAME` | Substring of the output device to use, overriding auto-detection |
| `--audio-speakers` | Allow an ordinary output device when no cable exists — see above |
| `--list-audio-devices` | Print every output device with its host API, and exit |
| `--no-av-sync` | Stop delaying video to match audio — see below |

A sender with no audio is unaffected by all of these.

## A/V sync

Both streams are stamped with the phone's `System.nanoTime()`, so the PC can
tell how far apart they are. They then leave the PC by paths with very
different latencies:

- **video** decodes and goes straight to the virtual camera — effectively
  immediate.
- **audio** goes through a jitter buffer and the sound device's own buffer,
  because an audio device that runs dry produces an audible click. It is
  therefore heard *later*.

Closing the gap means delaying video, since audio cannot be hurried without
pitching or glitching it. [`av_sync.py`](../pc_receiver/av_sync.py) holds
each decoded frame until the audio playout clock reaches its timestamp.

**That delay is a real cost** for a product whose pitch is low latency, so it
is bounded at both ends: the jitter buffer is kept at 60ms, and a frame is
never held longer than 350ms whatever the audio clock says. If audio stalls
or dies, video runs free again immediately — a dead audio path degrades to a
stutter, never a frozen picture.

The dominant term is the *device's* own latency, not anything here — which
is why host-API selection above matters so much: 182ms via PortAudio's
default MME, 22ms via WASAPI, for the same physical device. If lip-sync still
costs more video latency than you want, `--no-av-sync` turns the alignment
off and the video path behaves exactly as it did before audio existed. The
periodic pipeline log reports the measured skew either way:

```
pipeline: in=59.8fps decoded=59.8fps shown=59.6fps ... audio=48.0kHz skew=+41ms held=2
```

## Codecs

**AAC-LC** is what a session actually uses. MediaCodec's AAC-LC encoder is
the one audio encoder every Android device is required to ship, and ~192
kbit/s against ~1.5 Mbit/s of raw stereo is worth having even when video
dwarfs both.

It goes on the wire **ADTS-framed**, which is the detail that makes
reconnects work. MediaCodec emits raw access units plus a one-off
`AudioSpecificConfig`; a stream framed that way can only be decoded from its
start, and every reconnect gives the PC a brand-new decoder. ADTS re-states
the profile, rate and channel count on every frame — 7 bytes per 21ms — so
the stream can be joined anywhere.

**PCM (`pcm_s16le`)** is the fallback when the AAC encoder will not start, so
"the microphone works" never depends on a vendor codec behaving.

**Opus and FLAC** appear in the Settings spinner but are not implemented for
streaming, and fall back to AAC with a log line. MediaCodec's Opus *encoder*
is API 29+ and inconsistently present in practice; FLAC is a lossless
archival codec whose bitrate makes no sense for a live stream.

## Noise reduction and the wind filter

**Noise reduction** hands the capture session to Android's own
`NoiseSuppressor` effect. It is an optional vendor effect, so
`isAvailable()` returning false is a normal outcome, logged and ignored.

**Wind filter** is a 100 Hz second-order Butterworth high-pass applied to the
PCM before encoding ([`HighPassFilter.kt`](../android-app/app/src/main/java/com/phonecam/streamer/audio/HighPassFilter.kt)).
There is no Android API for this one, so it is done on the samples.

That is genuinely what a wind filter is: wind across a microphone port isn't
a sound the mic picks up so much as turbulence hitting the diaphragm, and
its energy sits almost entirely below ~100 Hz — under the ~85 Hz bottom of an
adult speaking voice. Rolling that band off is what a foam windscreen does
mechanically. It also earns its keep indoors, where the same band is desk
thumps, handling noise and air conditioning, all of which otherwise cost
bitrate the encoder could spend on the voice.

The filter's actual frequency response is unit tested rather than assumed —
20 Hz down to under 10%, everything from 300 Hz up flat within 5%, and −3 dB
at the corner — because a filter that is subtly wrong still produces audio,
so "it ran and made sound" would prove nothing.

## What happens when things go wrong

The rule throughout: **audio failing never costs you video.** A user on a
call would far rather lose their microphone than their camera.

| Situation | Result |
|---|---|
| RECORD_AUDIO not granted | Video-only session. The permission is requested when streaming starts, and takes effect next time |
| No usable mic format | Falls back across stereo→mono and the chosen rate→48kHz before giving up; Hello announces whatever really opened |
| AAC encoder won't start | Falls back to raw PCM |
| PC has no virtual cable | Audio not played, with a log line saying how to fix it. Video unaffected |
| Chosen device won't open | Retries the system default (WASAPI shared mode refuses formats a device's mixer isn't configured for) |
| PC has no audio device | Logged; session continues video-only |
| PC doesn't know the codec | Logged; session continues video-only |
| Corrupt audio packet | Dropped. A few milliseconds of audio, never the connection |
| Audio dies mid-session | Audio stops; framing does not change, and video is unaffected |

## Reconnects

The phone reconnects on its own (cable pulled, PC asleep, receiver
restarted). For audio that means:

- Audio queued for a connection that no longer exists is **dropped, not
  sent**. Video can arrive late because the receiver skips to the freshest
  frame; audio cannot, and a burst of pre-reconnect packets would play out
  behind the picture and desync everything after it. Silence across the gap
  is correct.
- ADTS framing means the new decoder needs nothing re-sent.

Video needed two fixes to survive the same event, both in this change: the
encoder's SPS/PPS is now kept and re-sent to a reconnected receiver, and a
keyframe is requested so its decoder has an IDR to start from rather than
waiting up to two seconds for the next one. Before this, a reconnected
session was still arriving and still being decoded — into no frames at all.

## Testing it without a phone

`demo_sender` stands in for the phone's audio path too, encoding real AAC
with PyAV and framing it exactly as the app does:

```bash
python -m pc_receiver.receiver --port 8787 --sink null --audio-sink wav
python -m pc_receiver.demo_sender --port 8787 --audio --frames 300
```

The tone pulses once a second against video that carries a frame counter, so
a sync problem is audible rather than only visible in the logs.

## Implementation

| Side | File | Role |
|---|---|---|
| Phone | [`AudioCapture.kt`](../android-app/app/src/main/java/com/phonecam/streamer/audio/AudioCapture.kt) | `AudioRecord`, format fallbacks, noise suppression |
| Phone | [`AudioEncoder.kt`](../android-app/app/src/main/java/com/phonecam/streamer/audio/AudioEncoder.kt) | AAC-LC via MediaCodec, PCM fallback |
| Phone | [`AdtsHeader.kt`](../android-app/app/src/main/java/com/phonecam/streamer/audio/AdtsHeader.kt) | ADTS framing (unit tested) |
| Phone | [`AudioTimeline.kt`](../android-app/app/src/main/java/com/phonecam/streamer/audio/AudioTimeline.kt) | Timestamps on video's clock (unit tested) |
| Phone | [`AudioStreamer.kt`](../android-app/app/src/main/java/com/phonecam/streamer/streaming/AudioStreamer.kt) | Capture→encode→send loop |
| Both | [`MediaChunk.kt`](../android-app/app/src/main/java/com/phonecam/streamer/streaming/MediaChunk.kt) / [`protocol.py`](../pc_receiver/protocol.py) | Chunk tagging (unit tested both sides) |
| PC | [`audio_decoder.py`](../pc_receiver/audio_decoder.py) | AAC/PCM → int16 |
| PC | [`audio_sinks.py`](../pc_receiver/audio_sinks.py) | Jitter buffer, playout clock, device/wav/null sinks |
| PC | [`av_sync.py`](../pc_receiver/av_sync.py) | Holds video to the audio clock |
