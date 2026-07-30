# Stability audit — 2026-07-28

A hunt for defects that exist while the program appears to work. Scope was
the streaming-critical path on both sides, plus whatever this machine's own
production logs could be made to confess.

**Headline:** thirteen real defects found, eleven fixed. The most severe had
already fired **13 times in production** on this machine and was silently
leaking the GPU decoder, the audio device and the virtual camera every time.

| | Before | After |
|---|---|---|
| Python tests | 184 | **205** |
| Android tests | 105 | **105** |
| Memory growth under load | **778 MB/h** | none measurable |
| Recorded teardown crashes | 13 | 0 (root cause removed) |

---

## 1. Method

Three independent sources, because each finds what the others miss:

1. **Reading the code** for the classes of defect that do not announce
   themselves — unbounded growth, silently dying threads, cleanup that can be
   skipped, blocking calls with no timeout.
2. **Mining `pc_receiver/logs/receiver.log`**, which holds a week of real
   sessions from this machine. This is what turned a theory into a
   reproduction: the dominant failure was already in there, 13 times.
3. **Running things** — a soak harness, a per-stage benchmark, and automated
   fault injection.

Nothing below is claimed as verified unless it was measured. What could not
be verified is listed in §7, not quietly omitted.

---

## 2. Defects found

Severity is about consequence to a user mid-call, not about how hard the fix
was.

### F1 — CRITICAL — `flush()` returned a different pixel format from `decode()`

**`pc_receiver/h264_decoder.py`** · *fixed* · **13 occurrences in production**

`decode()` honoured `output_format`; `flush()` hardcoded RGB. On the real
(native/NV12) path a session's final frames therefore arrived in a different
layout from every frame before them.

`VirtualCamSink` derives the camera resolution from the array shape, so it
read the height as two-thirds of the picture, concluded the resolution had
changed, tore down a **working** virtual camera and tried to build a
wrongly-sized one. OBS refused:

```
RuntimeError: 'obs' backend: virtual camera output could not be started
```

Reproduced before fixing:

```
output_format = native
  decode() shapes: {(720, 640)}  -> frame_format: nv12
  flush()  shapes: {(480, 640, 3)}
  *** MISMATCH ***
  sink computes from decode frames: 640 x 480
  sink computes from flush  frames: 640 x 320   <-- forces camera re-create
```

The log confirms these were healthy sessions that died only at the end —
`shown=30.0fps`, `sink_send=0.5ms` right up to the last line.

Regression tests: `test_flush_returns_the_same_layout_as_decode` (both
formats), `test_a_sink_sees_one_consistent_geometry_for_the_whole_session`.

### F2 — HIGH — teardown could skip releasing every resource

**`pc_receiver/receiver.py`** · *fixed*

`handle_connection`'s `finally` was a plain sequence, so the first step to
raise skipped all the rest. The step that raised — every time, because of F1
— was the least important one (a handful of trailing frames). The steps
skipped were `h264_decoder.close()` (NVDEC surfaces), `audio.close()` (the
sound device) and `sink.close()` (the virtual camera).

So the failure that already broke video also leaked the GPU decoder and the
audio device, making the *next* session more likely to fail. Every step is
now individually guarded.

Regression test: `test_a_failing_sink_does_not_prevent_the_sink_being_closed`.

### F3 — HIGH — the sink writer thread died silently on any send error

**`pc_receiver/receiver.py`** · *fixed*

`_SinkWriter._run` had no exception handling. One raising `sink.send()` and
the thread simply exited — `submit()` went on filling a mailbox nobody read,
so **video froze for the rest of the session with nothing logged**, because
the exception died with the thread.

Now caught, reported once, counted thereafter, and recovery is logged.

Regression tests: `test_sink_writer_survives_a_raising_send`,
`test_sink_writer_recovers_when_the_sink_starts_working_again`.

### F4 — HIGH — a frozen peer wedged the receiver permanently

