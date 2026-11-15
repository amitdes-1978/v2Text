"""Temporary audio retention so failed transcriptions can be retried without re-recording."""
from __future__ import annotations

import time
from pathlib import Path

import numpy as np

from .audio import save_wav


class AudioStore:
    def __init__(self, root: Path, policy: str = "on_failure", days: int = 14):
        if policy not in ("never", "on_failure", "always"):
            raise ValueError(f"unknown audio_retention policy: {policy}")
        self.root, self.policy, self.days = root, policy, days

    def save(self, audio: np.ndarray, sr: int) -> Path:
        """Save every take first; `finalize` decides whether to keep it."""
        p = self.root / f"{time.strftime('%Y%m%d-%H%M%S')}-{int(time.time() * 1000) % 1000:03d}.wav"
        save_wav(p, audio, sr)
        return p

    def finalize(self, path: Path | None, success: bool) -> Path | None:
        if path is None:
            return None
        keep = self.policy == "always" or (self.policy == "on_failure" and not success)
        if not keep:
            path.unlink(missing_ok=True)
            return None
        return path

    def purge_expired(self) -> int:
        if not self.root.exists():
            return 0
        cutoff, n = time.time() - self.days * 86400, 0
        for f in self.root.glob("*.wav"):
            if f.stat().st_mtime < cutoff:
                f.unlink(missing_ok=True)
                n += 1
        return n

    def delete_all(self) -> int:
        n = 0
        for f in self.root.glob("*.wav") if self.root.exists() else []:
            f.unlink(missing_ok=True)
            n += 1
        return n
