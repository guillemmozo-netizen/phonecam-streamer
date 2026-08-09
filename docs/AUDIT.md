# Adversarial review — FrameCast

An attempt to break the design rather than to describe it. Findings are ordered
by severity. Each says whether it was **verified** by running something, or
**analysed** by reading the code — the distinction matters, because two of the
analysed ones depend on hardware this environment does not have.

Reproduction steps assume a checkout and `pytest`-installed dependencies.

---

# CRITICAL

## C1. Wi-Fi pairing cannot work at all — `GET /pair` is unreachable

**Verified.**

**Impact.** The feature that exists specifically to let an unpaired phone
obtain a token is gated behind having a token. Every Wi-Fi user hits it. This
is a regression introduced in the same commit that added the pairing endpoint;
the unit tests exercised `open_pairing_window`/`claim_pairing` directly and
never went through the HTTP layer, so they passed while the feature was dead.

`Handler.do_GET` runs `self._authorised()` before routing. `_authorised()`
returns True for loopback, otherwise requires a valid `X-FrameCast-Token` — the
very thing the caller is trying to obtain. A LAN phone gets `403 unauthorised`
before `/pair` is ever considered.

**Reproduce.**
```
python -c "
import threading,time,socket,urllib.request,http.server
import pc_receiver.control_server as cs
cs.load_or_create_token()
s=http.server.HTTPServer(('0.0.0.0',8790),cs.Handler)
threading.Thread(target=s.serve_forever,daemon=True).start(); time.sleep(.3)
p=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); p.connect(('8.8.8.8',80)); LAN=p.getsockname()[0]
urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8790/pair/open',data=b'',method='POST'))
urllib.request.urlopen(f'http://{LAN}:8790/pair')   # -> HTTP 403
"
```

**Fix.** Route `/pair` and `/pair-status` before the authorisation check, and
let them do their own gating (which they already implement). The privileged
half stays where it is: `POST /pair/open` is still loopback-only.

**Complexity.** Trivial — move two branches above one `if`.

---

## C2. Discovery spoofing steals the token and hijacks the stream

**Analysed.**

**Impact.** Full compromise of the pairing secret and of the video/audio feed,
from any device on the same network, with no prior access.

`PcDiscovery.findPc` broadcasts to 255.255.255.255 and **accepts the first
reply**, taking the sender's address as "the PC". The reply is unauthenticated
and contains only the constant `FRAMECAST_HERE`. An attacker that answers
faster than the real PC — trivial, since they can reply to the broadcast
immediately while the PC does a `recvfrom`/`sendto` round trip, and can also
answer *unsolicited* broadcasts continuously — becomes the phone's PC.

The phone then opens a TCP connection and sends `Hello`, which carries
`auth_token` **in cleartext**. The attacker now has the token permanently, and
also receives the user's camera and microphone. Because the token is a bearer
secret with no binding to a peer, they can then stream into the real PC's
virtual camera too.

This predates the pairing work — pairing made it worse only in that a
successful spoof during pairing also yields the token via `/pair`.

**Reproduce.** On a second LAN host, run a UDP listener on 8789 that replies
`FRAMECAST_HERE` to everything, plus a TCP listener on 8787 that prints the
first length-prefixed JSON message. Tap "Find PC" on the phone, then Start.

**Fix.** Two layers, both needed:
1. **Authenticate discovery.** The reply should carry the PC's identity and be
   verifiable — e.g. reply with a public key fingerprint, or HMAC the reply
   with the pairing token once paired, and have the phone remember the PC's
   identity across sessions (trust-on-first-use with a pinned key).
2. **Stop sending the token in cleartext.** See C3.

**Complexity.** Medium for pinning; high if done properly with a key exchange.

---

## C3. The token is a permanent cleartext bearer secret — replay is unlimited

**Analysed.**