**`pc_receiver/receiver.py`** · *fixed*

There were **no socket timeouts anywhere** in `pc_receiver`. A TCP peer that
vanishes without closing — phone suspended, Wi-Fi dropped, lid shut, cable
pulled at the wrong instant — leaves a half-open socket that never errors and
never delivers. The receive loop parked in `recv()` forever.

Because the receiver accepts one connection at a time, it then never accepted
another: the phone reconnected, got no answer, and the only fix was
restarting the PC service. This is exactly the "suspensión del teléfono" /
"conexión congelada" case.

Fixed with a 10s idle timeout (`STREAM_IDLE_TIMEOUT_SECONDS`), applied to the
Hello read too — so a LAN client cannot hold the single accept slot open by
simply saying nothing.

Regression tests: `test_a_silent_sender_is_dropped_instead_of_blocking_forever`,
`test_a_client_that_never_sends_a_hello_is_dropped`.

### F5 — HIGH — the backlog check silently cleared that timeout

**`pc_receiver/protocol.py`** · *fixed*

`buffered_message_count()` ended with `setblocking(True)`, which **is**
`settimeout(None)`. So the obvious-looking restore removed whatever timeout
the caller had configured — permanently, from the first frame onwards. F4's
fix would have been switched off by the very next line of the receive loop.

Now saves and restores the actual timeout.

Regression test: `test_backlog_check_does_not_clear_the_socket_timeout`.

### F6 — HIGH — a connection reset escaped as a crash

**`pc_receiver/receiver.py`** · *fixed* · **1 occurrence in production**

`ConnectionResetError` is not a `ProtocolError`, so an RST — what a USB cable
pull, a killed sender or a router dropping the flow produces — escaped the
receive loop. `serve_forever` logged it as a crashed connection;
`serve_once` propagated it out of `main()` and killed the process.

Now treated as an ordinary disconnect. Note the ordering constraint: since
Python 3.10 `socket.timeout` *is* an `OSError`, so F4's handler must come
first or the two become indistinguishable.

Regression test: `test_an_abruptly_reset_connection_is_handled_as_a_disconnect`.

### F7 — HIGH — the null sinks were the fastest leak in the program

**`pc_receiver/sinks.py`, `audio_sinks.py`** · *fixed*

`NullSink` and `NullAudioSink` appended every frame/packet they were ever
given. Written as test doubles — but they are also `--sink null` and
`--audio-sink null` **on the command line**. At 1080p RGB that is 6MB a
frame: ~186 MB/s.

Caught by the soak harness within two minutes, and the arithmetic was exact:

```
REGRESSIONS: memory grew 778.1 MB/h
5621 audio packets x 1024 samples x 2ch x 2B = 23.0 MB   (measured: 23.2 MB)
```

Both are now bounded (256 recent items, plus a 64MB cap on the video one
because frame sizes vary by three orders of magnitude). Every existing test
still passes — they assert on a handful of frames.

Regression test: `test_null_sinks_are_constant_memory_under_a_long_stream`.

### F8 — HIGH — Android leaked a thread per streaming session

**`CameraStreamer.kt`** · *fixed*

`networkExecutor` was typed `Executor` (so `shutdown()` was not even
callable) and never shut down, with a **non-daemon** thread. `MainActivity`
builds a new `CameraStreamer` for every start/stop of streaming, so every
session left one behind alive for the life of the process. Fifty presses of
the record button, fifty parked threads and their stacks.

Now `ExecutorService`, daemon-threaded, `shutdown()` after teardown tasks are
queued so they still run.

### F9 — HIGH — Android had no connect timeout and no way out of a stalled write

**`StreamProtocol.kt`, `CameraStreamer.kt`** · *fixed*

Two halves:

- `Socket(host, port)` used the OS default connect timeout — tens of seconds
  to minutes on a network that black-holes packets. `ConnectionSupervisor`
  cannot poll its `stopped` flag while parked inside `connect()`, so stopping
  a stream appeared to hang. Now an explicit 4s timeout.

