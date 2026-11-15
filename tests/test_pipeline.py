import numpy as np
import pytest

from v2text.cleanup import Cleaner
from v2text.config import Config
from v2text.history import History
from v2text.pipeline import Pipeline
from v2text.store import AudioStore
from v2text.stt import STTError, Transcript

SR = 16000


def speech(seconds=1.0):
    t = np.arange(int(SR * seconds)) / SR
    a = 0.2 * np.sin(2 * np.pi * 220 * t) * (t > 0.2) * (t < seconds - 0.2)
    return a.astype(np.float32)


class FakeEngine:
    name, model_name = "fake", "fake-1"

    def __init__(self, text="um hello world", fail=False):
        self.text, self.fail, self.calls = text, fail, 0

    def transcribe(self, audio, sr, profile, hints):
        self.calls += 1
        if self.fail:
            raise STTError("model missing")
        return Transcript(self.text, self.name, self.model_name, "en", 0.01)


class FakeInjector:
    def __init__(self, fail=False):
        self.pasted, self.fail = [], fail

    def paste(self, text):
        if self.fail:
            raise RuntimeError("no clipboard")
        self.pasted.append(text)


@pytest.fixture
def env(tmp_path):
    cfg = Config()
    hist = History(tmp_path / "h.db")
    store = AudioStore(tmp_path / "audio", "on_failure")
    return cfg, hist, store, tmp_path


def make(env, engine=None, injector=None, cleaner=None):
    cfg, hist, store, _ = env
    return Pipeline(cfg, engine or FakeEngine(), cleaner or Cleaner(client=object()),
                    injector if injector is not None else FakeInjector(), hist, store)


def test_ok_keeps_verbatim_separate(env):
    inj = FakeInjector()
    s = make(env, injector=inj).dictate(speech())
    assert s.status == "ok"
    assert s.verbatim == "um hello world" and s.clean == "Hello world"
    assert inj.pasted == ["Hello world "]
    row = env[1].get(s.id)
    assert row["verbatim"] == "um hello world" and row["inserted"] == 1
    assert s.audio_path is None  # success + on_failure policy -> audio deleted


def test_silence_is_no_speech_not_error_and_audio_kept(env):
    eng = FakeEngine()
    s = make(env, engine=eng).dictate(np.zeros(SR, np.float32))
    assert s.status == "no_speech" and eng.calls == 0
    assert s.audio_path is not None and s.audio_path.exists()


def test_too_short(env):
    s = make(env).dictate(speech(0.2))
    assert s.status == "too_short"


def test_stt_error_retains_audio_for_retry(env):
    s = make(env, engine=FakeEngine(fail=True)).dictate(speech())
    assert s.status == "stt_error" and "model missing" in s.error
    assert s.audio_path.exists()
    # retry from the stored audio, with a working engine, without re-recording
    from v2text.audio import load_wav
    s2 = make(env).retry(s.id, load_wav(s.audio_path), s.audio_path)
    assert s2.status == "ok" and s2.id != s.id
    assert env[1].get(s.id)["status"] == "stt_error"  # original record untouched


def test_paste_failure_is_separate_from_transcription(env):
    s = make(env, injector=FakeInjector(fail=True)).dictate(speech())
    assert s.status == "paste_failed" and s.clean == "Hello world" and not s.inserted


def test_blocked_app(env):
    eng = FakeEngine()
    s = make(env, engine=eng).dictate(speech(), app="KeePassXC")
    assert s.status == "blocked" and eng.calls == 0


def test_offline_only_never_calls_ai(env):
    class Boom:
        @property
        def beta(self):
            raise AssertionError("network used in offline mode")
    cfg = env[0]
    cfg.ai_cleanup, cfg.offline_only = True, True
    s = make(env, cleaner=Cleaner(client=Boom())).dictate(speech())
    assert s.clean_method == "rules"


def test_verbatim_is_immutable(env):
    s = make(env).dictate(speech())
    with pytest.raises(ValueError):
        env[1].update(s.id, verbatim="edited")


def test_command_mode_requires_ai(env):
    s = make(env).command(speech(), "some text")
    assert s.status == "stt_error" and "AI" in s.error
