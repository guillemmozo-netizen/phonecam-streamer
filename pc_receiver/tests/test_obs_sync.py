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

    # The webcam is listed first on purpose. The old rule took the first item
    # with "cam" anywhere in its name, so this ordering is what makes the
    # exact-match tests below a real regression pin rather than a coincidence.
    DEFAULT_SCENE_ITEMS = [
        {"sourceName": "Logitech Webcam", "sceneItemId": 1},
        {"sourceName": "PhoneCam", "sceneItemId": 2},
    ]

    # What OBS offers in a capture source's device dropdown. The webcam is
    # first again, for the same reason as above: picking the virtual camera
    # must be a match, not "whatever came first".
    DEFAULT_DEVICES = [
        {"itemName": "Logitech HD Webcam C920", "itemValue": "usb#vid_046d"},
        {"itemName": "OBS Virtual Camera", "itemValue": "root#image#0000#{860bb310}"},
    ]

    def __init__(self, video_settings=None, fail_set=False, scene_items=None,
                 inputs=None, input_kinds=None, devices=None, fail_create=False,
                 events=False):
        self.requests: list[tuple[str, dict]] = []
        self.closed = False
        self._replies: list[str] = []
        self._video = video_settings or {
            "baseWidth": 1280, "baseHeight": 720,
            "outputWidth": 1280, "outputHeight": 720,
            "fpsNumerator": 30, "fpsDenominator": 1,
        }
        self._fail_set = fail_set
        self._scene_items = list(
            self.DEFAULT_SCENE_ITEMS if scene_items is None else scene_items
        )
        self._inputs = list(inputs or [])
        self._input_kinds = (
            ["dshow_input", "color_source_v3"] if input_kinds is None else list(input_kinds)
        )
        self._devices = self.DEFAULT_DEVICES if devices is None else list(devices)
        self._fail_create = fail_create
        # Real obs-websocket pushes events down the same socket as responses.
        # Off by default only to keep the other tests' transcripts readable —
        # see test_events_interleaved_with_responses_never_desync_the_dialogue.
        self._events = events
        # OBS refuses to reshape its video pipeline while an output runs, so
        # the canvas change is deferred — the state the "bigger than the
        # frame" bug lived in. Settable per test.
        self.outputs_active = False

    def _add_scene_item(self, source_name):
        item_id = max((i["sceneItemId"] for i in self._scene_items), default=0) + 1
        self._scene_items.append({"sourceName": source_name, "sceneItemId": item_id})
        return item_id

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
            data, ok = {"sceneItems": list(self._scene_items)}, True
        elif kind in ("GetVirtualCamStatus", "GetRecordStatus", "GetStreamStatus"):
            data, ok = {"outputActive": self.outputs_active}, True
        elif kind == "GetInputList":
            data, ok = {"inputs": self._inputs}, True
        elif kind == "GetInputKindList":
            data, ok = {"inputKinds": self._input_kinds}, True
        elif kind in ("CreateInput", "CreateSceneItem"):
            ok = not self._fail_create
            data = {}
            if ok:
                d = msg["requestData"]
                name = d.get("inputName") or d.get("sourceName")
                data = {"sceneItemId": self._add_scene_item(name)}
                if kind == "CreateInput":
                    self._inputs.append({"inputName": name})
        elif kind == "GetInputPropertiesListPropertyItems":
            data, ok = {"propertyItems": self._devices}, True
        else:
            data, ok = {}, True
        if self._events:
            # op 5 is an event. OBS emits these constantly (a source going
            # active, a scene item being selected); one landing between a
            # request and its answer is what silently desynced every later
            # request by one message.
            self._replies.append(json.dumps({
                "op": 5, "d": {"eventType": "InputActiveStateChanged"},
            }))
        self._replies.append(json.dumps({
            "op": 7,
            "d": {
                "requestId": msg.get("requestId"),
                "requestStatus": {"result": ok},
                "responseData": data,
            },
        }))

    def recv(self):
        return self._replies.pop(0)

    def close(self):
        self.closed = True

    def kinds(self):
        return [k for k, _ in self.requests]