- **Java has no write timeout at all** — `SO_TIMEOUT` governs reads only. A
  blocking write to a peer that has stopped reading blocks forever once the
  send buffer fills, which is precisely what a suspended PC looks like. It
  wedged the one thread that owns the socket: video stopped, audio stopped,
  and *no exception was ever thrown*, so the reconnect logic never ran
  either. Only a stop/start recovered it.

  Fixed with a stall detector riding the existing per-frame GL callback (no
  extra thread): if a write has been outstanding past
  `SEND_STALL_TIMEOUT_MS`, the socket is closed from that thread, which makes
  the blocked write throw and puts the session back on the normal reconnect
  path.

### F10 — MEDIUM — control server is single-threaded with no timeout

**`control_server.py`** · *not fixed, see §7*

`http.server.HTTPServer` handles one request at a time and no socket timeout
is set. One stalled or half-open client blocks every control request
indefinitely — the same class of defect as F4, on the port the phone uses to
start services and fetch its token.

### F11 — MEDIUM — 10-bit/HDR pixel formats are unhandled

**`sinks.py`** · *not fixed, see §7*

`_PIXEL_FORMAT_BY_NAME` covers `nv12`/`yuv420p`/`yuvj420p`. With the HDR
setting on, an HEVC 10-bit stream decodes to `p010le`, which falls through to
`PixelFormat.RGB` and then computes the camera geometry down the non-planar
branch — wrong size, and uint16 data handed to a sink expecting uint8.

### F12 — LOW — A/V sync queue is bounded by count, not bytes

**`av_sync.py`** · *not fixed, see §7*

`max_queued_frames=30` is 30 × 12.4MB = **373MB** at 4K NV12. Steady state is
~4 frames (audio buffer depth × fps), so it is not reached in practice, but
the bound should be in bytes.

### F13 — LOW — no log rotation

**`control_server.py`** · *not fixed*

`receiver.log` and `control_server.log` are opened append-only forever. One
pipeline line every 2s is ~21k lines per 12-hour session. Measured 120KB
across a week here, so it is slow — but it is unbounded, on a service that
auto-starts at every login.

### F14 — CRITICAL — the USB watcher never noticed a dead tunnel

**`control_server.py`** · *fixed* · **observed live, cost a working session**

`watch_usb_devices` computed `pending = current - ready_serials`, so once a
serial succeeded it was never looked at again while the phone stayed
connected. That covers "device appeared" and "device disconnected" — but not
the case in between: **the tunnels dying while the phone stays connected.**

`adb reverse` bindings belong to the adb *server*, so anything that restarts
it drops every tunnel while `adb devices` still lists the phone as `device`.
The serial stayed marked ready, nothing was retried, and the phone could no
longer reach the PC at all.

Observed exactly that during this audit:

```
$ adb reverse --list
(empty)
$ netstat -ano | grep :8787
TCP  0.0.0.0:8787  LISTENING  9096      <- receiver up and idle
```

Receiver listening, phone streaming, app in the foreground, and every frame
going into the phone's own loopback. The last successful session in
`receiver.log` was hours earlier — 212 frames at a clean 30fps — with no
`connected:` line since.

Now the watcher asks adb what is actually forwarded each cycle and repairs
whatever is missing, so it self-heals. Verified end to end afterwards: a TCP
connect from the phone's loopback reached the PC receiver (`connected:` count
28 → 29).

Regression tests: `test_usb_watcher.py` (7 tests), including the exact
scenario — tunnels vanish under a still-connected phone.

### F15 — CRITICAL — PhoneCam's OBS sync crashes OBS

**`obs_sync.py`** · **FIXED — root cause found** · 5 crash dumps in one day

**Root cause: PhoneCam relied on OBS to refuse a request that OBS does not
refuse.**

