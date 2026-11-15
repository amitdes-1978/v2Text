"""Microphone capture with optional pre-roll and a tail after release.

- warm_mic=False (default): the stream opens on key press. Opening takes tens of
  ms on most machines; diagnostics flag recordings whose speech starts at
  sample 0 (likely first-word clipping), which tells you to try warm_mic.
- warm_mic=True: the stream stays open and a ring buffer keeps the last
  `preroll_ms` of audio, which is prepended when you press the key. Your OS mic
  indicator will stay on while v2Text runs.
- tail_ms: recording continues briefly after release so the last word survives.
"""
from __future__ import annotations

import collections
import threading
import time
import wave
from pathlib import Path

import numpy as np


class Recorder:
    def __init__(self, sample_rate: int = 16000, warm_mic: bool = False,
                 preroll_ms: int = 400, tail_ms: int = 300):
        self.sr, self.warm, self.tail_ms = sample_rate, warm_mic, tail_ms
        self._ring = collections.deque(maxlen=max(1, int(preroll_ms / 1000 * sample_rate / 512) + 1))
        self._chunks: list[np.ndarray] = []
        self._capturing = False
        self._stream = None
        self._lock = threading.Lock()
        self.level = 0.0
        self.timing: dict[str, float] = {}

    # sounddevice callback
    def _cb(self, indata, frames, time_info, status):
        mono = indata[:, 0].copy()
        with self._lock:
            if self._capturing:
                if "first_block" not in self.timing:
                    self.timing["first_block"] = time.monotonic()
                self._chunks.append(mono)
            elif self.warm:
                self._ring.append(mono)
        self.level = float(np.sqrt(np.mean(mono ** 2))) if mono.size else 0.0

    def _open(self):
        import sounddevice as sd
        if self._stream is None:
            self._stream = sd.InputStream(samplerate=self.sr, channels=1, dtype="float32",
                                          blocksize=512, callback=self._cb)
            self._stream.start()

    def _close(self):
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None

    def open_warm(self):
        if self.warm:
            self._open()

    def start(self):
        self.timing = {"pressed": time.monotonic()}
        with self._lock:
            self._chunks = list(self._ring) if self.warm else []
            self.timing["preroll_samples"] = sum(c.size for c in self._chunks)
            self._ring.clear()
            self._capturing = True
        self._open()
        self.timing["stream_live"] = time.monotonic()

    def stop(self) -> np.ndarray:
        self.timing["released"] = time.monotonic()
        time.sleep(self.tail_ms / 1000)
        with self._lock:
            self._capturing = False
            audio = np.concatenate(self._chunks) if self._chunks else np.zeros(0, np.float32)
            self._chunks = []
        if not self.warm:
            self._close()
        self.level = 0.0
        return audio

    def shutdown(self):
        self._close()


# ---------------------------------------------------------------- WAV I/O
def save_wav(path: Path, audio: np.ndarray, sr: int = 16000):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
    tmp = path.with_suffix(".tmp")
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    tmp.replace(path)  # atomic: never leave a half-written file


def load_wav(path: str | Path, target_sr: int = 16000) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        sr, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width != 2:
        raise ValueError(f"{path}: only 16-bit PCM WAV is supported")
    a = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    if sr != target_sr:
        a = resample(a, sr, target_sr)
    return a


def resample(audio: np.ndarray, src: int, dst: int) -> np.ndarray:
    """Band-limited enough for speech when downsampling 44.1/48k -> 16k."""
    if src == dst or audio.size == 0:
        return audio
    if src > dst:  # simple moving-average low-pass before decimation
        k = int(np.ceil(src / dst))
        audio = np.convolve(audio, np.ones(k) / k, mode="same")
    n = int(round(audio.size * dst / src))
    return np.interp(np.linspace(0, audio.size - 1, n), np.arange(audio.size), audio).astype(np.float32)
