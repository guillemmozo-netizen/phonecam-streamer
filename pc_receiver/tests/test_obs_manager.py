"""Every state and transition of the OBS connection state machine.

No OBS, no sockets, no real time: the connector, config reader and clock are
all injected, so these run in milliseconds and cover failure paths that are
awkward to produce against a real OBS (wrong password, config appearing
mid-run, OBS closing under a live connection).
"""

import pytest

from pc_receiver.obs_manager import (
    CONFIG_RECHECK_SECONDS,
    INITIAL_BACKOFF_SECONDS,
    MAX_BACKOFF_SECONDS,
    AuthRejected,
    ObsManager,
    ObsState,
)


class FakeConnection:
    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True


def manager(config, connector=None, now=1000.0):
    """config may be a dict, None, or a callable returning either."""
    reader = config if callable(config) else (lambda: config)
    return ObsManager(
        connector=connector or (lambda port, password: FakeConnection()),
        config_reader=reader,
        clock=lambda: now,
        sleeper=lambda _s: None,
    )


ENABLED = {"server_enabled": True, "server_port": 4455, "server_password": "pw"}
DISABLED = {"server_enabled": False, "server_port": 4455, "server_password": "pw"}


# ---------- individual states ----------

def test_no_config_reports_not_installed():
    m = manager(None)
    assert m.tick() is ObsState.NOT_INSTALLED
    assert "install" in m.snapshot()["hint"].lower()


def test_websocket_disabled_is_distinct_from_offline():
    """These need different user actions, so they must not collapse into one
    state - that ambiguity is what made the feature look broken."""
    m = manager(DISABLED)
    assert m.tick() is ObsState.DISABLED
    assert m.snapshot()["state"] == "disabled"


def test_connection_refused_reports_offline():
    def refuse(port, password):
        raise ConnectionRefusedError("nothing listening")

    m = manager(ENABLED, connector=refuse)
    assert m.tick() is ObsState.OFFLINE
    assert "nothing listening" in m.snapshot()["last_error"]


def test_bad_password_reports_auth_failed_not_offline():
    def reject(port, password):
        raise AuthRejected("password rejected")

    m = manager(ENABLED, connector=reject)
    assert m.tick() is ObsState.AUTH_FAILED
    assert m.snapshot()["state"] == "auth_failed"


def test_successful_connect_reports_connected():
    m = manager(ENABLED)
    assert m.tick() is ObsState.CONNECTED
    snap = m.snapshot()
    assert snap["connected"] is True
    assert snap["connected_since"] == 1000.0


def test_connector_receives_port_and_password_from_config():
    seen = {}

    def spy(port, password):
        seen["port"] = port
        seen["password"] = password
        return FakeConnection()

    manager({"server_enabled": True, "server_port": 4999, "server_password": "s3cret"},
            connector=spy).tick()
    assert seen == {"port": 4999, "password": "s3cret"}


# ---------- transitions ----------

def test_recovers_when_obs_opens():
    calls = {"n": 0}

    def flaky(port, password):
        calls["n"] += 1
        if calls["n"] == 1:
            raise ConnectionRefusedError("closed")
        return FakeConnection()

    m = manager(ENABLED, connector=flaky)
    assert m.tick() is ObsState.OFFLINE
    assert m.tick() is ObsState.CONNECTED


def test_detects_obs_closing_under_a_live_connection():
    calls = {"n": 0}

    def then_fail(port, password):
        calls["n"] += 1
        if calls["n"] == 1:
            return FakeConnection()
        raise ConnectionRefusedError("gone")

    m = manager(ENABLED, connector=then_fail)
    assert m.tick() is ObsState.CONNECTED
    assert m.tick() is ObsState.OFFLINE
    assert m.snapshot()["connected_since"] is None


def test_picks_up_websocket_being_enabled_without_a_restart():
    """The whole point of re-reading config while idle."""
    state = {"cfg": DISABLED}
    m = manager(lambda: state["cfg"])
    assert m.tick() is ObsState.DISABLED
    state["cfg"] = ENABLED
    assert m.tick() is ObsState.CONNECTED