The five dumps are two distinct signatures, and separating them is what
solved it:

| Time | Crashing call | Status |
|---|---|---|
| 14:45, 14:58, 14:59, 20:36 | `RequestHandler::GetCurrentProgramScene` | already fixed by commit `b825876` at **20:40** |
| 20:24 | `RequestHandler::SetVideoSettings` → `OBSBasic::ResetVideo` | **this fix** |

All four `GetCurrentProgramScene` crashes predate the 20:40 commit that
replaced it with `GetSceneList`, and there has been no crash dump since. That
half was already solved. (Note: the scene collection emptying at ~23:43 is
therefore *not* attributable to a crash — the last dump is three hours
earlier, and no dump exists for that event.)

The remaining one:

```
Unhandled exception: c0000005
Fault address: ...w32-pthreads.dll
  obs64.exe!OBSBasic::ResetVideo+0x67c
  obs-websocket.dll!RequestHandler::SetVideoSettings+0xd68
  obs-websocket.dll!RequestHandler::ProcessRequest+0x196
```

`SetVideoSettings` lands in `OBSBasic::ResetVideo`, which tears down and
rebuilds OBS's entire video pipeline. **OBS's own Settings dialog disables
the resolution and FPS fields while an output is running** — that is not a UI
nicety, it is why the operation is safe when the UI performs it. The pthreads
fault address is the tell: ResetVideo was recreating the graphics thread
while something still held the old pipeline.

And PhoneCam was that something. The receiver feeds OBS's virtual camera, and
the automatic sync fires `OBS_SETTLE_SECONDS` **into a live session** — so
the one moment it reshaped the pipeline was the one moment the pipeline was
guaranteed to be in use.

The call site carried this comment:

> *"SetVideoSettings always rejects while any output is running"*

That assumption is **false on OBS 32.2.1**. The request went straight
through.

**Fix:** a pre-flight check (`_active_output`) that asks OBS directly —
`GetVirtualCamStatus`, `GetRecordStatus`, `GetStreamStatus`, all read-only,
the same class of request as the `GetVideoSettings` this module has always
issued safely. If any output is live, the reset is skipped and logged.
Nothing is given up: OBS's own UI forbids exactly this. An obs-websocket
build that doesn't know these requests reports unknown and is not blocked.

Regression tests: 7 in `test_obs_sync.py`, including one per output kind, one
proving the reset still happens when nothing is running, and one proving the
harmless half of the sync still runs when the reset is skipped.

### F16 — HIGH — two watchers produced two of every service

**`PhoneCam_Service.vbs`, `control_server.py`** · *fixed*

Two things launch the lifecycle watcher — the Startup shortcut at login and
the installer's own "launch now" step — and it had no singleton guard. Each
watcher started its own control server, and each control server spawned its
own discovery/speed_test/receiver. Observed live: two control servers, two
discovery servers, two speed test servers, two receivers.

The reason a duplicate control server could even bind: `HTTPServer` sets
`allow_reuse_address`, and on Windows `SO_REUSEADDR` does **not** mean "reuse
a socket in TIME_WAIT" as it does on Unix — it means *two live processes may
bind the same port*. Proven directly:

```
primer bind en 8795: OK
segundo bind rechazado correctamente: OSError (winerror=10048)
confirmado: HTTPServer por defecto PERMITE dos binds  <- the cause
```

Fixed at both levels: the watcher takes a lock file (with a process count to
break stale locks after a kill), and `ControlServer` sets
`allow_reuse_address = False` so the port itself is the mutex — no PID file
to go stale. The duplicate now exits *before* spawning children:

```
[control] port 8790 is already in use ([WinError 10048] ...);
          another control server is running. Exiting rather than starting a duplicate.
```

