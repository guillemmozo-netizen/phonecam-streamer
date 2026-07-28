"""The OBS sync, driven against a fake obs-websocket.

obs_sync had no tests: it opened a real WebSocket and every path went through
it. That is how it shipped two separate crashes of OBS itself - an access
violation in GetCurrentProgramScene, then one in SetVideoSettings ->
obs_reset_video -> pthread_create with six resets logged in 950ms. Both are
pinned here.
"""
import json
import threading

import pytest

from pc_receiver import obs_sync


class FakeWs:
    """Records requests and answers them the way obs-websocket would."""

    def __init__(self, video_settings=None, fail_set=False):
        self.requests: list[tuple[str, dict]] = []
        self.closed = False
        self._replies: list[str] = []
        self._video = video_settings or {
            "baseWidth": 1280, "baseHeight": 720,
            "outputWidth": 1280, "outputHeight": 720,
            "fpsNumerator": 30, "fpsDenominator": 1,
        }
        self._fail_set = fail_set

    def send(self, raw):
        msg = json.loads(raw)["d"]
        kind = msg["requestType"]
        self.requests.append((kind, msg.get("requestData") or {}))
        if kind == "GetVideoSettings":
            data, ok = self._video, True
        elif kind == "SetVideoSettings":
            data, ok = {}, not self._fail_set
            if ok:
                d = msg["requestData"]
                self._video = {
                    "baseWidth": d["baseWidth"], "baseHeight": d["baseHeight"],
                    "outputWidth": d["outputWidth"], "outputHeight": d["outputHeight"],
                    "fpsNumerator": d["fpsNumerator"], "fpsDenominator": d["fpsDenominator"],
                }
        elif kind == "GetSceneList":
            data, ok = {"currentProgramSceneName": "Scene", "scenes": [{"sceneName": "Scene"}]}, True
        elif kind == "GetSceneItemList":
            data, ok = {"sceneItems": [{"sourceName": "OBS Virtual Camera", "sceneItemId": 1}]}, True
        else:
            data, ok = {}, True
        self._replies.append(json.dumps({
            "d": {"requestStatus": {"result": ok}, "responseData": data},
        }))

    def recv(self):
        return self._replies.pop(0)

    def close(self):
        self.closed = True

    def kinds(self):
        return [k for k, _ in self.requests]


@pytest.fixture(autouse=True)
def reset_module_state(monkeypatch):
    """The coalescing guard is module state, so each test starts clean."""
    obs_sync._last_reset = None
    obs_sync._last_reset_at = 0.0
    monkeypatch.setattr(obs_sync, "_read_obs_websocket_config",
                        lambda: {"server_enabled": True, "server_port": 4455,
                                 "server_password": "pw"})
    yield
    obs_sync._last_reset = None
    obs_sync._last_reset_at = 0.0


def sync(ws, **kwargs):
    """Calls the real sync against a fake connection, with a frozen clock
    unless the test wants otherwise."""
    kwargs.setdefault("width", 1920)
    kwargs.setdefault("height", 1080)
    kwargs.setdefault("fps", 60)
    kwargs.setdefault("clock", lambda: 1000.0)
    kwargs.setdefault("sleeper", lambda _s: None)
    return obs_sync.sync_video_settings(connector=lambda port, pw: ws, **kwargs)


# ---------- the reset that crashed OBS ----------

def test_canvas_is_reset_when_it_does_not_match():
    ws = FakeWs()
    assert sync(ws) is True
    assert "SetVideoSettings" in ws.kinds()
    assert ws.requests[-1][0] != "GetVideoSettings"


def test_no_reset_when_obs_is_already_the_right_shape():
    """SetVideoSettings is a full obs_reset_video, not a setting write. If OBS
    is already correct the whole dangerous call is skipped."""
    ws = FakeWs(video_settings={
        "baseWidth": 1920, "baseHeight": 1080,
        "outputWidth": 1920, "outputHeight": 1080,
        "fpsNumerator": 60, "fpsDenominator": 1,
    })
    assert sync(ws) is True
    assert "SetVideoSettings" not in ws.kinds()