**Impact.** Anyone who observes one session — on open Wi-Fi, on a network with
a compromised device, or via C2 — can replay the token indefinitely. There is
no expiry, no rotation, no nonce, no channel binding, and no way for the user
to revoke it short of deleting `.control_token` and re-pairing every phone.

`Hello.auth_token` goes over plain TCP. `_peer_is_authorised` compares it and
accepts. Nothing about the connection is bound into the check — not the peer
address, not a timestamp, not a counter — so a captured Hello can be replayed
verbatim, forever, from anywhere on the network.

**Reproduce.** `tcpdump -A -i any port 8787` during a Wi-Fi session; the token
is in the first message in plaintext. Replay it with a script.

**Fix.** Minimum viable: a challenge-response so the token is never transmitted
— PC sends a random nonce on connect, phone replies HMAC(token, nonce), PC
verifies. That kills passive capture and replay in one step and is a small
protocol change on both sides. Proper fix: TLS with a pinned self-signed cert
generated at install time, which also solves C2.

**Complexity.** Medium for HMAC challenge-response. High for TLS.

---

## C4. Bluetooth SCO setup blocks the main thread — ANR

**Analysed.**

**Impact.** Up to 3 seconds of `Thread.sleep` on the UI thread every time a
user starts streaming with a Bluetooth microphone selected, plus the cost of
`AudioRecord.Builder().build()`. Android's ANR threshold is 5 s; on a device
where SCO is slow to come up this is an ANR, and on every device it is a
visible freeze of the record button.

Call chain: `MainActivity` calls `CameraStreamer.start()` on the UI thread →
`start()` calls `startAudio()` **synchronously** (only `connectWithRetry` is
posted to the executor) → `AudioStreamSession.start` → `AudioCapture.start` →
`startBluetoothSco()`, which polls `getDevices()` every 100 ms for up to
`SCO_CONNECT_TIMEOUT_MS = 3000`.

**Reproduce.** Pair a Bluetooth headset, select it in Settings, tap Start.
Watch for a frozen UI; enable StrictMode's `detectDiskReads`/custom slow-call
detection, or simply log `SystemClock.uptimeMillis()` around `start()`.

**Fix.** Post `startAudio()` to a background executor and report readiness
asynchronously. The stream should begin video-only and add audio when the mic
opens, rather than delaying the whole session on the microphone.

**Complexity.** Low.

---

## C5. `MODIFY_AUDIO_SETTINGS` and `BLUETOOTH_CONNECT` are not declared — Bluetooth mics silently record the phone

**Verified** (manifest inspection).

**Impact.** This is the worst kind of failure for this app: the user selects
their Bluetooth lavalier, sees no error, and records the phone's own
microphone for the whole session. Exactly the class of "looks like it works"
problem this review was asked to find.

`AudioManager.startBluetoothSco()`, `setBluetoothScoOn()` and
`setCommunicationDevice()` all require `MODIFY_AUDIO_SETTINGS`. The manifest
declares `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` and `AD_ID` — and nothing
else. Every SCO call is wrapped in `runCatching`, so the resulting
`SecurityException` is swallowed and logged at warning level, then capture
proceeds on the default input.

On Android 12+ enumerating and routing to Bluetooth devices also needs the
runtime permission `BLUETOOTH_CONNECT`.

**Reproduce.** Install, select a Bluetooth mic, stream, inspect logcat for the
swallowed `SecurityException`, and note `routedDeviceId` is the built-in mic.

**Fix.** Declare `MODIFY_AUDIO_SETTINGS` (normal, no prompt) and
`BLUETOOTH_CONNECT` (runtime, needs a request flow). Then **verify routing
rather than assume it**: `AudioCapture` already records `routedDeviceId` — if
it does not match the requested device, tell the user instead of streaming the
wrong microphone silently.

**Complexity.** Low for the permissions, low-medium for the runtime request and
the honest failure path.

---

# HIGH

## H1. No foreground service — streaming dies when the app leaves the foreground

