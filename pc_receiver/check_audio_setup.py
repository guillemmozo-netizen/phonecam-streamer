"""Reports whether this PC can deliver the phone's microphone to an app.

Two jobs, one script:

  * Run by Install_PhoneCam.vbs, which uses the exit code to decide what to
    tell the user — the same pattern enable_obs_websocket.py already follows.
  * Run by hand as a diagnostic when audio isn't showing up:

        python -m pc_receiver.check_audio_setup

The thing being checked is the one part of the audio path this project
cannot ship: video reaches other apps through pyvirtualcam's system webcam,
but nothing in Python can register a system *microphone* — that is a
kernel-mode driver. So the phone's audio has to be played into a virtual
audio cable that the user installs once, and their conferencing app picks
that cable as its microphone. See docs/AUDIO.md.

Getting this wrong is silent and confusing in exactly the way a setup step
should not be: audio arrives, decodes, plays perfectly — out of the PC's
speakers, where the call cannot hear it and the user gets an echo. Hence
checking at install time rather than leaving it to a warning in a log.

Exit codes:
    0  a virtual cable is installed — nothing to do
    2  audio works, but there is no cable, so audio cannot reach a call
    3  no usable audio output at all on this PC
"""

from __future__ import annotations

import sys

from pc_receiver.audio_sinks import VIRTUAL_CABLE_HINTS, list_output_devices

EXIT_READY = 0
EXIT_NO_CABLE = 2
EXIT_NO_AUDIO = 3


def find_virtual_cables() -> list:
    return [
        device for device in list_output_devices()
        if any(hint in device["name"].lower() for hint in VIRTUAL_CABLE_HINTS)
    ]


def main() -> int:
    devices = list_output_devices()
    if not devices:
        print("No audio output devices found on this PC.")
        print("The phone's video will still work; its microphone will not be usable.")
        return EXIT_NO_AUDIO

    cables = find_virtual_cables()
    if cables:
        best = min(cables, key=lambda d: d.get("default_low_output_latency", 1.0))
        print(f"Virtual audio cable found: {best['name']} (via {best['hostapi_name']})")
        print("The phone's microphone will be sent here automatically.")
        print(f"Set your conferencing app's microphone to \"{best['name']}\".")
        return EXIT_READY

    print("No virtual audio cable is installed.")
    print()
    print("Video will work as normal. Audio will play out of this PC's speakers")
    print("instead of reaching Zoom/Meet/OBS, which is an echo rather than a")
    print("microphone. To fix it, install VB-CABLE (free) from:")
    print("    https://vb-audio.com/Cable/")
    print("then set your conferencing app's microphone to \"CABLE Output\".")
    print("Nothing needs configuring here — the cable is picked up by name.")
    print()
    print(f"Audio output devices detected ({len(devices)}):")
    for device in sorted(devices, key=lambda d: d["name"]):
        print(f"    {device['name']} (via {device['hostapi_name']})")
    return EXIT_NO_CABLE


if __name__ == "__main__":
    sys.exit(main())
