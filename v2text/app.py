"""The running app: hotkeys -> recorder -> pipeline -> paste."""
from __future__ import annotations

import logging
import queue
import threading

from .audio import Recorder
from .cleanup import Cleaner
from .config import Config, app_home
from .context import active_app
from .history import History
from .pipeline import Pipeline
from .store import AudioStore
from .stt import WhisperEngine

log = logging.getLogger("v2text")

STATUS_MSG = {
    "no_speech": "No speech detected (audio kept for retry).",
    "too_short": "Too short - hold the key while you speak.",
    "blocked": "Blocked app - nothing transcribed.",
}


def build_pipeline(cfg: Config, injector=None) -> Pipeline:
    home = app_home()
    return Pipeline(
        cfg,
        engine=WhisperEngine(cfg.whisper_model, cfg.whisper_device, offline_only=cfg.offline_only),
        cleaner=Cleaner(cfg.claude_model, cfg.effort),
        injector=injector,
        history=History(home / "history.sqlite3"),
        store=AudioStore(home / "audio", cfg.audio_retention, cfg.audio_retention_days),
        app_detector=active_app,
    )


class App:
    def __init__(self, cfg: Config):
        from .inject import Injector
        self.cfg = cfg
        self.injector = Injector(cfg.restore_clipboard)
        self.pipeline = build_pipeline(cfg, self.injector)
        self.rec = Recorder(cfg.sample_rate, cfg.warm_mic, cfg.preroll_ms, cfg.tail_ms)
        self.jobs: queue.Queue = queue.Queue()
        self._selection = ""

    # hotkey callbacks (listener thread) - keep them fast
    def on_start(self, mode: str):
        if mode == "command":
            self._selection = self.injector.get_selection()
        self.rec.start()
        print(f"\r● recording ({mode})...        ", end="", flush=True)

    def on_stop(self, mode: str, discard: bool):
        audio = self.rec.stop()
        if discard:
            print("\r  (double-tap to go hands-free)", end="", flush=True)
            return
        print("\r… processing                   ", end="", flush=True)
        self.jobs.put((mode, audio, self._selection))

    def _worker(self):
        while True:
            mode, audio, sel = self.jobs.get()
            try:
                s = (self.pipeline.command(audio, sel) if mode == "command"
                     else self.pipeline.dictate(audio))
            except Exception:
                log.exception("pipeline crashed")
                continue
            if s.status == "ok":
                tag = f"[{s.clean_method}{' · ' + s.style if s.style else ''}]"
                print(f"\r✓ #{s.id} {tag} {s.clean}")
            else:
                print(f"\r✗ #{s.id} {s.status}: {s.error or STATUS_MSG.get(s.status, '')}")
            for w in (s.report.warnings if s.report else []):
                print(f"   ⚠ {w}")
            for n in s.notes:
                print(f"   ℹ {n}")

    def run(self):
        from .hotkey import HotkeyListener
        cfg = self.cfg
        self.pipeline.store.purge_expired()
        print("v2Text is running.")
        print(f"  Hold [{cfg.hotkey}] to dictate · double-tap for hands-free · Ctrl+C to quit")
        print(f"  Language: {cfg.language} · Engine: faster-whisper/{cfg.whisper_model} · "
              f"{'OFFLINE ONLY' if cfg.offline_only else 'online allowed'}")
        if cfg.ai_enabled:
            print(f"  AI cleanup ON: transcribed TEXT (not audio) is sent to Anthropic ({cfg.claude_model}).")
            print(f"  Hold [{cfg.command_hotkey}] with text selected to edit it by voice.")
        else:
            print("  Cleanup: rule-based (local). Voice command mode is off.")
        print("  Loading speech model...", flush=True)
        self.pipeline.engine.load()
        self.rec.open_warm()
        threading.Thread(target=self._worker, daemon=True).start()
        print("  Ready.\n")
        try:
            HotkeyListener(cfg, self.on_start, self.on_stop).run()
        except KeyboardInterrupt:
            pass
        finally:
            self.rec.shutdown()