**Verified** (zero `<service>` elements in the manifest).

**Impact.** A webcam app whose stream stops when the screen turns off or the
user switches apps. Android blocks camera and microphone access for
non-foreground apps (camera since 9, mic since 9/11 depending on path), so
`AudioRecord` goes silent and CameraX unbinds. Users will report "it stops
when I open Zoom" — which is the entire use case.

On Android 14+ a foreground service that uses the mic must additionally
declare `FOREGROUND_SERVICE_MICROPHONE` (and `..._CAMERA`) with matching
runtime permissions, so this is not just "add a service".

**Reproduce.** Start streaming, press Home, watch the PC's virtual camera
freeze and the audio stop.

**Fix.** A foreground service with `camera|microphone` types, a persistent
notification, and the capture pipeline owned by the service rather than the
Activity. Also required for surviving Doze.

**Complexity.** Medium — it is a real restructuring of ownership, not a wrapper.

---

## H2. Unbounded executor queues turn a network stall into an OOM

**Analysed.**

**Impact.** Out-of-memory kill during exactly the situation the reconnect logic
exists for.

`networkExecutor` is `Executors.newSingleThreadExecutor()`, which uses an
**unbounded** `LinkedBlockingQueue`. Every encoded video chunk batch and every
AAC frame is submitted to it. When the PC stalls, `conn.sendFrame` blocks on
TCP backpressure; when the connection drops, `reconnect()` runs *on that same
executor* and sleeps through the backoff (up to 8 s per attempt, unlimited
attempts). Meanwhile the camera keeps encoding at 30–60 fps and the microphone
at ~47 AAC frames/s, and every one of those becomes a queued `Runnable`
holding a `ByteArray`.

At 1080p60 and 20 Mbit/s that is roughly 2.5 MB/s of retained buffers with no
ceiling. A 30-second outage is ~75 MB of queued frames that are worthless by
the time they are sent.

**Reproduce.** Start a Wi-Fi stream, `iptables -A INPUT -p tcp --dport 8787 -j
DROP` on the PC, watch the phone's heap in Android Studio's profiler.

**Fix.** A bounded queue with a drop-newest (video) / drop-oldest (audio)
policy, or better: do not queue at all — keep one "latest frame" slot for video
exactly as the PC's `_SinkWriter` does, and a small ring for audio. Stale
frames have no value in a live stream.

**Complexity.** Low-medium.

---

## H3. `AacEncoder` has no lock — `release()` races `encode()`

**Analysed.**

**Impact.** `IllegalStateException` or a native crash in MediaCodec when the
user stops streaming, on a thread the app does not control.

`AacEncoder.encode()` runs on the `framecast-audio-capture` thread.
`AudioStreamSession.stop()` calls `capture.stop()` — which joins that thread
with a **1-second timeout** and then continues regardless — and then
`encoder.release()`. If the capture thread is still inside `encode()` (blocked
in `dequeueOutputBuffer`, or descheduled), `release()` runs concurrently with
it. `H264Encoder` guards exactly this case with `encoderLock`; the audio
encoder has no equivalent.

**Reproduce.** Start and stop streaming repeatedly, especially under CPU load.
Look for `IllegalStateException` from `MediaCodec.dequeueOutputBuffer` or a
SIGSEGV in `libmediandk`.

**Fix.** Mirror the video path: a lock held by both `encode()` and `release()`,
plus a `released` flag checked inside the lock. Also make the join
unconditional, or set a flag the capture loop checks before touching the codec.

**Complexity.** Low.

---

## H4. One idle TCP connection denies service to the real phone

**Verified** (code structure; `serve_forever` is strictly sequential).

**Impact.** Any device on the LAN — no token needed, because the token is only
checked *after* `Hello` is read — permanently prevents the user's phone from
streaming, with a single connection that sends nothing.

