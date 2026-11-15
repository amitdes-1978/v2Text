"""capture -> diagnostics -> recognition -> (immutable verbatim) -> cleanup -> insert.

Each stage reports its own outcome, so "no speech", "recognition failed" and
"paste failed" are distinguishable and independently retryable.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from . import textproc
from .cleanup import Cleaner
from .config import Config
from .diagnostics import AudioReport, analyze
from .languages import get_profile
from .stt import STTError
from .styles import style_for_app

log = logging.getLogger(__name__)


@dataclass
class Session:
    mode: str
    status: str                       # ok | no_speech | too_short | stt_error | paste_failed | blocked
    verbatim: str = ""
    clean: str = ""
    error: str | None = None
    app: str | None = None
    style: str | None = None
    report: AudioReport | None = None
    audio_path: Path | None = None
    clean_method: str = ""
    changes: list[str] = field(default_factory=list)
    stt_seconds: float = 0.0
    engine: str = ""
    model: str = ""
    inserted: bool = False
    id: int | None = None
    notes: list[str] = field(default_factory=list)


class Pipeline:
    def __init__(self, cfg: Config, engine, cleaner: Cleaner, injector=None, history=None,
                 store=None, app_detector=None):
        self.cfg, self.engine, self.cleaner = cfg, engine, cleaner
        self.injector, self.history, self.store = injector, history, store
        self.app_detector = app_detector or (lambda: None)

    # ------------------------------------------------------------------ helpers
    def _app(self) -> str | None:
        return self.app_detector() if self.cfg.context_awareness else None

    def _blocked(self, app: str | None) -> bool:
        return bool(app) and any(b in app.lower() for b in self.cfg.blocked_apps)

    def _hints(self) -> list[str]:
        return self.cfg.dictionary_terms()

    def _record(self, s: Session) -> Session:
        if self.history is not None and self.cfg.save_history:
            s.id = self.history.add(
                mode=s.mode, status=s.status, error=s.error, app=s.app, style=s.style,
                language=self.cfg.language, engine=s.engine, model=s.model, verbatim=s.verbatim,
                clean=s.clean, clean_method=s.clean_method, changes=s.changes,
                audio_path=str(s.audio_path) if s.audio_path else None,
                audio_seconds=s.report.duration_s if s.report else 0, stt_seconds=s.stt_seconds,
                diagnostics=s.report.as_dict() if s.report else None, inserted=int(s.inserted),
                words=textproc.word_count(s.clean))
        return s

    def _recognize(self, audio: np.ndarray, s: Session) -> bool:
        """Fill s.verbatim. Returns False (with s.status set) when there is nothing to clean."""
        s.report = analyze(audio, self.cfg.sample_rate)
        if s.report.duration_s < self.cfg.min_seconds:
            s.status = "too_short"
            return False
        if not s.report.speech_detected:
            s.status = "no_speech"
            return False
        try:
            t = self.engine.transcribe(audio, self.cfg.sample_rate, get_profile(self.cfg.language),
                                       self._hints())
        except STTError as e:
            s.status, s.error = "stt_error", str(e)
            return False
        s.verbatim, s.stt_seconds, s.engine, s.model = t.text, t.seconds, t.engine, t.model
        if not t.text.strip():
            s.status = "no_speech"
            return False
        return True

    def _keep_audio(self, audio, s: Session, success: bool):
        if self.store is None or self.store.policy == "never":
            return
        if s.audio_path is None:
            s.audio_path = self.store.save(audio, self.cfg.sample_rate)
        s.audio_path = self.store.finalize(s.audio_path, success)

    def _insert(self, s: Session, text: str):
        if self.injector is None:
            return
        try:
            self.injector.paste(text)
            s.inserted = True
        except Exception as e:  # transcript is kept; user can `v2text copy <id>`
            s.status, s.error = "paste_failed", f"insert failed: {e}"

    # ------------------------------------------------------------------ public
    def dictate(self, audio: np.ndarray, app: str | None = None,
                audio_path: Path | None = None) -> Session:
        app = app if app is not None else self._app()
        s = Session("dictation", "ok", app=app, audio_path=audio_path)
        if self._blocked(app):
            s.status = "blocked"
            s.notes.append("Active app is on the blocked list; nothing was transcribed or pasted.")
            return self._record(s)
        ok = self._recognize(audio, s)
        # keep audio on failure *and* on "no speech", so a misdetection can be retried
        self._keep_audio(audio, s, success=ok or s.status == "too_short")
        if not ok:
            return self._record(s)
        s.style = style_for_app(app, self.cfg.app_styles, self.cfg.default_style)
        out = self.cleaner.clean_dictation(s.verbatim, cfg=self.cfg, style=s.style, app=app)
        s.clean, s.clean_method, s.changes = out.text, out.method, out.changes
        if out.note:
            s.notes.append(out.note)
        self._insert(s, s.clean + " ")
        return self._record(s)

    def command(self, audio: np.ndarray, selection: str, app: str | None = None) -> Session:
        app = app if app is not None else self._app()
        s = Session("command", "ok", app=app)
        if not self.cfg.ai_enabled:
            s.status, s.error = "stt_error", "Command mode needs the optional AI cleanup (offline_only=false, ai_cleanup=true)."
            return s
        if self._blocked(app):
            s.status = "blocked"
            return self._record(s)
        if not self._recognize(audio, s):
            return self._record(s)
        out, err = self.cleaner.run_command(s.verbatim, selection, app=app)
        if out is None:
            s.status, s.error = "stt_error", err  # selection untouched
            return self._record(s)
        s.clean, s.clean_method = out, "claude-command"
        self._insert(s, out)
        return self._record(s)

    def retry(self, session_id: int, audio: np.ndarray, audio_path: Path | None = None) -> Session:
        """Re-transcribe stored audio. Creates a new session; the old verbatim is untouched."""
        s = self.dictate(audio, audio_path=audio_path)
        if self.history is not None and s.id is not None:
            self.history.update(s.id, error=(s.error or "") + f" [retry of #{session_id}]")
        return s