def test_identical_repeat_within_the_window_does_not_reset_twice():
    """Six resets in 950ms preceded the crash; a repeat of one that just
    happened buys nothing."""
    now = [1000.0]
    ws1, ws2 = FakeWs(), FakeWs()
    sync(ws1, clock=lambda: now[0])
    now[0] += 1.0
    sync(ws2, clock=lambda: now[0])
    assert "SetVideoSettings" in ws1.kinds()
    assert "SetVideoSettings" not in ws2.kinds()


def test_the_same_request_applies_again_once_the_window_passes():
    now = [1000.0]
    ws1, ws2 = FakeWs(), FakeWs()
    sync(ws1, clock=lambda: now[0])
    now[0] += obs_sync._MIN_SECONDS_BETWEEN_RESETS + 1
    sync(ws2, clock=lambda: now[0])
    assert "SetVideoSettings" in ws2.kinds()


def test_a_genuine_resolution_change_is_never_coalesced():
    """The guard skips repeats, not changes - a user switching resolution must
    still take effect immediately."""
    now = [1000.0]
    ws1, ws2 = FakeWs(), FakeWs()
    sync(ws1, width=1920, height=1080, fps=60, clock=lambda: now[0])
    now[0] += 0.1
    sync(ws2, width=3840, height=2160, fps=60, clock=lambda: now[0])
    assert "SetVideoSettings" in ws2.kinds()


def test_a_rejected_reset_is_not_remembered_as_applied():
    """OBS rejects SetVideoSettings while any output is active. Recording that
    as done would suppress the retry that eventually has to work."""
    now = [1000.0]
    rejected, accepted = FakeWs(fail_set=True), FakeWs()
    sync(rejected, clock=lambda: now[0])
    now[0] += 0.1
    sync(accepted, clock=lambda: now[0])
    assert "SetVideoSettings" in accepted.kinds()


def test_concurrent_syncs_never_overlap():
    """Two overlapping obs_reset_video calls is what crashed OBS - the log had
    two resets in the same millisecond."""
    overlapping = []
    active = []
    lock = threading.Lock()

    class SlowWs(FakeWs):
        def send(self, raw):
            if json.loads(raw)["d"]["requestType"] == "SetVideoSettings":
                with lock:
                    active.append(1)
                    if len(active) > 1:
                        overlapping.append(1)
                super().send(raw)
                with lock:
                    active.pop()
            else:
                super().send(raw)

    threads = [
        threading.Thread(target=lambda i=i: sync(SlowWs(), width=1920 + i * 2, height=1080))
        for i in range(8)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10)
    assert overlapping == []


# ---------- dimension sanity ----------

@pytest.mark.parametrize("w,h,fps", [
    (0, 0, 60),
    (64, 48, 60),        # the canvas OBS was left with right before the crash
    (1920, 1080, 0),
    (-1920, 1080, 60),
    (99999, 1080, 60),
    (1920, 1080, 100000),
])
def test_absurd_dimensions_never_reach_obs(w, h, fps):
    """OBS clamps out-of-range values instead of refusing them, so a bad number
    silently reshapes the user's canvas."""
    ws = FakeWs()
    assert sync(ws, width=w, height=h, fps=fps) is False
    assert ws.kinds() == []


# ---------- startup window ----------

def test_the_hello_path_waits_before_touching_obs():
    slept = []
    ws = FakeWs()
    sync(ws, settle_seconds=20.0, sleeper=slept.append)
    assert slept == [20.0]


def test_no_wait_when_the_caller_knows_obs_is_up():
    slept = []
    sync(FakeWs(), sleeper=slept.append)
    assert slept == []


def test_scene_requests_stay_off_by_default():
    ws = FakeWs()
    sync(ws)
    assert "GetSceneList" not in ws.kinds()


def test_scene_fit_runs_only_when_explicitly_allowed():
    ws = FakeWs()
    sync(ws, allow_scene_requests=True)
    assert "GetSceneList" in ws.kinds()
    assert "SetSceneItemTransform" in ws.kinds()


