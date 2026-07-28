"""Connection state machine for OBS.

obs_sync.py opens a fresh WebSocket per call and reports nothing between calls,
so the app could never answer "is OBS reachable right now, and if not, why?".
That distinction matters: OBS not installed, OBS installed but WebSocket
disabled, OBS closed, and a wrong password are four different problems with
four different fixes, and all of them previously surfaced as the same silent
no-op.

This owns detection, retry policy and live status. It deliberately does not own
*what* gets synced - that stays in obs_sync - so the two can be tested apart.

Everything is injectable (connector, config reader, clock, sleeper) so the whole
state machine is exercised in tests without OBS, a socket, or real time.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Optional

log = logging.getLogger("pc_receiver.obs_manager")

# Retry policy. Doubling from 1s to a 30s ceiling: fast enough that opening OBS
# feels instant, slow enough that a PC left running for hours with OBS closed
# isn't opening a socket every second forever.
INITIAL_BACKOFF_SECONDS = 1.0
MAX_BACKOFF_SECONDS = 30.0

# Config is re-read on this cadence even while idle, so enabling the WebSocket
# server in OBS is picked up without restarting anything.
CONFIG_RECHECK_SECONDS = 15.0


class ObsState(str, Enum):
    """Why OBS is or isn't usable. String-valued so it serialises straight to
    JSON for the phone's status UI."""

    NOT_INSTALLED = "not_installed"   # no obs-websocket config on disk
    DISABLED = "disabled"             # installed, WebSocket server turned off
    OFFLINE = "offline"               # enabled, but nothing listening (OBS closed)
    CONNECTING = "connecting"
    CONNECTED = "connected"
    AUTH_FAILED = "auth_failed"       # reachable, password rejected


# States where retrying quickly is pointless: the user has to do something in
# OBS first. Polling these on the fast backoff would just burn cycles.
_USER_ACTION_REQUIRED = {ObsState.NOT_INSTALLED, ObsState.DISABLED, ObsState.AUTH_FAILED}

_HINTS = {
    ObsState.NOT_INSTALLED: "OBS not found - install it and open it once",
    ObsState.DISABLED: "Enable OBS WebSocket: Tools > WebSocket Server Settings",
    ObsState.OFFLINE: "OBS is closed - open it",
    ObsState.CONNECTING: "Connecting to OBS...",
    ObsState.CONNECTED: "Connected to OBS",
    ObsState.AUTH_FAILED: "OBS rejected the password - reopen OBS to re-read it",
}


@dataclass
class ObsStatus:
    state: ObsState = ObsState.OFFLINE
    hint: str = ""
    last_error: str = ""
    connected_since: Optional[float] = None
    attempts: int = 0
    last_attempt: Optional[float] = None
    next_retry_in: float = 0.0

    def to_dict(self) -> dict:
        return {
            "state": self.state.value,
            "connected": self.state is ObsState.CONNECTED,
            "hint": self.hint or _HINTS.get(self.state, ""),
            "last_error": self.last_error,
            "connected_since": self.connected_since,
            "attempts": self.attempts,
            "next_retry_in": round(self.next_retry_in, 1),
        }


class AuthRejected(Exception):
    """OBS answered but refused the password - retrying with the same one
    cannot succeed, so this is kept distinct from a plain connection error."""


@dataclass
class ObsManager:
    """Keeps a live view of OBS reachability, retrying on its own schedule."""

    connector: Callable[[int, str], object]
    config_reader: Callable[[], Optional[dict]]
    clock: Callable[[], float] = time.monotonic
    sleeper: Callable[[float], None] = time.sleep

    _status: ObsStatus = field(default_factory=ObsStatus)
    _lock: threading.Lock = field(default_factory=threading.Lock)
    _backoff: float = INITIAL_BACKOFF_SECONDS
    _stop: threading.Event = field(default_factory=threading.Event)
    _thread: Optional[threading.Thread] = None
    _connection: object = None

    # ---------- public API ----------

    @property
    def status(self) -> ObsStatus:
        with self._lock:
            return ObsStatus(**vars(self._status))

    def snapshot(self) -> dict:
        return self.status.to_dict()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="obs-manager", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None
        self._close_connection()

    def tick(self) -> ObsState:
        """One evaluation of the state machine. Exposed so tests can drive it
        step by step instead of racing a background thread."""
        config = self.config_reader()

        if config is None:
            self._set(ObsState.NOT_INSTALLED)
            return ObsState.NOT_INSTALLED
        if not config.get("server_enabled"):
            self._set(ObsState.DISABLED)
            return ObsState.DISABLED

        self._set(ObsState.CONNECTING)
        port = int(config.get("server_port", 4455))
        password = str(config.get("server_password", ""))

        with self._lock:
            self._status.attempts += 1
            self._status.last_attempt = self.clock()

        try:
            self._connection = self.connector(port, password)
        except AuthRejected as e:
            # A wrong password is not transient. Stay here until the config
            # changes - OBS rewrites it when reopened - rather than hammering.
            self._set(ObsState.AUTH_FAILED, error=str(e))
            return ObsState.AUTH_FAILED
        except Exception as e:
            self._set(ObsState.OFFLINE, error=str(e))
            return ObsState.OFFLINE

        self._backoff = INITIAL_BACKOFF_SECONDS
        with self._lock:
            self._status.state = ObsState.CONNECTED
            self._status.hint = _HINTS[ObsState.CONNECTED]
            self._status.last_error = ""
            self._status.connected_since = self.clock()
            self._status.next_retry_in = 0.0
        return ObsState.CONNECTED

    def wait_interval(self) -> float:
        """How long to idle before the next tick, given the current state."""
        state = self.status.state
        if state is ObsState.CONNECTED:
            return CONFIG_RECHECK_SECONDS
        if state in _USER_ACTION_REQUIRED:
            # Re-read config on a slow, fixed cadence: the fix is in OBS, not
            # here, but we still want to notice the moment the user applies it.
            return CONFIG_RECHECK_SECONDS
        return self._backoff

    # ---------- internals ----------

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.tick()
            except Exception as e:  # a status watcher must never die
                log.warning("obs_manager: unexpected error: %s", e)
                self._set(ObsState.OFFLINE, error=str(e))
            interval = self.wait_interval()
            with self._lock:
                self._status.next_retry_in = interval
            if self.status.state is not ObsState.CONNECTED:
                self._advance_backoff()
            if self._stop.wait(interval):
                return

    def _advance_backoff(self) -> None:
        self._backoff = min(self._backoff * 2, MAX_BACKOFF_SECONDS)

    def _set(self, state: ObsState, error: str = "") -> None:
        with self._lock:
            if state is not ObsState.CONNECTED and self._status.state is ObsState.CONNECTED:
                self._status.connected_since = None
            self._status.state = state
            self._status.hint = _HINTS.get(state, "")
            if error:
                self._status.last_error = error

    def _close_connection(self) -> None:
        conn = self._connection
        self._connection = None
        if conn is None:
            return
        close = getattr(conn, "close", None)
        if callable(close):
            try:
                close()
            except Exception:
                pass