@pytest.fixture(autouse=True)
def reset_module_state(monkeypatch, tmp_path):
    """The coalescing guard is module state, so each test starts clean."""
    obs_sync._last_reset = None
    obs_sync._last_reset_at = 0.0
    monkeypatch.setattr(obs_sync, "_read_obs_websocket_config",
                        lambda: {"server_enabled": True, "server_port": 4455,
                                 "server_password": "pw"})
    # The pending store is a real file under %LOCALAPPDATA%. Redirected per
    # test: without this the suite writes to (and deletes from) the developer's
    # own PhoneCam state, and tests leak deferred changes into each other -
    # both happened, and the leak was invisible because a later passing test
    # happened to clear the file again.
    monkeypatch.setattr(obs_sync, "_PENDING_PATH", str(tmp_path / "obs_pending.json"))
    # Scene-item matching is exact, and the name is env-overridable — so a
    # developer who set PHONECAM_OBS_SOURCE for their own OBS must not change
    # what these tests assert.
    monkeypatch.delenv("PHONECAM_OBS_SOURCE", raising=False)
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


# ---------- which source may be reshaped, and which may never be ----------

def _transforms(ws):
    return [data for kind, data in ws.requests if kind == "SetSceneItemTransform"]


def test_only_phonecams_own_source_is_reshaped():
    """The webcam is first in the scene and its name contains "cam" — the old
    substring rule reshaped it. Nothing but the exact name may be touched."""
    ws = FakeWs()
    sync(ws, allow_scene_requests=True)
    assert [t["sceneItemId"] for t in _transforms(ws)] == [2]


@pytest.mark.parametrize("name", [
    "Logitech Webcam",       # a real webcam
    "Elgato Cam Link 4K",    # a capture card
    "DroidCam Source",       # a competing phone streamer
    "OBS Virtual Camera",    # the backend PhoneCam itself rides on
    "Camera 2",              # anything at all with "cam" in it
    "PhoneCam overlay",      # a near miss: exact means exact, not "starts with"
    "My PhoneCam",           # and not "ends with" either
])
def test_a_source_that_is_not_ours_is_never_touched(name):
    """The invariant is about the FOREIGN item, not about doing nothing.

    Since the missing source is now created, a transform does get sent — to
    the item this module made for itself. What must never happen, and is what
    this pins, is that transform landing on the user's webcam/capture card.
    """
    ws = FakeWs(scene_items=[{"sourceName": name, "sceneItemId": 7}])
    sync(ws, allow_scene_requests=True)
    assert 7 not in [t["sceneItemId"] for t in _transforms(ws)]
    # Nor may the foreign source be renamed, re-pointed or reconfigured.
    assert [d for k, d in ws.requests if k == "SetInputSettings"
            and d.get("inputName") == name] == []


def test_a_missing_source_is_created_and_then_fitted():
    """The setup step that used to be the user's job.

    The fit is name-exact so it can never touch a webcam — which made "name it
    exactly FrameCast" a manual step. Creating it here removes the step
    without relaxing the rule.
    """
    ws = FakeWs(scene_items=[{"sourceName": "Webcam", "sceneItemId": 1}])
    sync(ws, width=1080, height=1920, allow_scene_requests=True)

    created = [d for k, d in ws.requests if k == "CreateInput"]
    assert len(created) == 1
    assert created[0]["inputName"] == "FrameCast"
    assert created[0]["sceneName"] == "Scene"
    assert created[0]["inputKind"] == "dshow_input"

    # And the brand-new item is the one fitted — the webcam is never touched.
    fitted = _transforms(ws)
    assert len(fitted) == 1
    assert fitted[0]["sceneItemId"] == 2
    assert fitted[0]["sceneItemTransform"]["boundsWidth"] == 1080.0


def test_the_new_source_is_pointed_at_the_obs_virtual_camera():
    """A source with the right name but no device is a black rectangle. The
    device value is a machine-specific moniker, so it has to be matched by
    name out of OBS's own list."""
    ws = FakeWs(scene_items=[])
    sync(ws, allow_scene_requests=True)

    settings = [d for k, d in ws.requests if k == "SetInputSettings"]
    assert len(settings) == 1
    assert settings[0]["inputName"] == "FrameCast"
    assert settings[0]["inputSettings"]["video_device_id"] == "root#image#0000#{860bb310}"


def test_an_input_that_already_exists_elsewhere_is_added_not_duplicated():
    """Input names are global in OBS, so CreateInput would fail outright on a
    name conflict — the source has to be added to this scene instead."""
    ws = FakeWs(scene_items=[], inputs=[{"inputName": "FrameCast"}])
    sync(ws, allow_scene_requests=True)

    assert "CreateInput" not in ws.kinds()
    added = [d for k, d in ws.requests if k == "CreateSceneItem"]
    assert len(added) == 1
    assert added[0]["sourceName"] == "FrameCast"
    assert len(_transforms(ws)) == 1


