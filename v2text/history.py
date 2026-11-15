"""Local SQLite store: sessions, verbatim + clean transcripts, audio paths, status.

Nothing here leaves the machine. Verbatim text is stored separately from the
cleaned text and is never rewritten.
"""
from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path

SCHEMA = """CREATE TABLE IF NOT EXISTS sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  mode TEXT NOT NULL,              -- dictation | command
  status TEXT NOT NULL,            -- ok | no_speech | too_short | stt_error | paste_failed | blocked
  error TEXT,
  app TEXT, style TEXT, language TEXT,
  engine TEXT, model TEXT,
  verbatim TEXT,                   -- immutable engine output
  clean TEXT,                      -- what was / would be inserted
  clean_method TEXT, changes TEXT, -- audit trail of formatter steps (JSON list)
  audio_path TEXT, audio_seconds REAL, stt_seconds REAL,
  diagnostics TEXT,                -- JSON AudioReport
  inserted INTEGER NOT NULL DEFAULT 0,
  words INTEGER NOT NULL DEFAULT 0
)"""

COLUMNS = ["mode", "status", "error", "app", "style", "language", "engine", "model", "verbatim",
           "clean", "clean_method", "changes", "audio_path", "audio_seconds", "stt_seconds",
           "diagnostics", "inserted", "words"]


class History:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute(SCHEMA)

    def add(self, **kw) -> int:
        for k in ("changes", "diagnostics"):
            if k in kw and not isinstance(kw[k], (str, type(None))):
                kw[k] = json.dumps(kw[k])
        cols = [c for c in COLUMNS if c in kw]
        cur = self.db.execute(
            f"INSERT INTO sessions (ts, {', '.join(cols)}) VALUES (?, {', '.join('?' * len(cols))})",
            [time.time()] + [kw[c] for c in cols])
        self.db.commit()
        return cur.lastrowid

    def update(self, sid: int, **kw):
        if "verbatim" in kw:
            raise ValueError("verbatim transcripts are immutable; record a new session instead")
        for k in ("changes", "diagnostics"):
            if k in kw and not isinstance(kw[k], (str, type(None))):
                kw[k] = json.dumps(kw[k])
        sets = ", ".join(f"{k}=?" for k in kw)
        self.db.execute(f"UPDATE sessions SET {sets} WHERE id=?", [*kw.values(), sid])
        self.db.commit()

    def get(self, sid: int):
        return self.db.execute("SELECT * FROM sessions WHERE id=?", (sid,)).fetchone()

    def recent(self, n: int = 20):
        return self.db.execute("SELECT * FROM sessions ORDER BY id DESC LIMIT ?", (n,)).fetchall()

    def stats(self) -> dict:
        r = self.db.execute(
            "SELECT COUNT(*) n, COALESCE(SUM(words),0) w, COALESCE(SUM(audio_seconds),0) s,"
            " SUM(status='ok') ok, SUM(status='stt_error') err, SUM(status='no_speech') ns,"
            " SUM(status='paste_failed') pf, AVG(stt_seconds) lat"
            " FROM sessions WHERE mode='dictation'").fetchone()
        mins = (r["s"] or 0) / 60
        return {"sessions": r["n"], "ok": r["ok"] or 0, "no_speech": r["ns"] or 0,
                "stt_errors": r["err"] or 0, "paste_failed": r["pf"] or 0,
                "words": r["w"], "minutes_spoken": round(mins, 1),
                "avg_wpm": round(r["w"] / mins) if mins else 0,
                "avg_stt_latency_s": round(r["lat"] or 0, 2)}

    def clear(self):
        self.db.execute("DELETE FROM sessions")
        self.db.commit()
