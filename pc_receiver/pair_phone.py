"""Open a Wi-Fi pairing window on the running control server.

Run by Pair_Phone.bat; also usable directly:

    python -m pc_receiver.pair_phone

Talks to the already-running control_server over loopback rather than doing
the work itself, because the token and the window both live in that process —
it is the one holding the socket a phone will connect to. Reaching 127.0.0.1
is also the entire authorisation check: only something running on this PC can,
so running this file is the proof of physical access that a typed code would
otherwise have to stand in for.

Exit codes: 0 paired window open, 1 control server not reachable, 2 refused.
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

CONTROL_URL = "http://127.0.0.1:8790/pair/open"
TIMEOUT_SECONDS = 5


def main() -> int:
    request = urllib.request.Request(CONTROL_URL, data=b"", method="POST")
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"  FrameCast refused to open pairing ({e.code}).")
        return 2
    except (urllib.error.URLError, OSError):
        # By far the most likely cause, and the one worth naming: the services
        # only run while OBS is open (see FrameCast_Service.vbs).
        print("  Could not reach FrameCast on this PC.")
        print("  It runs while OBS is open - open OBS, then run this again.")
        return 1
    except json.JSONDecodeError:
        print("  FrameCast answered with something unexpected.")
        return 2

    seconds = int(payload.get("seconds_left", 0))
    print(f"  Ready to pair for the next {seconds} seconds.")
    print("  The first phone that asks gets paired, and the window closes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