def test_an_existing_source_is_never_recreated():
    """The overwhelmingly common path: it is already there, so nothing is
    created and the sync is exactly what it always was."""
    ws = FakeWs()
    sync(ws, allow_scene_requests=True)
    assert "CreateInput" not in ws.kinds()
    assert "CreateSceneItem" not in ws.kinds()


def test_a_source_that_cannot_be_created_changes_nothing_and_says_so(caplog):
    ws = FakeWs(scene_items=[{"sourceName": "Webcam", "sceneItemId": 1}],
                fail_create=True)
    with caplog.at_level("WARNING"):
        assert sync(ws, allow_scene_requests=True) is True
    assert _transforms(ws) == []
    assert "could not be created" in caplog.text


def test_creation_is_skipped_when_obs_has_no_capture_input_kind(caplog):
    """Better to say so than to create a source of some unrelated kind."""
    ws = FakeWs(scene_items=[], input_kinds=["color_source_v3"])
    with caplog.at_level("WARNING"):
        sync(ws, allow_scene_requests=True)
    assert "CreateInput" not in ws.kinds()
    assert _transforms(ws) == []


def test_a_missing_virtual_camera_device_still_leaves_a_usable_source(caplog):
    """No device found: the source is still created with the right name, so
    the fit works and the user only has to pick a device."""
    ws = FakeWs(scene_items=[], devices=[{"itemName": "Logitech", "itemValue": "usb#1"}])
    with caplog.at_level("WARNING"):
        sync(ws, allow_scene_requests=True)
    assert "CreateInput" in ws.kinds()
    assert [d for k, d in ws.requests if k == "SetInputSettings"] == []
    assert len(_transforms(ws)) == 1
    assert "no OBS Virtual Camera device" in caplog.text


def test_the_source_is_fitted_to_the_canvas_in_effect_not_the_one_requested():
    """The "bigger than the frame and shifted left" report.

    While an output is running the canvas reset is deferred — and that is the
    normal state once OBS's own virtual camera is started, not a rare one.
    Sizing the scene item for the canvas we asked for, while the canvas is
    still the old one, hangs the source off the edges.
    """
    ws = FakeWs(video_settings={
        "baseWidth": 1920, "baseHeight": 1080,
        "outputWidth": 1920, "outputHeight": 1080,
        "fpsNumerator": 60, "fpsDenominator": 1,
    })
    ws.outputs_active = True   # OBS refuses the reshape; the change is deferred

    sync(ws, width=1080, height=1920, fps=60, allow_scene_requests=True)

    assert "SetVideoSettings" not in ws.kinds()      # correctly deferred
    fitted = _transforms(ws)[0]["sceneItemTransform"]
    assert (fitted["boundsWidth"], fitted["boundsHeight"]) == (1920.0, 1080.0)


def test_the_requested_size_is_used_once_the_canvas_really_changed():
    ws = FakeWs()
    sync(ws, width=1440, height=1080, fps=60, allow_scene_requests=True)
    fitted = _transforms(ws)[0]["sceneItemTransform"]
    assert (fitted["boundsWidth"], fitted["boundsHeight"]) == (1440.0, 1080.0)


def test_events_interleaved_with_responses_never_desync_the_dialogue():
    """Found live, not by these tests — which is why the fake now emits events.

    obs-websocket multiplexes events onto the request socket. Taking "the next
    message" as the answer made GetInputList come back empty for a scene that
    plainly held the input, so creation tried to make a source that already
    existed and OBS refused it (code 601). Every request after such an event
    was reading the previous one's answer.
    """
    ws = FakeWs(events=True)
    assert sync(ws, width=1440, height=1080, allow_scene_requests=True) is True
    # The canvas really changed, and the right item was fitted — neither is
    # true if the answers are read one message late.
    assert "SetVideoSettings" in ws.kinds()
    assert [t["sceneItemId"] for t in _transforms(ws)] == [2]