While there, `ControlServer` also became a `ThreadingHTTPServer` with a 10s
handler timeout, closing **F10** — one slow `/obs-sync` used to freeze every
other request, which is exactly what `control_server.log` showed (a bare
`POST /obs-sync` as the final line). The lazy `ObsManager` init needed a lock
once the server became threaded.

### F17 — MEDIUM — a burst of connections was refused rather than queued

**`receiver.py`** · *fixed*

`server.listen(1)` meant a couple of stray connects filled the accept queue
and a legitimate phone got ECONNREFUSED. Measured in the stress battery: 10
rapid connects, **only 2 accepted**, port unreachable until the idle timeout
expired. Raised to `listen(8)` — a backlog, not concurrency; the receiver
still handles one session at a time. Re-measured: **9 accepted, recovers on
its own.**

OBS crashed with an access violation inside **obs-websocket.dll**:

```
Thread 1A34: Thread (pooled) (Crashed)
Unhandled exception: c0000005
  ucrtbase.dll!...
  obs-websocket.dll!...   (x4 frames)
  qt6core.dll!...
```

PhoneCam is the only client of obs-websocket on that machine. `receiver.log`
shows `obs_sync: resetting OBS canvas to 1920x1080@30fps` at 23:36:06,
followed by OBS restarting three times in the next eight minutes, ending with
a scene collection written **empty** — 0 scenes, 0 sources — which is what
makes OBS's "create new source" silently do nothing in every category.

The user lost their scene collection to this. It was recoverable only because
`Sin_Título.json.bak` still held the previous state.

This is the **third** recorded instance of this failure mode in the project's
history (`fix(obs): stop scene requests from crashing OBS during startup`,
`fix(obs): stop crashing OBS - the previous fix was built on the wrong
cause`). Both previous fixes narrowed *when* requests are sent; neither
stopped them being sent. `SetVideoSettings` reinitialises OBS's whole video
pipeline while it runs.

**Recommendation: turn "Sync OBS settings" off** until this path is either
proven safe on obs-websocket 5.x or reduced to read-only requests. The
feature saves the user one manual canvas change; it has now cost them their
scene collection once.

---

## 3. Benchmarks

`python -m pc_receiver.tools.bench` · RTX-class GPU, NVDEC active.

| Stage | 720p | 1080p | 4K | Budget @60fps |
|---|---|---|---|---|
| decode (native) mean | 1.96ms | 3.04ms | 8.18ms | 16.7ms |
| decode (native) **p95** | 5.81ms | 11.49ms | **33.55ms** | 16.7ms |
| decode (rgb) mean | 1.99ms | 3.59ms | 9.07ms | |
| `pack_chunk` | 0.002ms | 0.001ms | 0.000ms | |
| `unpack_chunk` | 0.002ms | 0.001ms | 0.001ms | |

| Audio stage | Cost | Budget (one 1024-sample packet) |
|---|---|---|
| AAC decode | 0.029ms | 21.3ms |
| ADTS wrap | 0.001ms | |
| PCM decode | 0.001ms | |
| A/V sync submit+due | 0.001ms | 16.7ms |

**Where the milliseconds go:**

- **4K60 is the only real bottleneck.** Mean decode fits the budget at 49%,
  but **p95 is 33.5ms — twice the 16.7ms budget.** The existing backlog-skip
  logic is not optional at 4K60; it is what keeps that from accumulating.
  1080p60 is comfortable (p95 11.5ms, 69% of budget).
- **`native` beats `rgb` by 10–14%** at 4K, confirming that design choice.
- **The entire audio + sync + framing addition costs ~0.03ms/frame — under
  0.2% of a 60fps budget.** The 9-byte chunk header is unmeasurable. Sharing
  one socket cost nothing.

---

## 4. Soak

`python -m pc_receiver.tools.soak --minutes N [--audio]` — runs the real
receiver in-process against the real demo sender, sampling RSS, handles,
threads and throughput once a second to CSV, and exits non-zero on
regression.

### The measurement itself was wrong first