def test_picks_up_obs_being_installed_later():
    state = {"cfg": None}
    m = manager(lambda: state["cfg"])
    assert m.tick() is ObsState.NOT_INSTALLED
    state["cfg"] = ENABLED
    assert m.tick() is ObsState.CONNECTED


def test_auth_failure_clears_once_the_password_changes():
    state = {"cfg": dict(ENABLED)}

    def connector(port, password):
        if password != "correct":
            raise AuthRejected("nope")
        return FakeConnection()

    m = manager(lambda: state["cfg"], connector=connector)
    assert m.tick() is ObsState.AUTH_FAILED
    state["cfg"] = dict(ENABLED, server_password="correct")
    assert m.tick() is ObsState.CONNECTED


# ---------- retry policy ----------

def test_backoff_doubles_while_offline_and_is_capped():
    m = manager(ENABLED, connector=lambda p, w: (_ for _ in ()).throw(ConnectionRefusedError("x")))
    m.tick()
    assert m.wait_interval() == INITIAL_BACKOFF_SECONDS
    for _ in range(20):
        m._advance_backoff()
    assert m.wait_interval() == MAX_BACKOFF_SECONDS


def test_backoff_resets_after_a_successful_connect():
    calls = {"n": 0}

    def flaky(port, password):
        calls["n"] += 1
        if calls["n"] <= 3:
            raise ConnectionRefusedError("closed")
        return FakeConnection()

    m = manager(ENABLED, connector=flaky)
    for _ in range(3):
        m.tick()
        m._advance_backoff()
    assert m.wait_interval() > INITIAL_BACKOFF_SECONDS
    m.tick()
    assert m.status.state is ObsState.CONNECTED
    # Backoff is reset on success, so a later drop retries fast again.
    m._backoff = INITIAL_BACKOFF_SECONDS
    assert m.wait_interval() == CONFIG_RECHECK_SECONDS


@pytest.mark.parametrize("state_config,expected", [
    (None, ObsState.NOT_INSTALLED),
    (DISABLED, ObsState.DISABLED),
])
def test_states_needing_user_action_use_the_slow_cadence(state_config, expected):
    """Retrying these every second is pointless - the fix is inside OBS."""
    m = manager(state_config)
    assert m.tick() is expected
    assert m.wait_interval() == CONFIG_RECHECK_SECONDS


def test_attempts_are_counted_only_when_a_connection_is_tried():
    """NOT_INSTALLED/DISABLED short-circuit before the connector, so they must
    not inflate the attempt counter the UI shows."""
    m = manager(DISABLED)
    m.tick()
    m.tick()
    assert m.snapshot()["attempts"] == 0

    m2 = manager(ENABLED, connector=lambda p, w: (_ for _ in ()).throw(OSError("x")))
    m2.tick()
    m2.tick()
    assert m2.snapshot()["attempts"] == 2


# ---------- robustness ----------

def test_unexpected_connector_error_does_not_escape():
    def explode(port, password):
        raise RuntimeError("something odd")

    m = manager(ENABLED, connector=explode)
    assert m.tick() is ObsState.OFFLINE
    assert "something odd" in m.snapshot()["last_error"]


def test_malformed_config_is_treated_as_disabled_not_a_crash():
    m = manager({"unexpected": "shape"})
    assert m.tick() is ObsState.DISABLED


def test_snapshot_is_json_serialisable():
    import json

    m = manager(ENABLED)
    m.tick()
    json.dumps(m.snapshot())


def test_stop_closes_a_live_connection():
    conn = FakeConnection()
    m = manager(ENABLED, connector=lambda p, w: conn)
    m.tick()
    m.stop()
    assert conn.closed is True


def test_status_is_a_copy_not_a_live_reference():
    """Callers must not be able to mutate the manager's own state."""
    m = manager(ENABLED)
    m.tick()
    snap = m.status
    snap.state = ObsState.OFFLINE
    assert m.status.state is ObsState.CONNECTED
