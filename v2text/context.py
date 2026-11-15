"""Which app is in front? Used to choose a writing style (context awareness)."""
from __future__ import annotations

import subprocess
import sys


def _run(cmd: list[str]) -> str | None:
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=1.0)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def active_app() -> str | None:
    if sys.platform == "darwin":
        return _run(["osascript", "-e",
                     'tell application "System Events" to get name of first process whose frontmost is true'])
    if sys.platform == "win32":
        try:
            import ctypes
            import ctypes.wintypes as wt
            user32 = ctypes.windll.user32
            hwnd = user32.GetForegroundWindow()
            n = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(hwnd, buf, n + 1)
            return buf.value or None
        except Exception:
            return None
    # Linux / X11 (xdotool). Wayland compositors generally don't expose this.
    name = _run(["xdotool", "getactivewindow", "getwindowclassname"])
    title = _run(["xdotool", "getactivewindow", "getwindowname"])
    return " - ".join(x for x in (name, title) if x) or None