The harness originally reported `last_rss - first_rss` scaled to an hour. On
a process whose RSS sawtooths 65MB between GC cycles that is a coin flip on
where the endpoints land, and it lied in **both** directions on the same
healthy system — **-161.7 MB/h** on one run and **+56.6 MB/h** on the next,
while the process was allocating steadily and releasing everything.

It now measures the slope of the per-minute *minimum*. Whatever a collector
defers it returns to a baseline; if that baseline climbs, memory is genuinely
retained. Re-reading the same three runs with the corrected metric:

| Run | RSS floor trend | Floor first → last |
|---|---|---|
| Before the null-sink fix | **+702 MB/h** | 62.2 → 73.9 MB in 2 min |
| After (12 min) | +3.9 MB/h | 63.0 → 63.6 MB |
| After all fixes (15 min) | +3.9 MB/h | 62.7 → 63.5 MB over 14 min |
| Re-run with corrected gate (6 min) | +6.3 MB/h | 63.0 → 63.5 MB |

The real leak is unmistakable and gone. The residual ~4-6 MB/h is 0.5-0.8MB
of floor movement across the run — at the resolution limit of a soak this
short, and **not** something a 15-minute run can distinguish from allocator
arena growth. Separating it needs hours, which is the honest limitation.

**12-minute run with audio**, after the F7 fix:

```
  duration            10.8 min (post warm-up)
  frames / audio      5391 / 33746
  decode failures     0
  RSS                 116.4 -> 87.4 MB (-29.1 MB, -161.7 MB/h)
  RSS peak            125.0 MB
  handles             381 -> 377 (-4)
  threads             55 -> 53 (-2)
  fps 1st/2nd half    7.5 / 7.5 (-0.0%)
  no regressions detected
```

Same harness before the fix: `REGRESSIONS: memory grew 778.1 MB/h`.

RSS oscillates 63–125MB (bounded retention plus GC sawtooth) with no trend.
The 7.5fps figure is the *sender* — synthetic 1080p generation plus JPEG
encode on one core — not a receiver limit; the benchmark above is the
receiver's real capacity.

### The 12-hour run

Launched detached, writing to `pc_receiver/logs/soak/soak_12h.csv`:

```bash
python -m pc_receiver.tools.soak --minutes 720 --audio --port 8910 \
       --csv pc_receiver/logs/soak/soak_12h.csv
python -m pc_receiver.tools.analyse_soak pc_receiver/logs/soak/soak_12h.csv
```

**One continuous run rather than four separate ones.** Four runs of 1/3/6/12
hours would each test a fresh process, which is a test of startup; only a
single long session can show a leak, a drift or a decay that needs hours to
appear. `analyse_soak` truncates the same CSV at 1h/3h/6h/12h, so the short
horizons come out of the long run for free — and it reports "not reached yet"
rather than inventing a trend from too few points.

Two changes were needed before committing 12 hours to it:

- **The CSV is now streamed row by row, flushed once a minute**, instead of
  being held in memory and written at the end. A twelve-hour run that dies at
  hour eleven must still leave eleven hours of evidence — and on a soak, the
  run that dies *is* the interesting one.
- The regression gate measures the RSS **floor** (see above), because the
  previous endpoint metric would have reported a verdict uncorrelated with
  reality.

It uses port 8910 and a null sink, so it does not touch the live services,
OBS, or the phone: `demo_sender` generates synthetic video and a test tone
itself. Verified running alongside the live receiver (8787), control server
(8790) and the USB tunnels without interference.

**Result: 12.00h, clean.**

