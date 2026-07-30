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


def _request(ws, request_type: str, request_id: str, data: dict) -> dict:
    """One obs-websocket request/response round trip, returning its
    requestStatus. Every caller here treats a rejection as non-fatal."""
    ws.send(json.dumps({
        "op": 6,
        "d": {"requestType": request_type, "requestId": request_id, "requestData": data},
    }))
    try:
        return json.loads(ws.recv()).get("d", {}) or {}
    except Exception:
        return {}


def _fit_virtual_camera_source(ws, width: int, height: int) -> None:
    """Makes the phone's source fit the canvas without distortion.

    Matching the canvas to the stream is only half of it: a scene item keeps
    whatever scale it was given when it was added, so after a resolution change
    the source stays at its old size and OBS stretches it to fill. Setting a
    bounding box with OBS_BOUNDS_SCALE_INNER makes OBS scale the source to fit
    inside the canvas *preserving aspect ratio* — letterboxing rather than
    distorting, which is what "don't stretch the image" actually requires.

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
    scene_status = _request(ws, "GetSceneList", "phonecam-scene", {})
    scene = (scene_status.get("responseData") or {}).get("currentProgramSceneName")
    if not scene:
        return

    items_status = _request(ws, "GetSceneItemList", "phonecam-items", {"sceneName": scene})
    items = (items_status.get("responseData") or {}).get("sceneItems") or []
    for item in items:
        name = str(item.get("sourceName", ""))
        # The receiver feeds OBS through a virtual camera, so the scene item is
        # a video capture device whose name mentions it. Matching loosely on
        # purpose: the device name is localised and varies by backend.
        if "cam" not in name.lower():
            continue
        _request(ws, "SetSceneItemTransform", "phonecam-fit", {
            "sceneName": scene,
            "sceneItemId": item.get("sceneItemId"),
            "sceneItemTransform": {
                "boundsType": "OBS_BOUNDS_SCALE_INNER",
                "boundsAlignment": 0,
                "boundsWidth": float(width),
                "boundsHeight": float(height),
                "positionX": 0.0,
                "positionY": 0.0,
            },
        })
        log.info("obs_sync: fitted scene item '%s' to %sx%s without stretching", name, width, height)
        return


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


def _canvas_already_matches(ws, width: int, height: int, fps: int) -> bool:
    """Whether OBS is already shaped the way we are about to ask for.

    Worth a round trip: the answer is usually yes (nothing changed since the
    last stream), and skipping the request turns the most dangerous call in this
    module into a no-op for the common case. A failed or unparseable read just
    reports False, so the sync proceeds exactly as it did before.
    """
    status = _request(ws, "GetVideoSettings", "phonecam-get-video", {})
    data = status.get("responseData") or {}
    try:
        return (
            int(data["baseWidth"]) == width
            and int(data["baseHeight"]) == height
            and int(data["outputWidth"]) == width
            and int(data["outputHeight"]) == height
            and int(data["fpsNumerator"]) == fps
            and int(data["fpsDenominator"]) == 1
        )
    except (KeyError, TypeError, ValueError):
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
    """
    global _last_reset, _last_reset_at

    if not _dimensions_are_sane(width, height, fps):
        log.warning(
            "obs_sync: refusing to reshape OBS to %sx%s@%sfps (from %s) — out of range",
            width, height, fps, source,
        )
        return False

    if settle_seconds > 0:
        sleeper(settle_seconds)

    cfg = _read_obs_websocket_config()
    if not cfg or not cfg.get("server_enabled"):
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
            elif _canvas_already_matches(ws, width, height, fps):
                log.info("obs_sync: OBS canvas already %sx%s@%sfps", width, height, fps)
                _last_reset, _last_reset_at = requested, clock()
            elif (busy := _active_output(ws)) is not None:
                # The hard guard. See _active_output: reshaping the video
                # pipeline while an output holds it is what crashed OBS, and
                # OBS does not reliably refuse it on our behalf. Skipping is
                # not a degraded outcome — OBS's own Settings dialog forbids
                # exactly this, so there is nothing here we are giving up.
                log.info(
                    "obs_sync: not resetting the canvas to %sx%s@%sfps — OBS %s output is "
                    "running, and changing video settings under a live output crashes OBS "
                    "(see _active_output). Stop it and change resolution, or let the next "
                    "sync pick it up.",
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
                    log.info("obs_sync: OBS canvas set to %sx%s@%sfps", width, height, fps)
                else:
                    # Reached only when OBS refuses for some reason the
                    # pre-flight check above did not cover. Not fatal to the
                    # rest of the sync.
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
                _fit_virtual_camera_source(ws, width, height)

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
        identify: dict = {"op": 1, "d": {"rpcVersion": 1}}
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