def test_no_events_are_subscribed_to_in_the_first_place():
    """The loop above is the belt; this is the braces. Nothing here consumes
    OBS events, so the identify handshake asks for none."""
    sent = {}

    class Recorder:
        def __init__(self):
            self._step = 0

        def send(self, raw):
            msg = json.loads(raw)
            if msg.get("op") == 1:
                sent.update(msg["d"])

        def recv(self):
            self._step += 1
            if self._step == 1:
                return json.dumps({"op": 0, "d": {}})
            if self._step == 2:
                return json.dumps({"op": 2, "d": {}})
            return json.dumps({
                "op": 7,
                "d": {"requestId": "phonecam-liveness",
                      "requestStatus": {"result": True}, "responseData": {}},
            })

        def close(self):
            pass

    import pc_receiver.obs_sync as module

    recorder = Recorder()
    module.websocket = None  # not used: create_connection is patched below
    import types
    fake_ws_module = types.SimpleNamespace(create_connection=lambda url, timeout: recorder)
    import sys
    sys.modules["websocket"] = fake_ws_module
    try:
        module.open_connection(4455, "pw")
    finally:
        sys.modules.pop("websocket", None)

    assert sent.get("eventSubscriptions") == 0


def test_nothing_is_created_when_scene_requests_are_not_allowed():
    """The Hello path used to run with scene requests off; creation must ride
    the same gate as the fit, never happen behind it."""
    ws = FakeWs(scene_items=[])
    sync(ws, allow_scene_requests=False)
    assert "CreateInput" not in ws.kinds()


def test_framecast_wins_over_the_legacy_phonecam_name():
    """Both names in one scene: the rebrand name is the one fitted, and the
    legacy item is left untouched."""
    ws = FakeWs(scene_items=[
        {"sourceName": "PhoneCam", "sceneItemId": 1},
        {"sourceName": "FrameCast", "sceneItemId": 2},
    ])
    sync(ws, allow_scene_requests=True)
    assert [t["sceneItemId"] for t in _transforms(ws)] == [2]


def test_the_framecast_name_matches_case_insensitively():
    ws = FakeWs(scene_items=[{"sourceName": "framecast", "sceneItemId": 4}])
    sync(ws, allow_scene_requests=True)
    assert [t["sceneItemId"] for t in _transforms(ws)] == [4]


def test_the_source_name_is_configurable(monkeypatch):
    monkeypatch.setenv("PHONECAM_OBS_SOURCE", "Movil del salon")
    ws = FakeWs(scene_items=[
        {"sourceName": "PhoneCam", "sceneItemId": 1},
        {"sourceName": "Movil del salon", "sceneItemId": 2},
    ])
    sync(ws, allow_scene_requests=True)
    assert [t["sceneItemId"] for t in _transforms(ws)] == [2]


def test_the_name_match_ignores_case_but_still_demands_the_whole_name():
    ws = FakeWs(scene_items=[{"sourceName": "phonecam", "sceneItemId": 3}])
    sync(ws, allow_scene_requests=True)
    assert [t["sceneItemId"] for t in _transforms(ws)] == [3]


def test_the_fit_centres_the_source_and_pins_its_box_to_the_canvas():
    ws = FakeWs()
    sync(ws, width=1440, height=1080, allow_scene_requests=True)
    transform = _transforms(ws)[0]["sceneItemTransform"]
    assert transform["boundsType"] == "OBS_BOUNDS_SCALE_INNER"  # scale, never stretch
    assert transform["boundsAlignment"] == 0                    # centred in its box
    assert transform["alignment"] == 5                          # box pinned top-left
    assert (transform["boundsWidth"], transform["boundsHeight"]) == (1440.0, 1080.0)
    assert (transform["positionX"], transform["positionY"]) == (0.0, 0.0)


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


# ───────────────── the SetVideoSettings crash guard ─────────────────
#
# Crash dump, OBS 32.2.1:
#     obs64.exe!OBSBasic::ResetVideo+0x67c
#     obs-websocket.dll!RequestHandler::SetVideoSettings+0xd68
#     Fault address: ...w32-pthreads.dll
#
# The call site used to assume "SetVideoSettings always rejects while any
# output is running". It does not, so the request reached ResetVideo and tore
# down the video pipeline under a live output. These pin the pre-flight check
# that replaced that assumption.