```
42638 samples, 12.00h elapsed, 323557 frames, 2024966 audio packets

  1h      rss_floor   -5.8 MB/h (35.0 -> 17.0 MB)   handles -1.46/h   threads -0.49/h   fps 7.5->7.5 (+0.0%)
  3h      rss_floor   -0.1 MB/h (18.6 -> 17.2 MB)   handles +0.00/h   threads -0.01/h   fps 7.5->7.5 (+0.0%)
  6h      rss_floor   +0.1 MB/h (17.2 -> 17.4 MB)   handles +0.00/h   threads +0.00/h   fps 7.5->7.5 (-0.0%)
  12h     rss_floor   +0.0 MB/h (17.0 -> 17.1 MB)   handles +0.00/h   threads +0.00/h   fps 7.5->7.5 (+0.0%)
```

The 1h figure is warm-up settling, not a trend: RSS falls from its initial
allocation to a steady state and stays there. From 3h onwards the floor is
flat to within 0.1 MB/h, and **the floor moved 0.1 MB across the whole 12
hours** (16.9 MB in the first half, 17.0 MB in the second). Handles and
threads are flat to two decimal places.

Integrity checks on the same CSV, because a run that silently stopped for two
hours would also show a flat floor:

| Check | Result |
|---|---|
| Sampling gaps > 5s | **0** — no suspension, no stall |
| Windows at 0 fps | **0** — video never stopped |
| fps range after warm-up | 6.8 – 8.0, no decay |
| Counter regressions (frames, audio) | **0** |
| Sustained rates | 7.49 fps video, 46.9 packets/s audio |

**Not captured for this run:** the decode-failure count. The scheduled task
did not redirect stdout, so the final summary (which reports it) was lost;
the CSV does not carry that column. Every earlier run reported 0, but that is
not evidence about this one.

### The first attempt died at 4h21m

Worth recording because the cause was not the code. The first 12h attempt was
launched with `Start-Process` from the tool session's own process tree, and
stopped after 4h21m with **no trace in any Windows event log** — no sleep
event, no unexpected shutdown, no crash report, nothing from Defender. A
`TerminateProcess` on a job object leaves no record, which is exactly what a
parent session going away produces. The Claude servers went down around that
time, which fits.

The rerun therefore went through the Windows Task Scheduler, which creates
the process with no parent to inherit that fate, and with
`-AllowStartIfOnBatteries -DontStopIfGoingOnBatteries`.

The partial run's data is kept as `soak_12h_intento1_murio_a_las_4h21m.csv`
and was itself healthy: floor stable or falling, handles and threads flat,
117,631 frames.

---

## 5b. Stress battery (live services)

`python -m pc_receiver.tools.stress` — drives the *installed* services on
this machine, not doubles, so it covers what no unit test can: the real USB
watcher, the real singleton guard, the real listener.

| Scenario | Result |
|---|---|
| 25 rapid reconnects | **PASS** — 25/25 accepted |
| 10 half-open clients abandoned with RST | **PASS** — 9 accepted, listener recovers unaided |
| Garbage payloads (HTTP verb, lying length, nulls, bad JSON) | **PASS** — listener survives all |
| Second control server started | **PASS** — refuses to bind, exits before spawning children |
| One owner per port (8787/8788/8789/8790) | **PASS** |
| `adb kill-server` mid-session | **PASS** — *tunnels rebuilt automatically* |

The adb scenario is the one that matters most: it reproduces F14 exactly —
the failure that left the phone streaming into its own loopback — and
confirms the watcher now repairs it without human help.

Deliberately excluded: killing OBS. Restarting OBS is destructive to a scene
collection if it is mid-save, and this project has already cost one. That
scenario needs a human and a backup.

## 5. Fault injection

`pc_receiver/tests/test_fault_injection.py`, all passing:

| Scenario | Result |
|---|---|
| 10 back-to-back reconnects | listener accepts all 10, sessions independent |
| Connect then vanish | later real session still served |
| Sink creation fails (OBS shut) ×2 | listener survives, keeps accepting |
| Length prefix > 32MB | rejected without allocating |
| Hello that is not JSON | rejected cleanly |
| Frame dribbled in 97-byte chunks | reassembled, decodes, 0 failures |
| Connection reset (RST) | handled as disconnect, sink closed |
| Empty frame | counted as a decode failure, stream continues |

