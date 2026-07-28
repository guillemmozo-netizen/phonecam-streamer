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
from typing import Optional

log = logging.getLogger("pc_receiver.obs_sync")

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

    Best-effort throughout: if the scene or the source can't be found, the rest
    of the sync is still worth doing.
    """
    scene_status = _request(ws, "GetCurrentProgramScene", "phonecam-scene", {})
    scene = (scene_status.get("responseData") or {}).get("sceneName")
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


def sync_video_settings(
    width: int,
    height: int,
    fps: int,
    bitrate_bps: int = 0,
    audio_bitrate_bps: int = 0,
    sample_rate: int = 0,
) -> bool:
    """Sets OBS's canvas/output resolution+fps to match the phone's stream,
    and (best-effort, only takes effect if the profile is in Simple output
    mode) its video bitrate. Returns whether the sync actually went through —
    callers can log it, but there's nothing to react to either way.
    """
    try:
        import websocket
    except ImportError:
        log.warning("obs_sync: 'websocket-client' not installed (pip install -r requirements.txt) — skipping")
        return False

    cfg = _read_obs_websocket_config()
    if not cfg or not cfg.get("server_enabled"):
        log.info(
            "obs_sync: OBS WebSocket server is off — enable it once in OBS "
            "(Tools > WebSocket Server Settings > Enable WebSocket server) to use auto-sync",
        )
        return False

    port = cfg.get("server_port", 4455)
    password = cfg.get("server_password", "")

    ws = None
    try:
        ws = websocket.create_connection(f"ws://127.0.0.1:{port}", timeout=3)

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
            log.warning("obs_sync: OBS WebSocket handshake failed (wrong password?): %s", identified)
            return False

        video_request = {
            "op": 6,
            "d": {
                "requestType": "SetVideoSettings",
                "requestId": "phonecam-sync-video",
                "requestData": {
                    "fpsNumerator": fps,
                    "fpsDenominator": 1,
                    "baseWidth": width,
                    "baseHeight": height,
                    "outputWidth": width,
                    "outputHeight": height,
                },
            },
        }
        ws.send(json.dumps(video_request))
        video_status = json.loads(ws.recv()).get("d", {}).get("requestStatus", {})
        if video_status.get("result"):
            log.info("obs_sync: OBS canvas set to %sx%s@%sfps", width, height, fps)
        else:
            # Most common cause: OBS has an active output (recording/streaming/
            # its own "Start Virtual Camera") — SetVideoSettings always rejects
            # while any output is running. Not fatal to the rest of the sync.
            log.warning("obs_sync: SetVideoSettings rejected: %s", video_status.get("comment"))

        # Profile parameters below only take effect in Simple output mode, and
        # OBS silently ignores them otherwise — all best-effort.
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
            # SimpleOutput, and OBS only picks it up on the next profile load —
            # worth setting anyway so a restart lands on the right value.
            _request(ws, "SetProfileParameter", "phonecam-sync-samplerate", {
                "parameterCategory": "Audio",
                "parameterName": "SampleRate",
                "parameterValue": str(sample_rate),
            })

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
