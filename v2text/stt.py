"""Speech-to-text engines behind one interface.

Default (and only bundled) engine: local faster-whisper. The model is
downloaded once with `v2text model download`; afterwards, with offline_only on,
it is loaded with local_files_only=True so no network access is attempted.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Protocol

import numpy as np

from .languages import LanguageProfile

log = logging.getLogger(__name__)


class STTError(RuntimeError):
    """Recognition failed (model missing/corrupt, decode error). Audio must be kept for retry."""


@dataclass
class Transcript:
    text: str                 # verbatim engine output - never edited afterwards
    engine: str
    model: str
    language: str | None      # what the engine reports, if anything
    seconds: float            # processing time


class SpeechEngine(Protocol):
    name: str

    def transcribe(self, audio: np.ndarray, sample_rate: int, profile: LanguageProfile,
                   hint_words: list[str]) -> Transcript: ...


class WhisperEngine:
    name = "faster-whisper"

    def __init__(self, model: str = "small", device: str = "auto", offline_only: bool = True):
        self.model_name, self.device, self.offline_only = model, device, offline_only
        self._model = None

    def load(self):
        if self._model is None:
            try:
                from faster_whisper import WhisperModel
            except ImportError as e:
                raise STTError("faster-whisper is not installed: pip install faster-whisper") from e
            compute = "int8" if self.device in ("auto", "cpu") else "float16"
            try:
                self._model = WhisperModel(self.model_name, device=self.device, compute_type=compute,
                                           local_files_only=self.offline_only)
            except Exception as e:  # missing or corrupt model files
                hint = (" Run `v2text model download` once (needs internet)." if self.offline_only else "")
                raise STTError(f"Could not load Whisper model '{self.model_name}': {str(e).rstrip('.')}.{hint}") from e
        return self._model

    def transcribe(self, audio, sample_rate, profile, hint_words) -> Transcript:
        if sample_rate != 16000:
            raise STTError(f"Whisper expects 16 kHz audio, got {sample_rate} Hz")
        prompt = " ".join(x for x in [profile.prompt,
                                      ("Vocabulary: " + ", ".join(hint_words) + ".") if hint_words else ""] if x)
        t0 = time.perf_counter()
        try:
            segments, info = self.load().transcribe(
                audio.astype(np.float32), language=profile.whisper_language,
                initial_prompt=prompt or None, vad_filter=True, beam_size=5,
                condition_on_previous_text=False,
            )
            text = " ".join(s.text.strip() for s in segments).strip()
        except STTError:
            raise
        except Exception as e:
            raise STTError(f"Whisper decode failed: {e}") from e
        return Transcript(text, self.name, self.model_name, getattr(info, "language", None),
                          time.perf_counter() - t0)


def download_model(name: str) -> str:
    """Explicit, user-initiated download into the Hugging Face cache."""
    from faster_whisper.utils import download_model as dl
    return dl(name)