Plus `test_receiver_robustness.py` (12 tests) for F1–F7.

---

## 6. Simplifications

- Removed `CountingSink` from the soak harness — dead once `NullSink` became
  bounded.
- Extracted `_log_skipped` / `_drain_to` / `_flush_to` so the teardown block
  is a guarded list of steps rather than a sequence with implicit ordering
  hazards.
- `sinks.py` no longer imports `List` (unused after the deque change).

---

## 7. What was NOT verified

Stated plainly, because the request was explicit about this.

**Not run at all:**

- **OBS interaction.** OBS is installed here and `pyvirtualcam` opens its
  Virtual Camera, but no test drove OBS open/closed, scene changes, or the
  obs-websocket sync path. F1's fix removes the *cause* of the 13 recorded
  failures; it has not been re-observed against live OBS.
- **Windows 10.** This machine is Windows 11 (build 26200). No Win10 testing.
- **Multiple monitors, other webcams, other audio devices.** Untested. Device
  *selection* logic is unit-tested against a synthetic Windows device table.
- **Multi-hour thermal / battery / CPU / GPU behaviour** on either side.

**Blocked by hardware access:**

The phone (SM-S918B, Android 16) is connected via adb, but its **screen is
locked** (`mWakefulness=Dozing`). I did not unlock it and did not install
anything on it. Therefore:

- `AudioRecord` capture, the MediaCodec AAC encoder, CameraX/Camera2, the GL
  renderer and the video encoder have **never been executed**. Android 16
  also blocks background microphone access, so a locked-screen instrumented
  test could not validate capture even if one were installed.
- No on-device CPU/GPU/memory/temperature/power profiling.
- The Android fixes (F8, F9) **compile and pass unit tests but have not been
  run on hardware.** F9's stall detector in particular is exactly the kind of
  logic that wants a real stalled network to prove it.

Read-only device inspection *was* done: this device's `media_codecs` tables
advertise AAC encode at 8000–192000 Hz and up to 8 channels, consistent with
what `AudioEncoder.kt` requests.

**Partially audited:**

`MainActivity.kt` (1785 lines), `SettingsActivity.kt` (797),
`DeviceCapabilities.kt` (546), `EncoderSurfaceRenderer.kt` (334) and
`Camera2CaptureSource.kt` (278) were scanned and lint-checked, not read line
by line. The audit concentrated on the streaming path. **"Todo el código" is
therefore an overstatement of what §2 covers.**

**Lint** reports 23 `RestrictedApi` and 11 `NewApi` errors. The one in `main`
(`Camera2Capabilities.kt:63`, an API-29 constructor under `minSdk 26`) is
already defended by a deliberate `catch (e: Throwable)` with a comment
explaining it — a lint false positive. `AppToast.kt` holds a static `View`
(`StaticFieldLeak`); bounded to one toast, not chased. `activity_main.xml:55`
has an unconstrained view (`MissingConstraints`) that will jump to (0,0).

---

## 8. Recommended next

Ordered by expected value.

1. **Run the long soaks** — `--minutes 720 --audio`. Everything needed is in
   place; it just needs wall-clock time on an idle machine.
2. **Unlock the phone and validate the audio path end to end.** It is the
   largest untested surface in the project and the newest code in it.
3. **Fix F10** (`ThreadingHTTPServer` + a handler timeout) — same defect class
   as F4, on the port the phone depends on.
4. **Fix F11** before shipping HDR, or gate HDR off until the 10-bit path has
   a real pixel-format mapping.
5. **Bound A/V sync by bytes (F12) and add log rotation (F13).**
6. **Re-observe F1 against live OBS** — a 30-minute session with the virtual
   camera as the sink, confirming a clean teardown, would close the loop on
   the headline finding with direct evidence rather than a reproduction.
