"""Insert text at the cursor of the focused app; read the current selection.

Clipboard + simulated paste works in nearly every app. The previous clipboard
is restored afterwards. A failure raises, so the pipeline can mark the
session `paste_failed` and keep the transcript for `v2text copy <id>`.
"""
from __future__ import annotations

import sys
import time


class Injector:
    def __init__(self, restore_clipboard: bool = True):
        import pyperclip
        from pynput.keyboard import Controller, Key
        self._clip, self._kb = pyperclip, Controller()
        self._mod = Key.cmd if sys.platform == "darwin" else Key.ctrl
        self.restore_clipboard = restore_clipboard

    def _chord(self, key: str):
        with self._kb.pressed(self._mod):
            self._kb.press(key)
            self._kb.release(key)

    def _get(self) -> str | None:
        try:
            return self._clip.paste()
        except Exception:
            return None

    def paste(self, text: str):
        old = self._get()
        self._clip.copy(text)
        if self._get() != text:
            raise RuntimeError("clipboard is unavailable (on Linux install xclip, xsel or wl-clipboard)")
        time.sleep(0.05)
        self._chord("v")
        if self.restore_clipboard and old is not None:
            time.sleep(0.3)  # give the target app time to read the clipboard
            self._clip.copy(old)

    def copy_only(self, text: str):
        self._clip.copy(text)

    def get_selection(self) -> str:
        old, sentinel = self._get(), "⁣v2text⁣"
        self._clip.copy(sentinel)
        self._chord("c")
        time.sleep(0.15)
        sel = self._get()
        if old is not None:
            self._clip.copy(old)
        return "" if sel in (None, sentinel) else sel