class OutputAwareWs(FakeWs):
    """A FakeWs that also answers the output-status requests."""

    def __init__(self, active_output=None, knows_status=True, **kwargs):
        self.active_output = active_output
        self.knows_status = knows_status
        super().__init__(**kwargs)

    def send(self, raw):
        msg = json.loads(raw)["d"]
        kind = msg["requestType"]
        status_kinds = {
            "GetVirtualCamStatus": "virtual camera",
            "GetRecordStatus": "recording",
            "GetStreamStatus": "streaming",
        }
        if kind in status_kinds:
            self.requests.append((kind, msg.get("requestData") or {}))
            if not self.knows_status:
                self._replies.append(json.dumps({"d": {"requestStatus": {"result": False}}}))
                return
            active = self.active_output == status_kinds[kind]
            self._replies.append(json.dumps({
                "d": {"requestStatus": {"result": True},
                      "responseData": {"outputActive": active}},
            }))
            return
        super().send(raw)


def sync_against(ws, **kwargs):
    params = dict(width=1920, height=1080, fps=60, connector=lambda port, pw: ws,
                  sleeper=lambda s: None)
    params.update(kwargs)
    return obs_sync.sync_video_settings(**params)


@pytest.mark.parametrize("busy", ["virtual camera", "recording", "streaming"])
def test_no_video_reset_while_an_output_is_running(busy):
    """The crash. Any running output means ResetVideo must not be provoked."""
    ws = OutputAwareWs(active_output=busy)

    assert sync_against(ws) is True
    assert "SetVideoSettings" not in ws.kinds(), (
        f"reshaped the video pipeline while the {busy} output was live"
    )


def test_the_reset_still_happens_when_nothing_is_running():
    ws = OutputAwareWs(active_output=None)

    assert sync_against(ws) is True
    assert "SetVideoSettings" in ws.kinds(), "the guard blocked a safe reset"


def test_the_rest_of_the_sync_still_runs_when_the_reset_is_skipped():
    """Skipping the dangerous request must not cost the harmless ones."""
    ws = OutputAwareWs(active_output="virtual camera")

    assert sync_against(ws, bitrate_bps=20_000_000, audio_bitrate_bps=192_000) is True
    assert "SetProfileParameter" in ws.kinds()


def test_an_obs_that_does_not_know_the_status_requests_is_not_blocked():
    """Older obs-websocket builds lack these requests. Unknown status must not
    become a reason never to sync again."""
    ws = OutputAwareWs(active_output=None, knows_status=False)

    assert sync_against(ws) is True
    assert "SetVideoSettings" in ws.kinds()


def test_the_status_check_is_skipped_entirely_when_the_canvas_already_matches():
    """The cheapest safe path stays cheapest: no reset needed, no questions
    asked."""
    ws = OutputAwareWs(active_output=None, video_settings={
        "baseWidth": 1920, "baseHeight": 1080,
        "outputWidth": 1920, "outputHeight": 1080,
        "fpsNumerator": 60, "fpsDenominator": 1,
    })

    assert sync_against(ws) is True
    assert "GetVirtualCamStatus" not in ws.kinds()
    assert "SetVideoSettings" not in ws.kinds()


# ---------- the before -> after log line ----------

def test_the_canvas_change_is_logged_with_both_shapes(caplog):
    """The one line that answers "did my resolution change reach OBS?" without
    needing the line above it for the previous value."""
    ws = FakeWs(video_settings={
        "baseWidth": 1920, "baseHeight": 1080,
        "outputWidth": 1920, "outputHeight": 1080,
        "fpsNumerator": 60, "fpsDenominator": 1,
    })
    with caplog.at_level("INFO"):
        assert sync(ws, width=1440, height=1080, fps=60) is True
    assert "OBS canvas updated: 1920x1080 -> 1440x1080" in caplog.text


def test_an_unreadable_previous_shape_still_logs_the_new_one(caplog):
    ws = FakeWs(video_settings={"baseWidth": "?"})
    with caplog.at_level("INFO"):
        assert sync(ws, width=1440, height=1080, fps=60) is True
    assert "OBS canvas updated: unknown -> 1440x1080" in caplog.text


# ---------- deferring a change nobody can apply yet ----------

def test_a_change_made_while_obs_is_closed_is_kept():
    def refuse(port, pw):
        raise ConnectionError("nothing listening")

    assert obs_sync.has_pending() is False
    assert obs_sync.sync_video_settings(
        width=1440, height=1080, fps=60, connector=refuse, sleeper=lambda s: None,
    ) is False
    assert obs_sync.has_pending() is True


def test_a_change_made_during_a_live_session_is_kept():
    """PhoneCam's own feed is a virtual-camera output, so this is the branch
    every mid-stream resolution change takes. It used to be dropped outright."""
    ws = OutputAwareWs(active_output="virtual camera")

    assert sync_against(ws, width=1440, height=1080) is True
    assert "SetVideoSettings" not in ws.kinds()
    assert obs_sync.has_pending() is True


