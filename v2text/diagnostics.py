"""Signal-quality checks on each recording (blueprint section 7.2)."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field

import numpy as np

FRAME_MS = 20


@dataclass
class AudioReport:
    duration_s: float
    rms_dbfs: float
    peak_dbfs: float
    clipping_pct: float
    silence_ratio: float
    onset_ms: float | None        # first voiced frame
    trailing_silence_ms: float | None
    speech_detected: bool
    warnings: list[str] = field(default_factory=list)

    def as_dict(self):
        return asdict(self)


def _db(x: float) -> float:
    return float(20 * np.log10(max(x, 1e-9)))


def analyze(audio: np.ndarray, sr: int = 16000, silence_dbfs: float = -45.0) -> AudioReport:
    if audio.size == 0:
        return AudioReport(0, -180, -180, 0, 1, None, None, False, ["empty recording"])
    a = audio.astype(np.float32)
    rms, peak = float(np.sqrt(np.mean(a ** 2))), float(np.max(np.abs(a)))
    clip = float(np.mean(np.abs(a) >= 0.99) * 100)
    n = max(1, int(sr * FRAME_MS / 1000))
    frames = a[: a.size // n * n].reshape(-1, n) if a.size >= n else a.reshape(1, -1)
    fdb = 20 * np.log10(np.sqrt(np.mean(frames ** 2, axis=1)) + 1e-9)
    voiced = fdb > silence_dbfs
    speech = bool(voiced.sum() >= 3)  # >= 60 ms of signal above the floor
    onset = float(np.argmax(voiced) * FRAME_MS) if speech else None
    trailing = float((len(voiced) - 1 - np.flatnonzero(voiced)[-1]) * FRAME_MS) if speech else None
    r = AudioReport(round(a.size / sr, 3), round(_db(rms), 1), round(_db(peak), 1), round(clip, 3),
                    round(float(1 - voiced.mean()), 3), onset, trailing, speech)
    if not speech:
        r.warnings.append("no speech detected (check mic selection / permission / input gain)")
    else:
        if _db(rms) < -38:
            r.warnings.append("input is very quiet: move closer or raise mic gain")
        if clip > 0.1:
            r.warnings.append(f"clipping on {clip:.2f}% of samples: lower mic gain")
        if onset is not None and onset < FRAME_MS * 2:
            r.warnings.append("speech starts at the very first frame: first word may be clipped (try warm_mic)")
        if trailing is not None and trailing < FRAME_MS * 2:
            r.warnings.append("speech runs to the last frame: last word may be cut (raise tail_ms)")
    return r
