"""Push-to-talk hotkeys.

- Hold `hotkey`           -> record; release -> dictate
- Double-tap `hotkey`     -> hands-free (locked) recording; tap once more to stop
- Hold `command_hotkey`   -> record an instruction applied to the selected text
"""
from __future__ import annotations

import time
from typing import Callable


def parse_key(name: str):
    from pynput import keyboard
    if hasattr(keyboard.Key, name):
        return getattr(keyboard.Key, name)
    return keyboard.KeyCode.from_char(name)


class HotkeyStateMachine:
    """Pure logic (no OS hooks) so it can be unit-tested.

    on_start(mode) begins capture; on_stop(mode, discard) ends it. `discard`
    is True for the first quick tap of a double-tap, so it isn't processed.
    """

    def __init__(self, on_start: Callable[[str], None], on_stop: Callable[[str, bool], None],
                 double_tap_window: float = 0.35, clock: Callable[[], float] = time.monotonic):
        self.on_start, self.on_stop = on_start, on_stop
        self.window, self.clock = double_tap_window, clock
        self.active: str | None = None
        self.locked = False
        self._pressed_at = 0.0
        self._last_tap = -1e9

    def press(self, mode: str):
        if self.locked or self.active is not None:
            return
        self.active, self._pressed_at = mode, self.clock()
        self.on_start(mode)

    def release(self, mode: str):
        now = self.clock()
        if self.locked:                         # the "stop" tap while hands-free
            self.locked, self.active = False, None
            self.on_stop(mode, False)
            return
        if self.active != mode:
            return
        quick = now - self._pressed_at < self.window
        if mode == "dictation" and quick:
            if now - self._last_tap < self.window * 2:
                self.locked = True              # second quick tap: keep recording
                return
            self._last_tap = now
            self.active = None
            self.on_stop(mode, True)            # first quick tap: discard
            return
        self.active = None
        self.on_stop(mode, False)


class HotkeyListener:
    def __init__(self, cfg, on_start, on_stop):
        from pynput import keyboard
        self._keyboard = keyboard
        self.dict_key, self.cmd_key = parse_key(cfg.hotkey), parse_key(cfg.command_hotkey)
        self.sm = HotkeyStateMachine(on_start, on_stop, cfg.double_tap_window)
        self._down: set = set()

    def _mode(self, key):
        return {self.dict_key: "dictation", self.cmd_key: "command"}.get(key)

    def _on_press(self, key):
        m = self._mode(key)
        if m and key not in self._down:          # ignore OS auto-repeat
            self._down.add(key)
            self.sm.press(m)

    def _on_release(self, key):
        m = self._mode(key)
        if m:
            self._down.discard(key)
            self.sm.release(m)

    def run(self):
        with self._keyboard.Listener(on_press=self._on_press, on_release=self._on_release) as l:
            l.join()