def test_the_websocket_server_being_off_is_kept_too(monkeypatch):
    monkeypatch.setattr(obs_sync, "_read_obs_websocket_config", lambda: {"server_enabled": False})
    assert sync(FakeWs()) is False
    assert obs_sync.has_pending() is True


def test_a_change_that_applied_leaves_nothing_pending():
    assert sync(FakeWs(), width=1440, height=1080) is True
    assert obs_sync.has_pending() is False


def test_a_canvas_that_already_matches_clears_an_older_pending_change():
    """Whatever was waiting has since been satisfied — by OBS restarting into
    the right shape, or by the user setting it by hand. Replaying it would be
    a no-op reset of the whole video pipeline."""
    obs_sync._defer({"width": 1440, "height": 1080, "fps": 60}, "test")
    ws = FakeWs(video_settings={
        "baseWidth": 1440, "baseHeight": 1080,
        "outputWidth": 1440, "outputHeight": 1080,
        "fpsNumerator": 60, "fpsDenominator": 1,
    })
    assert sync(ws, width=1440, height=1080, fps=60) is True
    assert obs_sync.has_pending() is False


def test_only_the_newest_deferred_change_survives():
    """A user going 1080p -> 4K -> 1440p wants 1440p, not a queue of three."""
    for width, height in [(1920, 1080), (3840, 2160), (1440, 1080)]:
        obs_sync._defer({"width": width, "height": height, "fps": 60}, "test")

    pending = obs_sync._read_pending()
    assert (pending["request"]["width"], pending["request"]["height"]) == (1440, 1080)


def test_repeating_the_same_deferral_does_not_reset_its_attempt_count():
    """Otherwise a change OBS keeps refusing, re-deferred by each attempt,
    could never reach the give-up threshold."""
    request = {"width": 1440, "height": 1080, "fps": 60}
    obs_sync._defer(request, "test")
    obs_sync._write_pending({**obs_sync._read_pending(), "attempts": 4})
    obs_sync._defer(request, "test again")
    assert obs_sync._read_pending()["attempts"] == 4


# ---------- replaying it ----------

def test_replaying_with_nothing_pending_does_nothing():
    assert obs_sync.replay_pending() is False


def test_a_deferred_change_is_applied_on_the_next_connection(monkeypatch):
    ws = OutputAwareWs(active_output="recording")
    assert sync_against(ws, width=1440, height=1080) is True
    assert obs_sync.has_pending() is True

    # OBS is reachable again and its output has stopped.
    free = OutputAwareWs(active_output=None)
    monkeypatch.setattr(obs_sync, "open_connection", lambda port, pw: free)
    obs_sync._last_reset = None

    assert obs_sync.replay_pending() is True
    applied = [d for k, d in free.requests if k == "SetVideoSettings"][0]
    assert (applied["baseWidth"], applied["baseHeight"]) == (1440, 1080)
    assert (applied["outputWidth"], applied["outputHeight"]) == (1440, 1080)
    assert obs_sync.has_pending() is False


def test_a_replay_that_still_cannot_apply_stays_pending(monkeypatch):
    obs_sync._defer({"width": 1440, "height": 1080, "fps": 60}, "test")
    busy = OutputAwareWs(active_output="streaming")
    monkeypatch.setattr(obs_sync, "open_connection", lambda port, pw: busy)

    obs_sync.replay_pending()
    assert obs_sync.has_pending() is True


def test_a_change_obs_will_never_accept_eventually_gives_up(monkeypatch):
    """Replay runs on every manager tick while something is pending. A change
    that can never land must not retry until the machine is switched off."""
    obs_sync._defer({"width": 1440, "height": 1080, "fps": 60}, "test")

    def refuse(port, pw):
        raise ConnectionError("still nothing")

    monkeypatch.setattr(obs_sync, "open_connection", refuse)
    for _ in range(obs_sync._MAX_PENDING_ATTEMPTS + 1):
        obs_sync.replay_pending()
    assert obs_sync.has_pending() is False


def test_an_unreadable_pending_file_is_discarded_rather_than_retried():
    obs_sync._write_pending({"request": {"width": "wide"}, "attempts": 0})
    assert obs_sync.replay_pending() is False
    assert obs_sync.has_pending() is False