`serve_forever` does `listen(1)` and handles one connection at a time inside
the accept loop. `handle_connection` immediately calls `reader.recv_hello()`,
which blocks in `sock.recv()`. No socket timeout is set anywhere in
`receiver.py` or `protocol.py`. The connection is never abandoned.

**Reproduce.** `python -c "import socket;s=socket.create_connection(('PC_IP',8787));import time;time.sleep(99999)"`, then try to stream from the phone.

**Fix.** A receive timeout on the accepted socket (a handshake deadline of a
few seconds, then a longer per-frame idle timeout), and a larger `listen()`
backlog. Consider handling connections on threads so a slow client cannot
starve a good one, with a hard cap on concurrent connections.

**Complexity.** Low.

---

## H5. There is no A/V synchronisation — drift is unbounded and guaranteed

**Analysed.**

**Impact.** Lip-sync error that grows without limit over a session. For a
webcam app this is the difference between usable and unusable, and it will be
reported as "the audio is ahead" long before anyone finds this document.

Nothing on the wire carries a timestamp. Video messages are bare access units;
audio messages are bare AAC access units. `Hello` has no clock reference. The
PC therefore plays each stream as fast as it arrives, and the two are actively
treated differently:

- Video is **dropped** when the receiver detects backlog (`is_stale`), and
  dropped again by `_SinkWriter`'s single-slot mailbox when the sink is slow.