def test_the_request_that_crashes_obs_is_never_sent():
    """Three crash reports name GetCurrentProgramScene, the last against an OBS
    that had been up and healthy for a minute. GetSceneList carries the same
    field and was verified safe on the same build."""
    ws = FakeWs()
    sync(ws, allow_scene_requests=True)
    assert "GetCurrentProgramScene" not in ws.kinds()


# ---------- failure paths ----------

def test_a_connection_failure_is_not_raised_at_the_caller():
    def refuse(port, pw):
        raise ConnectionRefusedError("nothing listening")

    assert obs_sync.sync_video_settings(
        1920, 1080, 60, connector=refuse, sleeper=lambda _s: None) is False


def test_websocket_disabled_short_circuits(monkeypatch):
    monkeypatch.setattr(obs_sync, "_read_obs_websocket_config",
                        lambda: {"server_enabled": False})
    ws = FakeWs()
    assert sync(ws) is False
    assert ws.kinds() == []


def test_a_mid_sync_failure_still_closes_the_connection():
    class Exploding(FakeWs):
        def send(self, raw):
            raise RuntimeError("connection dropped")

    ws = Exploding()
    assert sync(ws) is False
    assert ws.closed is True


def test_the_lock_is_released_after_a_failure():
    """A sync that raises must not wedge every later sync behind a held lock."""
    class Exploding(FakeWs):
        def send(self, raw):
            raise RuntimeError("boom")

    sync(Exploding())
    assert obs_sync._SYNC_LOCK.locked() is False
    ws = FakeWs()
    assert sync(ws) is True


def test_profile_parameters_are_sent_when_given():
    ws = FakeWs()
    sync(ws, bitrate_bps=12_000_000, audio_bitrate_bps=160_000, sample_rate=48_000)
    params = [d["parameterName"] for k, d in ws.requests if k == "SetProfileParameter"]
    assert params == ["VBitrate", "ABitrate", "SampleRate"]


def test_profile_parameters_are_skipped_when_zero():
    ws = FakeWs()
    sync(ws)
    assert "SetProfileParameter" not in ws.kinds()


# ---------- liveness: a crashed OBS still accepts connections ----------

class FakeSocketWs:
    """Speaks the raw handshake open_connection expects, not the request-level
    protocol FakeWs fakes."""

    def __init__(self, answers_requests=True):
        self._answers = answers_requests
        self._queue = [json.dumps({"op": 0, "d": {}}), json.dumps({"op": 2, "d": {}})]
        self.closed = False

    def send(self, raw):
        msg = json.loads(raw)
        if msg.get("op") == 6:
            if not self._answers:
                # What a crashed OBS does: the WebSocket thread takes the
                # request, nothing ever answers it, the recv times out.
                raise TimeoutError("timed out")
            self._queue.append(json.dumps({
                "d": {"requestStatus": {"result": True}, "responseData": {"obsVersion": "32.2.1"}},
            }))

    def recv(self):
        return self._queue.pop(0)

    def close(self):
        self.closed = True


def test_a_healthy_obs_connection_is_returned(monkeypatch):
    ws = FakeSocketWs()
    monkeypatch.setitem(__import__("sys").modules, "websocket",
                        type("m", (), {"create_connection": staticmethod(lambda *a, **k: ws)}))
    assert obs_sync.open_connection(4455, "pw") is ws


def test_a_crashed_obs_that_still_accepts_connections_is_rejected(monkeypatch):
    """The zombie case: process alive around its crash dialog, WebSocket thread
    still completing identify, frontend dead. The manager used to call this
    CONNECTED and then vouch for it."""
    ws = FakeSocketWs(answers_requests=False)
    monkeypatch.setitem(__import__("sys").modules, "websocket",
                        type("m", (), {"create_connection": staticmethod(lambda *a, **k: ws)}))
    with pytest.raises(Exception):
        obs_sync.open_connection(4455, "pw")
    assert ws.closed is True
