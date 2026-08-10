"""Keeps OBS's video canvas (and, best-effort, its Simple-output video
bitrate) in sync with whatever the phone is actually streaming, over
obs-websocket — OBS's own built-in remote-control API (Tools > WebSocket
Server Settings in OBS; disabled by default, has to be turned on once).

Called from receiver.py on every Hello, gated by Hello.sync_obs (the phone's
Settings > "Sync OBS settings" toggle — see StreamConfig.kt/protocol.py).
Every failure mode here (server disabled, wrong/rotated password, OBS has an
active output so SetVideoSettings is rejected, obs-websocket not installed,
`websocket-client` not installed) is caught and logged, never raised —
this must not be able to take down the receiver or the stream itself over
what's fundamentally a nice-to-have.
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import threading
import time
from typing import Callable, Optional

log = logging.getLogger("pc_receiver.obs_sync")

# SetVideoSettings is not a setting write - it makes OBS tear down and rebuild
# its entire video pipeline (obs_reset_video). Two of those overlapping crashed
# OBS 32.2.1 outright:
#
#   obs-websocket.dll!RequestHandler::SetVideoSettings
#   obs64.exe!OBSBasic::ResetVideo
#   obs.dll!obs_reset_video
#   w32-pthreads.dll!pthread_create   <- access violation
#
# with six resets logged in 950ms and two of them landing in the same
# millisecond. Every sync therefore runs under this lock: callers can be a Hello
# thread and a Settings push at once, and neither knows about the other.
_SYNC_LOCK = threading.Lock()

# A repeat of a reset that just happened buys nothing and costs a full pipeline
# rebuild, so bursts are coalesced. Deliberately short - a real resolution
# change by the user still applies immediately, since the guard only skips
# *identical* requests.
_MIN_SECONDS_BETWEEN_RESETS = 3.0
_last_reset: Optional[tuple] = None
_last_reset_at = 0.0

# Sanity bounds on what we will ask OBS to become. OBS clamps out-of-range
# values rather than refusing them, so a bad number here silently reshapes the
# user's canvas instead of failing loudly.
#
# The crash log showed six resets to 64x48 in the second before the crash, which
# looked like a caller sending nonsense. It was not: a clean OBS start logs the
# same 64x48 resets with nothing connected to it. That is OBS's own startup
# sequence, and the crash was our SetVideoSettings landing in the middle of it -
# hence the settle wait below, and the lock.
_MIN_DIMENSION = 160
_MAX_DIMENSION = 16384
_MAX_FPS = 240

_CONFIG_PATH = os.path.join(
    os.path.expanduser("~"), "AppData", "Roaming", "obs-studio",
    "plugin_config", "obs-websocket", "config.json",
)


def _read_obs_websocket_config() -> Optional[dict]:
    try:
        with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _compute_auth(password: str, salt: str, challenge: str) -> str:
    """obs-websocket v5 auth: base64(sha256(base64(sha256(password+salt)) + challenge))."""
    secret = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((secret + challenge).encode()).digest()).decode()


# How many messages to skip while looking for one request's answer. Events
# arrive in bursts (a scene switch emits several), but any number this large
# means the socket is not answering us at all.
_MAX_MESSAGES_PER_REQUEST = 50


def _request(ws, request_type: str, request_id: str, data: dict) -> dict:
    """One obs-websocket request/response round trip, returning its
    requestStatus. Every caller here treats a rejection as non-fatal.

    Reading until the matching answer, rather than taking the next message,
    is load-bearing: obs-websocket multiplexes *events* onto the same socket,
    so `recv()` can hand back an InputActiveStateChanged (or any of a dozen
    others) instead of the response. Every later request then reads the
    previous one's answer, and the whole conversation is off by one.

    Confirmed live: GetInputList came back empty for a scene that plainly
    contained the input, which made the source-creation logic try to create
    one that already existed. [open_connection] now also asks for no events at
    all, which removes the cause; this loop is what keeps a stray message from
    ever being mistaken for an answer again.
    """
    ws.send(json.dumps({
        "op": 6,
        "d": {"requestType": request_type, "requestId": request_id, "requestData": data},
    }))
    for _ in range(_MAX_MESSAGES_PER_REQUEST):
        try:
            message = json.loads(ws.recv())
        except Exception:
            return {}
        if message.get("op") == 5:          # an event, never an answer
            continue
        payload = message.get("d") or {}
        # None covers test doubles and any build that omits the echo; a
        # different id is a late answer to a request that already gave up.
        if payload.get("requestId") not in (None, request_id):
            continue
        return payload
    log.warning("obs_sync: no answer to %s after %d messages", request_type,
                _MAX_MESSAGES_PER_REQUEST)
    return {}


DEFAULT_SOURCE_NAME = "FrameCast"
# The app's name before the FrameCast rebrand. Still accepted so a scene set
# up against an older build keeps working untouched; FrameCast wins when a
# scene somehow has both.
LEGACY_SOURCE_NAME = "PhoneCam"


def source_names() -> tuple:
    """The exact OBS scene-item names this module is allowed to reshape, in
    preference order.

    Overridable with PHONECAM_OBS_SOURCE for anyone who has already named
    their source something else and would rather configure than rename — and
    an explicit override is exclusive: it names *the* source, so the built-in
    defaults stop applying.
    """
    configured = os.environ.get("PHONECAM_OBS_SOURCE", "").strip()
    if configured:
        return (configured,)
    return (DEFAULT_SOURCE_NAME, LEGACY_SOURCE_NAME)


# Video-capture input kinds, per platform, mapped to the settings key that
# holds the chosen device. Asked of OBS (GetInputKindList) rather than
# assumed, so an OBS build without the expected kind degrades to "cannot
# create it" instead of creating a broken source.
_CAPTURE_KINDS = {
    "dshow_input": "video_device_id",        # Windows
    "v4l2_input": "device_id",               # Linux
    "av_capture_input_v2": "device",         # macOS
    "av_capture_input": "device",
}

# How OBS names its own virtual-camera device in that list. The Windows
# DirectShow filter is registered by the OBS installer, so it is present
# whether or not the virtual camera has ever been started — matched
# case-insensitively, and the legacy obs-virtualcam plugin's name too.
_VIRTUAL_CAMERA_HINTS = ("obs virtual camera", "obs-camera", "obs virtualcam")


def _current_scene(ws) -> Optional[str]:
    """The current program scene, via GetSceneList.

    Never GetCurrentProgramScene: that request crashed OBS 32.2.1 with an
    access violation in three separate reports — see [_fit_phonecam_source].
    """
    status = _request(ws, "GetSceneList", "phonecam-scene", {})
    return (status.get("responseData") or {}).get("currentProgramSceneName")


def _find_scene_item(ws, scene: str, wanted_names) -> Optional[dict]:
    """The scene item matching one of [wanted_names], preferring the order
    given (so FrameCast wins over the legacy PhoneCam when a scene has both).
    Matching is case-insensitive but never a substring — see
    [_fit_phonecam_source] for the webcam-eating bug that rule exists for."""
    status = _request(ws, "GetSceneItemList", "phonecam-items", {"sceneName": scene})
    items = (status.get("responseData") or {}).get("sceneItems") or []
    by_folded_name: dict = {}
    for item in items:
        by_folded_name.setdefault(str(item.get("sourceName", "")).casefold(), item)
    for wanted in wanted_names:
        found = by_folded_name.get(wanted.casefold())
        if found is not None:
            return found
    return None


def _capture_kind(ws) -> Optional[tuple]:
    status = _request(ws, "GetInputKindList", "phonecam-kinds", {})
    kinds = (status.get("responseData") or {}).get("inputKinds") or []
    for kind, device_key in _CAPTURE_KINDS.items():
        if kind in kinds:
            return kind, device_key
    return None


def _select_virtual_camera(ws, input_name: str, device_key: str) -> None:
    """Points a freshly created capture input at OBS's virtual camera.

    Done after creation rather than during it because the device *value* is a
    machine-specific DirectShow moniker, not a name — it can only be read from
    the input's own property list, which requires the input to exist.

    Best-effort by design: a source that exists with the right name but no
    device still lets the canvas/fit logic work, and the user only has to pick
    a device from a dropdown. Silently creating nothing at all would be worse.
    """
    status = _request(ws, "GetInputPropertiesListPropertyItems", "phonecam-devices", {
        "inputName": input_name,
        "propertyName": device_key,
    })
    items = (status.get("responseData") or {}).get("propertyItems") or []
    for item in items:
        name = str(item.get("itemName", ""))
        if any(hint in name.casefold() for hint in _VIRTUAL_CAMERA_HINTS):
            _request(ws, "SetInputSettings", "phonecam-setdevice", {
                "inputName": input_name,
                "inputSettings": {device_key: item.get("itemValue")},
                "overlay": True,
            })
            log.info("obs_sync: pointed '%s' at OBS's virtual camera (%s)", input_name, name)
            return
    log.warning(
        "obs_sync: created '%s' but found no OBS Virtual Camera device to point it at. "
        "Devices offered: %s. Pick one in OBS (double-click the source) — and if the list "
        "is empty, start OBS's Virtual Camera once so Windows registers it.",
        input_name, [str(i.get("itemName", "")) for i in items] or "none",
    )


def ensure_source(ws, scene: str) -> Optional[dict]:
    """Creates FrameCast's own capture source in [scene] if it isn't there,
    and returns its scene item (or None if it could not be created).

    This exists because the auto-fit is deliberately name-exact: it only ever
    touches a source called FrameCast, so that it can never rewrite the
    transform of somebody's webcam or capture card. That safety rule put the
    burden of naming on the user — "it only works if you name it exactly
    FrameCast" is a setup step people get wrong. Creating it here removes the
    step instead of relaxing the rule.

    Three cases, and only the first one creates anything new:
      - no input by that name anywhere -> CreateInput in this scene;
      - the input exists in another scene -> CreateSceneItem, because input
        names are global in OBS and CreateInput would fail on the conflict;
      - already in this scene -> the caller never gets here.
    """
    wanted = source_names()[0]

    inputs = (_request(ws, "GetInputList", "phonecam-inputs", {})
              .get("responseData") or {}).get("inputs") or []
    already_exists = any(
        str(i.get("inputName", "")).casefold() == wanted.casefold() for i in inputs
    )

    if already_exists:
        log.info("obs_sync: adding the existing '%s' source to scene '%s'", wanted, scene)
        status = _request(ws, "CreateSceneItem", "phonecam-additem", {
            "sceneName": scene,
            "sourceName": wanted,
            "sceneItemEnabled": True,
        })
    else:
        kind = _capture_kind(ws)
        if kind is None:
            log.warning(
                "obs_sync: this OBS has no video-capture input kind we know of — "
                "cannot create the '%s' source automatically", wanted,
            )
            return None
        input_kind, device_key = kind
        log.info(
            "obs_sync: creating the '%s' capture source in scene '%s' (%s)",
            wanted, scene, input_kind,
        )
        status = _request(ws, "CreateInput", "phonecam-createinput", {
            "sceneName": scene,
            "inputName": wanted,
            "inputKind": input_kind,
            "inputSettings": {},
            "sceneItemEnabled": True,
        })
        if (status.get("requestStatus") or {}).get("result"):
            _select_virtual_camera(ws, wanted, device_key)

    if not (status.get("requestStatus") or {}).get("result"):
        log.warning(
            "obs_sync: could not create the '%s' source: %s",
            wanted, (status.get("requestStatus") or {}).get("comment"),
        )
        return None

    # Re-queried rather than read out of the create response: it keeps this
    # working across obs-websocket versions that answer CreateSceneItem and
    # CreateInput with different shapes, and proves the item is really there.
    return _find_scene_item(ws, scene, (wanted,))


def _fit_phonecam_source(ws, width: int, height: int) -> None:
    """Makes PhoneCam's own source fit the canvas without distortion.

    Matching the canvas to the stream is only half of it: a scene item keeps
    whatever scale it was given when it was added, so after a resolution change
    the source stays at its old size and OBS stretches it to fill. Setting a
    bounding box with OBS_BOUNDS_SCALE_INNER makes OBS scale the source to fit
    inside the canvas *preserving aspect ratio* — letterboxing rather than
    distorting, which is what "don't stretch the image" actually requires.

    ## Why the match is exact, and only exact

    This used to take the first scene item with "cam" anywhere in its name,
    on the reasoning that the virtual camera device name is localised and
    varies by backend. That reasoning was about finding *our* source; what the
    rule actually describes is a large share of every capture device anyone
    owns. "Logitech Webcam", "Elgato Cam Link", "DroidCam Source", "OBS Virtual
    Camera" and a scene named "Camera 2" all match it, and the first one in
    the user's scene list wins — so a feature meant to fit the phone's feed
    could silently rewrite the transform of a webcam it has nothing to do with,
    in a scene it was never pointed at.

    A scene item is now touched only when its name is exactly [source_name],
    compared case-insensitively but never as a substring. If nothing matches,
    nothing is modified and the reason is logged with the names that *were*
    found, so the fix (rename the source, or set PHONECAM_OBS_SOURCE) is
    visible rather than guessed at.

    Note this is deliberately a *name* match, not a device match: PhoneCam
    reaches OBS through pyvirtualcam, whose Windows backend is the OBS Virtual
    Camera device — so the device identity cannot distinguish our feed from
    anything else using the same backend. The name the user gave the item can.

    The current scene comes from GetSceneList, not from GetCurrentProgramScene,
    which reports it more directly but crashes OBS 32.2.1 with an access
    violation:

        obs-websocket.dll!RequestHandler::GetCurrentProgramScene+0xa1
        obs-websocket.dll!RequestHandler::ProcessRequest

    Three crash reports name it. The first two arrived while OBS was still
    initialising, which made it look like a startup race worth gating; the third
    happened against an OBS that had been up and answering for a minute, which
    ruled that out - the request is simply not safe on this build. GetSceneList
    goes through a different handler, carries currentProgramSceneName, and was
    verified against the same OBS without incident.

    Best-effort throughout: if the scene or the source can't be found, the rest
    of the sync is still worth doing.
    """
    wanted_names = source_names()

    scene = _current_scene(ws)
    if not scene:
        return

    item = _find_scene_item(ws, scene, wanted_names)
    if item is None:
        # Not there — so put it there. See [ensure_source]: the exact-name rule
        # is what keeps this from ever touching somebody else's webcam, and
        # creating the source ourselves is what stops that safety rule from
        # becoming a setup step the user has to get right by hand.
        item = ensure_source(ws, scene)
    if item is None:
        log.warning(
            "obs_sync: no scene item named '%s' in scene '%s', and it could not be created "
            "— leaving every source alone. Add a Video Capture Device source named '%s' "
            "(or set PHONECAM_OBS_SOURCE to the name of the one you have).",
            wanted_names[0], scene, wanted_names[0],
        )
        return

    name = str(item.get("sourceName", ""))
    _request(ws, "SetSceneItemTransform", "phonecam-fit", {
        "sceneName": scene,
        "sceneItemId": item.get("sceneItemId"),
        "sceneItemTransform": {
            "boundsType": "OBS_BOUNDS_SCALE_INNER",
            # 0 is OBS_ALIGN_CENTER: the source is centred inside its
            # bounding box, so a composition narrower or shorter than the
            # canvas letterboxes evenly instead of hugging one edge.
            "boundsAlignment": 0,
            "boundsWidth": float(width),
            "boundsHeight": float(height),
            # The bounding box itself is pinned to the canvas origin, which
            # is only where it looks if the item's *position* alignment is
            # top-left. An item left on centre alignment would put the box's
            # centre at (0,0) and hang three quarters of it off-canvas, so
            # the alignment is set here rather than inherited.
            "alignment": 5,  # OBS_ALIGN_LEFT | OBS_ALIGN_TOP
            "positionX": 0.0,
            "positionY": 0.0,
        },
    })
    log.info(
        "obs_sync: fitted scene item '%s' to %sx%s without stretching", name, width, height,
    )


def _dimensions_are_sane(width: int, height: int, fps: int) -> bool:
    return (
        _MIN_DIMENSION <= width <= _MAX_DIMENSION
        and _MIN_DIMENSION <= height <= _MAX_DIMENSION
        and 0 < fps <= _MAX_FPS
    )


# Read-only status requests, one per kind of output that makes resetting the
# video pipeline unsafe. Same class of request as GetVideoSettings, which this
# module has always issued without incident.
_OUTPUT_STATUS_REQUESTS = (
    ("virtual camera", "GetVirtualCamStatus"),
    ("recording", "GetRecordStatus"),
    ("streaming", "GetStreamStatus"),
)


def _active_output(ws) -> Optional[str]:
    """The name of an OBS output that is currently running, or None.

    ## Why this exists — the definitive cause of the SetVideoSettings crash

    SetVideoSettings lands in OBSBasic::ResetVideo, which tears down and
    rebuilds OBS's entire video pipeline. OBS's own Settings dialog will not
    let you do that while an output is running: the resolution and FPS fields
    are disabled. That is not a UI nicety, it is the reason the operation is
    safe when the UI performs it.

    This module used to send SetVideoSettings unconditionally, on the
    assumption — written down at its call site — that "SetVideoSettings always
    rejects while any output is running". **That assumption is false on OBS
    32.2.1.** The request went through, reached ResetVideo, and took OBS down
    with an access violation:

        obs64.exe!OBSBasic::ResetVideo+0x67c
        obs-websocket.dll!RequestHandler::SetVideoSettings+0xd68
        obs-websocket.dll!RequestHandler::ProcessRequest+0x196
        Fault address: ...w32-pthreads.dll

    The pthreads fault address is the tell: ResetVideo was recreating the
    graphics thread while something still held the old pipeline.

    And PhoneCam is what was holding it. The receiver feeds OBS's virtual
    camera, and the automatic sync fires OBS_SETTLE_SECONDS *into a live
    session* — so the one moment it reshapes the pipeline is the one moment
    the pipeline is guaranteed to be in use. Checking here, instead of
    trusting OBS to refuse, is what removes that collision.

    A status request that fails or is unrecognised reports None: this must
    never be the reason a sync does not happen, only the reason a *reset* does
    not happen.
    """
    for label, request_type in _OUTPUT_STATUS_REQUESTS:
        status = _request(ws, request_type, f"phonecam-{request_type}", {})
        if not (status.get("requestStatus") or {}).get("result"):
            continue  # older obs-websocket without this request; treat as unknown
        if (status.get("responseData") or {}).get("outputActive"):
            return label
    return None


def _current_canvas(ws) -> Optional[tuple]:
    """OBS's canvas right now as (baseW, baseH, outW, outH, fps), or None if it
    could not be read.

    Worth the round trip twice over: it decides whether the most dangerous call
    in this module can be skipped entirely (the usual case — nothing changed
    since the last stream), and it is the only source for the "from" half of the
    canvas-updated log line. An unreadable answer is None, which every caller
    treats as "proceed as before".
    """
    status = _request(ws, "GetVideoSettings", "phonecam-get-video", {})
    data = status.get("responseData") or {}
    try:
        denominator = int(data["fpsDenominator"]) or 1
        return (
            int(data["baseWidth"]), int(data["baseHeight"]),
            int(data["outputWidth"]), int(data["outputHeight"]),
            int(data["fpsNumerator"]) // denominator,
        )
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        return None


def _canvas_matches(canvas: Optional[tuple], width: int, height: int, fps: int) -> bool:
    return canvas == (width, height, width, height, fps)


# ---------------------------------------------------------------- pending ----
#
# A canvas change that could not be applied is kept rather than dropped. There
# are three ordinary ways to arrive at a change nobody can apply yet, and all
# three used to end with the setting silently lost:
#
#   - OBS is closed, or its WebSocket server is off (the user changes the
#     setting on the phone first and opens OBS afterwards - the common order);
#   - OBS is open but an output is running, which is the one state where
#     reshaping the pipeline crashes it (see _active_output). Note PhoneCam's
#     own feed *is* an output, so every change made mid-session lands here;
#   - OBS answered and refused.
#
# On disk rather than in memory because the receiver is a service that gets
# restarted, and "apply it on the next connection" has to survive that.

_PENDING_PATH = os.path.join(
    os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"),
    "PhoneCam", "obs_pending.json",
)

# Replay is attempted on every ObsManager tick that finds a pending change, so
# a change deferred because an output was running applies the moment it stops -
# no reconnect needed. This bounds that: a change OBS will never accept must
# not retry until the machine is turned off.
_MAX_PENDING_ATTEMPTS = 20

_PENDING_LOCK = threading.Lock()


def _read_pending() -> Optional[dict]:
    try:
        with open(_PENDING_PATH, "r", encoding="utf-8") as f:
            pending = json.load(f)
        return pending if isinstance(pending, dict) else None
    except Exception:
        return None


def _write_pending(pending: Optional[dict]) -> None:
    """Persists (or clears) the deferred change. Never raises: a read-only or
    missing profile directory must not take down a sync that otherwise worked."""
    try:
        if pending is None:
            if os.path.exists(_PENDING_PATH):
                os.remove(_PENDING_PATH)
            return
        os.makedirs(os.path.dirname(_PENDING_PATH), exist_ok=True)
        with open(_PENDING_PATH, "w", encoding="utf-8") as f:
            json.dump(pending, f)
    except Exception as e:
        log.debug("obs_sync: could not persist the pending canvas change: %s", e)


def _canvas_key(request: dict) -> tuple:
    """What makes two deferred changes "the same change" for retry purposes.

    The canvas triple only, not the whole request: the bitrates and sample
    rate ride along on the same replay but do not reset the pipeline, so a
    change that differs only in bitrate is still the same canvas change and
    must keep its attempt count.
    """
    return (request.get("width"), request.get("height"), request.get("fps"))


def _defer(request: dict, reason: str) -> None:
    """Remembers a change that could not be applied now.

    Only the newest matters - a user who moves 1080p -> 4K -> 1440p wants
    1440p, not a queue - so a request for a different canvas replaces the
    stored one and resets its attempt count.

    A request for the *same* canvas must not, and this is load-bearing rather
    than tidy: replay_pending increments the count and then calls
    sync_video_settings, which lands right back here when it fails again.
    Resetting on the way through made the counter oscillate between 0 and 1,
    so the give-up threshold was unreachable and a change OBS would never
    accept retried on every manager tick, forever.
    """
    with _PENDING_LOCK:
        previous = _read_pending() or {}
        same_canvas = _canvas_key(previous.get("request") or {}) == _canvas_key(request)
        attempts = previous.get("attempts", 0) if same_canvas else 0
        _write_pending({"request": request, "attempts": attempts, "reason": reason})
    log.info(
        "obs_sync: saved %sx%s@%sfps to apply when OBS can take it (%s)",
        request["width"], request["height"], request["fps"], reason,
    )


def _clear_pending() -> None:
    with _PENDING_LOCK:
        if _read_pending() is not None:
            _write_pending(None)


def has_pending() -> bool:
    with _PENDING_LOCK:
        return os.path.exists(_PENDING_PATH) and _read_pending() is not None


def replay_pending(allow_scene_requests: bool = False) -> bool:
    """Re-applies a deferred canvas change. Wired to ObsManager's connected
    hook in control_server, so obs_manager still knows nothing about *what*
    gets synced - only that OBS is reachable again.

    Returns whether anything was applied.
    """
    with _PENDING_LOCK:
        pending = _read_pending()
        if pending is None:
            return False
        request = pending.get("request") or {}
        attempts = int(pending.get("attempts", 0)) + 1
        if attempts > _MAX_PENDING_ATTEMPTS:
            log.warning(
                "obs_sync: giving up on the deferred %sx%s canvas change after %s attempts",
                request.get("width"), request.get("height"), _MAX_PENDING_ATTEMPTS,
            )
            _write_pending(None)
            return False
        _write_pending({**pending, "attempts": attempts})

    try:
        return sync_video_settings(
            width=int(request["width"]),
            height=int(request["height"]),
            fps=int(request["fps"]),
            bitrate_bps=int(request.get("bitrate_bps", 0)),
            audio_bitrate_bps=int(request.get("audio_bitrate_bps", 0)),
            sample_rate=int(request.get("sample_rate", 0)),
            allow_scene_requests=allow_scene_requests,
            source="pending",
        )
    except (KeyError, TypeError, ValueError) as e:
        log.warning("obs_sync: discarding an unreadable pending change: %s", e)
        _clear_pending()
        return False


def sync_video_settings(
    width: int,
    height: int,
    fps: int,
    bitrate_bps: int = 0,
    audio_bitrate_bps: int = 0,
    sample_rate: int = 0,
    allow_scene_requests: bool = False,
    settle_seconds: float = 0.0,
    source: str = "unknown",
    connector: Optional[Callable[[int, str], object]] = None,
    sleeper: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> bool:
    """Sets OBS's canvas/output resolution+fps to match the phone's stream,
    and (best-effort, only takes effect if the profile is in Simple output
    mode) its video bitrate. Returns whether the sync actually went through —
    callers can log it, but there's nothing to react to either way.

    [settle_seconds] delays the whole thing. obs-websocket answers requests
    while OBS is still starting, and mutating OBS in that window is what the
    two crash reports have in common; callers that may fire right after OBS
    launched (the Hello path) wait it out first. Callers that already know OBS
    has been up for a while pass 0.

    [source] only ever appears in the log, and exists because when six resets
    arrived in one second there was no way to tell which caller sent them.

    A change that cannot be applied right now is deferred rather than dropped
    — see the pending section above — and retried by [replay_pending].
    """
    global _last_reset, _last_reset_at

    if not _dimensions_are_sane(width, height, fps):
        log.warning(
            "obs_sync: refusing to reshape OBS to %sx%s@%sfps (from %s) — out of range",
            width, height, fps, source,
        )
        return False

    # What would have to be replayed if this attempt cannot land. Built before
    # anything can fail so every deferral path stores the same shape.
    deferrable = {
        "width": width, "height": height, "fps": fps,
        "bitrate_bps": bitrate_bps, "audio_bitrate_bps": audio_bitrate_bps,
        "sample_rate": sample_rate,
    }

    if settle_seconds > 0:
        sleeper(settle_seconds)

    cfg = _read_obs_websocket_config()
    if not cfg or not cfg.get("server_enabled"):
        _defer(deferrable, "OBS WebSocket server is off")
        log.info(
            "obs_sync: OBS WebSocket server is off — enable it once in OBS "
            "(Tools > WebSocket Server Settings > Enable WebSocket server) to use auto-sync",
        )
        return False

    port = int(cfg.get("server_port", 4455))
    password = str(cfg.get("server_password", ""))
    connect = connector or open_connection

    ws = None
    with _SYNC_LOCK:
        try:
            ws = connect(port, password)
        except Exception as e:
            _defer(deferrable, f"could not connect to OBS: {e}")
            log.warning("obs_sync: could not connect to OBS (from %s): %s", source, e)
            return False

        try:
            requested = (width, height, fps)
            recent = (
                _last_reset == requested
                and clock() - _last_reset_at < _MIN_SECONDS_BETWEEN_RESETS
            )
            if recent:
                log.info(
                    "obs_sync: skipping repeat reset to %sx%s@%sfps from %s",
                    width, height, fps, source,
                )
            elif _canvas_matches(canvas := _current_canvas(ws), width, height, fps):
                log.info("obs_sync: OBS canvas already %sx%s@%sfps", width, height, fps)
                _last_reset, _last_reset_at = requested, clock()
                _clear_pending()
            elif (busy := _active_output(ws)) is not None:
                # The hard guard. See _active_output: reshaping the video
                # pipeline while an output holds it is what crashed OBS, and
                # OBS does not reliably refuse it on our behalf. Skipping is
                # not a degraded outcome — OBS's own Settings dialog forbids
                # exactly this, so there is nothing here we are giving up.
                #
                # Deferred rather than dropped: PhoneCam's own feed is a
                # virtual-camera output, so this is the branch every canvas
                # change made during a live session takes. Without the pending
                # store, changing resolution mid-stream did nothing, ever.
                _defer(deferrable, f"OBS {busy} output is running")
                log.info(
                    "obs_sync: not resetting the canvas to %sx%s@%sfps — OBS %s output is "
                    "running, and changing video settings under a live output crashes OBS "
                    "(see _active_output). Saved; it will be applied once that output stops.",
                    width, height, fps, busy,
                )
            else:
                log.info(
                    "obs_sync: resetting OBS canvas to %sx%s@%sfps (from %s)",
                    width, height, fps, source,
                )
                video_status = _request(ws, "SetVideoSettings", "phonecam-sync-video", {
                    "fpsNumerator": fps,
                    "fpsDenominator": 1,
                    "baseWidth": width,
                    "baseHeight": height,
                    "outputWidth": width,
                    "outputHeight": height,
                }).get("requestStatus", {})
                if video_status.get("result"):
                    _last_reset, _last_reset_at = requested, clock()
                    _clear_pending()
                    # Both halves of the change, which is what makes this line
                    # answer "did my resolution change actually reach OBS?" on
                    # its own. The previous shape is whatever GetVideoSettings
                    # reported a moment ago; "unknown" only if it was unreadable.
                    was = f"{canvas[0]}x{canvas[1]}" if canvas else "unknown"
                    log.info(
                        "OBS canvas updated: %s -> %sx%s @%sfps", was, width, height, fps,
                    )
                else:
                    # Reached only when OBS refuses for some reason the
                    # pre-flight check above did not cover. Not fatal to the
                    # rest of the sync.
                    _defer(deferrable, "SetVideoSettings rejected")
                    log.warning("obs_sync: SetVideoSettings rejected: %s", video_status.get("comment"))

            # Profile parameters below only take effect in Simple output mode,
            # and OBS silently ignores them otherwise — all best-effort. None of
            # them resets the video pipeline, so none is gated.
            if bitrate_bps > 0:
                _request(ws, "SetProfileParameter", "phonecam-sync-vbitrate", {
                    "parameterCategory": "SimpleOutput",
                    "parameterName": "VBitrate",
                    "parameterValue": str(bitrate_bps // 1000),
                })

            if audio_bitrate_bps > 0:
                _request(ws, "SetProfileParameter", "phonecam-sync-abitrate", {
                    "parameterCategory": "SimpleOutput",
                    "parameterName": "ABitrate",
                    "parameterValue": str(audio_bitrate_bps // 1000),
                })

            if sample_rate > 0:
                # Audio sample rate lives on the profile's Audio category, not
                # SimpleOutput, and OBS only picks it up on the next profile
                # load — worth setting anyway so a restart lands on the right
                # value.
                _request(ws, "SetProfileParameter", "phonecam-sync-samplerate", {
                    "parameterCategory": "Audio",
                    "parameterName": "SampleRate",
                    "parameterValue": str(sample_rate),
                })

            # Gated, and defaulting to off. GetCurrentProgramScene crashed OBS
            # 32.2.1 with an access violation when it arrived during startup -
            # the WebSocket server answers before the frontend is ready. Callers
            # that know OBS has been up for a while (see ObsManager.scene_
            # requests_allowed) opt in; the Hello path never does, because a
            # stream starting is exactly when OBS may have just been launched.
            if allow_scene_requests:
                # Fit to the canvas that is ACTUALLY in effect, never to the
                # one we asked for. The two differ whenever the reset above
                # was skipped or deferred — and "an output is running" is not
                # a rare state, it is the normal one the moment the user
                # starts OBS's own virtual camera. Sizing the source for a
                # canvas that does not exist yet is what put it off-canvas:
                # reported as "bigger than the frame and shifted left", with
                # the frames themselves pixel-perfect.
                #
                # One extra read-only request, and it cannot be wrong by
                # construction. When the deferred change does land later,
                # replay_pending runs this same path again with the canvas
                # it just applied.
                applied = _current_canvas(ws)
                if applied is not None and (applied[0], applied[1]) != (width, height):
                    log.info(
                        "obs_sync: fitting to the canvas actually in effect (%sx%s), "
                        "not the requested %sx%s",
                        applied[0], applied[1], width, height,
                    )
                fit_width, fit_height = (
                    (applied[0], applied[1]) if applied is not None else (width, height)
                )
                _fit_phonecam_source(ws, fit_width, fit_height)

            log.info(
                "obs_sync: synced canvas=%sx%s@%sfps video=%skbps audio=%skbps rate=%sHz",
                width, height, fps, bitrate_bps // 1000, audio_bitrate_bps // 1000, sample_rate,
            )
            return True
        except Exception as e:
            log.warning("obs_sync: sync failed: %s", e)
            return False
        finally:
            if ws is not None:
                try:
                    ws.close()
                except Exception:
                    pass


def open_connection(port: int, password: str):
    """Connector for ObsManager: a live, identified, *answering* obs-websocket
    session.

    Raises AuthRejected when OBS answers but refuses the password, so the state
    machine can tell "wrong password" (user must act) apart from "nothing
    listening" (retry soon) - they used to look identical.

    The identify handshake alone is not proof OBS works. After OBS crashed, the
    process stayed alive around its crash dialog with the WebSocket thread still
    accepting connections and completing identify, while every request that
    needed the frontend timed out. The manager called that CONNECTED, and after
    two such connects it declared OBS stable enough for scene requests - vouching
    for a corpse. One cheap request that OBS can only answer if it is actually
    running closes that hole.
    """
    import websocket

    from pc_receiver.obs_manager import AuthRejected

    ws = websocket.create_connection(f"ws://127.0.0.1:{port}", timeout=3)
    try:
        hello = json.loads(ws.recv())
        auth_info = hello.get("d", {}).get("authentication")
        # eventSubscriptions 0: nothing here consumes OBS events, and leaving
        # the default (all of them) meant they interleaved with responses on
        # this same socket — see _request for the desync that caused.
        identify: dict = {"op": 1, "d": {"rpcVersion": 1, "eventSubscriptions": 0}}
        if auth_info:
            identify["d"]["authentication"] = _compute_auth(
                password, auth_info["salt"], auth_info["challenge"],
            )
        ws.send(json.dumps(identify))
        identified = json.loads(ws.recv())
        if identified.get("op") != 2:
            raise AuthRejected(str(identified.get("d", {}).get("comment") or identified))

        # GetVersion is read-only, cheap, and answered by OBS proper rather
        # than by the WebSocket thread alone - so no answer means no usable OBS.
        # _request swallows the timeout and returns {}, which is exactly the
        # case being detected here.
        version = _request(ws, "GetVersion", "phonecam-liveness", {})
        if not (version.get("requestStatus") or {}).get("result"):
            raise ConnectionError("OBS accepted the connection but is not answering requests")
        return ws
    except Exception:
        try:
            ws.close()
        except Exception:
            pass
        raise


def read_config() -> Optional[dict]:
    """Config reader for ObsManager. None means OBS was never installed/run."""
    return _read_obs_websocket_config()