- Audio is **never** dropped, by explicit design ("a dropped block is an
  audible gap").

Every dropped video frame is therefore a permanent step of desynchronisation
with no mechanism that can ever recover it.

**Reproduce.** Stream for several minutes over Wi-Fi with any contention; clap
on camera and compare the audio and video timing at the start and after five
minutes.

**Fix.** Put a monotonic presentation timestamp on every message (the encoder
already computes one for audio, and `CameraStreamer` has `presentationTimeUs`
for video), share a single clock base announced in `Hello`, and have the PC
schedule playback against it — with a small jitter buffer on audio and video
presented to match. Dropping video then has to be accounted for as a time jump,
not as a silent frame skip.

**Complexity.** High. This is a real change to the protocol and to both
pipelines, and it is the single most consequential item in this document after
the security ones.

---

## H6. Audio playback backpressure stalls the video pipeline

**Analysed.**

**Impact.** A slow, glitching, or exclusive-mode audio device on the PC freezes
the user's *video*, which looks like a network problem and is not.

In `handle_connection`, audio is decoded and written **inline on the receive
loop**: `audio_output.write(block)` calls `sounddevice`'s blocking
`OutputStream.write`. Video deliberately does not do this — it goes through
`_SinkWriter` on its own thread, with a documented rationale about exactly this
contention. Audio never got the same treatment.

If the output device underruns, is opened at a mismatched rate, or is a
Bluetooth speaker with a deep buffer, that `write` blocks the loop that also
reads video off the socket.

**Reproduce.** Point `--audio-device` at a device with a small buffer under
load, or add `time.sleep(0.2)` in `AudioOutput.write`, and watch the video
frame rate collapse.

**Fix.** Give audio its own writer thread and a bounded ring buffer, dropping
oldest on overflow. Never block the socket reader on a playback device.

**Complexity.** Low-medium.

---

# MEDIUM

## M1. Non-ASCII token header crashes the request handler

**Verified.**

**Impact.** Any LAN device can trigger an unhandled `TypeError` on an
unauthenticated code path with one byte. `socketserver` catches it, so the
process survives and the connection is dropped — but the traceback is printed,
the request is not answered, and on a threaded server this would be worse.

`secrets.compare_digest(supplied, _auth_token)` raises
`TypeError: comparing strings with non-ASCII characters is not supported` when
the header contains any non-ASCII character.

**Reproduce.**
```
printf 'GET /status HTTP/1.1\r\nHost: x\r\nX-FrameCast-Token: caf\xe9\r\n\r\n' | nc PC_IP 8790
```

**Fix.** Compare bytes, not `str`: encode both sides with
`.encode("utf-8", "ignore")` — or reject non-ASCII before comparing. One line.

**Complexity.** Trivial.

---

## M2. Multi-homed PCs answer discovery from the wrong interface

**Analysed — not reproducible here** (this environment has one interface).

**Impact.** The phone learns an IP it cannot reach and reports "Found" followed
by a connection that never succeeds. Very common on Windows, which is the
target platform: Wi-Fi plus Ethernet, plus VirtualBox/VMware/WSL/Hyper-V
virtual adapters, plus VPN adapters.

`discovery_server` binds `0.0.0.0` and replies with `sock.sendto(reply, addr)`
on that same wildcard socket. The source address is then chosen by the routing
table, which may be a virtual adapter's address. `PcDiscovery` takes
`response.address.hostAddress` as the PC's address.

**Reproduce.** On a Windows PC with VirtualBox installed (which adds a
host-only adapter), run discovery from a phone on Wi-Fi.

**Fix.** Reply from the interface the request arrived on — enumerate local
addresses, pick the one on the same subnet as `addr[0]`, and bind a per-
interface socket or set the source explicitly. Additionally, have the reply
carry the address the PC believes is reachable, and have the phone validate it
with a quick TCP probe before accepting it.

**Complexity.** Medium.

---

## M3. The pairing window can be claimed by an attacker instead of the phone

**Analysed.**

**Impact.** The single-use property that makes the window safe also makes it
stealable: the first `GET /pair` wins, and nothing verifies *who* asked. A
device already on the LAN that polls `/pair` continuously gets the token the
instant the user runs `Pair_Phone.bat`, and the user's phone then fails to pair
— which reads as "pairing is flaky", not as "someone took it".

**Reproduce.** `while true; do curl -s http://PC_IP:8790/pair; sleep 0.05; done`
from any LAN host, then run the pairing tool.

**Fix.** Bind the window to a device the user can confirm: display a short code
in the `Pair_Phone.bat` console that the phone must present, or show the
requesting device's name/IP and require confirmation. The code is what turns
"first to ask" into "the one the user is holding".

**Complexity.** Medium — needs a phone-side input dialog and strings.

---

## M4. Discovery is an unauthenticated, unthrottled responder

**Analysed.**

**Impact.** Minor amplification (18-byte request → 14-byte reply, so not a
useful amplifier) but a free, unlimited network-presence oracle and a target
for spoofed-source floods that make the PC send packets to a victim.

There is no rate limiting, no source validation, and no reply suppression.

**Fix.** Rate-limit per source address, ignore requests from outside the local
subnets, and stop replying entirely when no receiver is running.

**Complexity.** Low.

---

## M5. Audio timeline silently compresses under load

**Analysed.**

**Impact.** Contributes to H5 and makes it non-recoverable, because dropped
audio is not represented as a gap — it simply is not there, and the samples
after it play early.

Two places discard PCM without accounting for it:
- `AacEncoder.encode`: when `dequeueInputBuffer` returns <0 the buffer is
  dropped and `samplesSubmitted` is not advanced, so the encoder's presentation
  clock says no time passed.
- The same method: `writable = minOf(length, input.remaining())` silently drops
  the remainder if the codec's input buffer is smaller than the read.

**Fix.** Advance `samplesSubmitted` by the dropped sample count so timestamps
reflect real time, and loop until the whole read is submitted rather than
truncating. Log dropped-sample totals; silent audio loss is not acceptable at
any rate.

**Complexity.** Low.

---

## M6. `probeSampleRates` measures the framework, not the device

**Analysed** — and documented in the code, but the UI does not reflect the
caveat.

**Impact.** The microphone picker shows a rate and a maximum bitrate per
device, implying they were established for that device. They were not:
`AudioRecord.getMinBufferSize` is a global format query. A USB interface that
only does 44.1 kHz will still be listed as 48 kHz.

**Fix.** Probe per device by building an `AudioRecord`, calling
`setPreferredDevice`, starting it briefly and reading `getRoutedDevice()` and
`getSampleRate()`. That needs `RECORD_AUDIO` already granted, so the picker
should show declared values with an "unverified" marker until the user has
granted the permission.

**Complexity.** Medium.

---

## M7. 32 MB frame ceiling with no per-connection budget

**Analysed.**

**Impact.** An unauthenticated peer (the token check happens after `Hello`, and
`Hello` itself is length-prefixed) can make the receiver allocate up to 32 MB
per message and repeat it. `FrameReader._fill` grows a `bytearray` until the
declared length arrives.

**Fix.** Enforce a much smaller ceiling for `Hello` specifically (a few KB), and
bound total buffered bytes per connection. Read the token before accepting
large payloads.

**Complexity.** Low.

---

## M8. Vendor power management will kill the stream

**Analysed.**

**Impact.** Samsung (Device Care / "put unused apps to sleep"), Xiaomi (MIUI
Battery Saver + Autostart), Oppo/Realme (ColorOS Startup Manager), Vivo
(iManager), Huawei (Protected Apps) all aggressively kill background and even
foreground-service apps that they do not recognise. Combined with H1 — no
foreground service at all — the stream will stop on these devices in normal
use, and the app has nothing that would tell the user why.

**Fix.** Implement H1 first (a foreground service is the minimum bar these
OEMs respect), then detect the vendor and offer a one-tap path to the relevant
battery-optimisation exclusion screen, and detect unexpected termination to
explain it on next launch. `ConnectionSupervisor`'s unlimited retry helps
reconnect but cannot help if the process is dead.

**Complexity.** Medium.

---

# LOW

## L1. `AudioDeviceInventory.defaultInput` enumerates twice

`inputs(context)` is called twice in one expression when the first lookup
misses. Cheap, but it is a system call returning allocated objects, invoked
from a UI path. **Fix:** assign once. **Trivial.**

## L2. `AacEncoder.drain` allocates per output frame

A fresh `ByteArray` per AAC frame (~47/s) plus a `Runnable` per frame for the
executor. Minor GC pressure that adds to H2. **Fix:** pool or reuse buffers
where the send path allows. **Low.**

## L3. Noise suppressor released before the record it is attached to

`AudioCapture.stop()` releases the `NoiseSuppressor` before calling
`record.stop()`. The effect is attached to the record's audio session; the
documented order is to release effects after stopping the source. Unlikely to
crash, but it is the wrong order. **Fix:** reorder. **Trivial.**

## L4. `audio_messages_received` is counted but never bounded

Audio messages bypass the staleness check entirely by design, so a hostile or
buggy sender can flood them and force decode + playback work. **Fix:** rate-cap
audio against the sample rate announced in `Hello`. **Low.**

## L5. Reconnect resends the AAC config but not a video keyframe

On reconnect the phone re-sends `codecConfig` for audio, which is correct — but
nothing forces an H.264/H.265 IDR. The PC's fresh decoder will output nothing
until the next natural keyframe, which at `I_FRAME_INTERVAL_SECONDS` could be
seconds of black. **Fix:** request a sync frame via
`MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME` on reconnect. **Low.**

---

# Notes on what could not be tested

- **Nothing Android was compiled.** `dl.google.com` is blocked in this
  environment, so AGP, androidx and the SDK are unreachable. Every Android
  finding above is from reading code. Compile errors, if any, are not covered.
- **No device, no Bluetooth, no USB audio.** C4, C5, M6 and M8 are the ones
  most likely to reveal further problems on contact with real hardware.
- **One network interface.** M2 could not be reproduced, only reasoned about.
- **No Windows.** The installer's VBScript, VB-CABLE routing and OBS
  interaction are unexercised.
