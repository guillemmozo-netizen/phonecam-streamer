"""Manifest declarations that code alone cannot make true.

A permission that isn't declared doesn't fail at build time — it fails at
runtime, as a SecurityException, in whatever the calling code does with it.
FrameCast wraps its audio-routing calls in runCatching, so a missing permission
degraded into "recorded the wrong microphone and said nothing". These assert the
declarations exist, which is the only place that can be checked without a device.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MANIFEST = REPO_ROOT / "android-app" / "app" / "src" / "main" / "AndroidManifest.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


@pytest.fixture(scope="module")
def manifest() -> ET.Element:
    return ET.parse(MANIFEST).getroot()


def _permissions(manifest: ET.Element) -> set[str]:
    return {
        e.get(f"{ANDROID_NS}name")
        for e in manifest.findall("uses-permission")
    }


# ---- C5: Bluetooth microphone routing ----


def test_modify_audio_settings_is_declared(manifest):
    """AudioManager.startBluetoothSco / setBluetoothScoOn / setCommunicationDevice
    all require it. Without it every SCO call throws SecurityException, the
    swallowing catch hides it, and capture silently falls back to the built-in
    microphone while the user believes they are on their Bluetooth mic."""
    assert "android.permission.MODIFY_AUDIO_SETTINGS" in _permissions(manifest)


def test_bluetooth_connect_is_declared(manifest):
    """Android 12+ needs it to enumerate and route to Bluetooth audio devices."""
    assert "android.permission.BLUETOOTH_CONNECT" in _permissions(manifest)
