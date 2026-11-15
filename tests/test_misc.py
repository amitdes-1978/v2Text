import numpy as np

from v2text import bench
from v2text.audio import load_wav, resample, save_wav
from v2text.config import Config
from v2text.diagnostics import analyze
from v2text.hotkey import HotkeyStateMachine
from v2text.styles import style_for_app

SR = 16000


def test_wer_cer():
    assert bench.wer("send the LNG report", "send the LNG report.") == 0
    assert bench.wer("a b c d", "a x c") == 0.5
    assert bench.cer("abcd", "abxd") == 0.25
    assert bench.critical_hits(["LNG", "3 pm"], "the lng at 3 PM") == (2, 2)
    assert bench.boundary_loss("hello there friend", "there friend")["first_word_lost"]


def test_wer_unicode_scripts():
    assert bench.wer("मैं कल आऊंगा।", "मैं कल आऊंगा") == 0


def test_diagnostics_flags():
    assert not analyze(np.zeros(SR, np.float32)).speech_detected
    loud = np.ones(SR, np.float32)
    r = analyze(loud)
    assert r.clipping_pct > 50 and any("clipping" in w for w in r.warnings)
    assert any("first word" in w for w in r.warnings)


def test_wav_roundtrip_and_resample(tmp_path):
    a = (0.3 * np.sin(np.linspace(0, 400, 48000))).astype(np.float32)
    save_wav(tmp_path / "x.wav", a, 48000)
    b = load_wav(tmp_path / "x.wav", 16000)
    assert abs(b.size - 16000) <= 1
    assert resample(a, 16000, 16000) is a


def test_style_for_app():
    styles = Config().app_styles
    assert style_for_app("Slack - general", styles) == "casual"
    assert style_for_app("Mozilla Thunderbird", styles) == "formal"
    assert style_for_app(None, styles) == "default"


def test_config_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("V2TEXT_HOME", str(tmp_path))
    c = Config()
    c.snippets["sig"] = "Regards"
    c.save()
    c2 = Config.load()
    assert c2.snippets == {"sig": "Regards"} and c2.offline_only and not c2.ai_enabled
    assert "LNG" in c2.dictionary_terms() and c2.alias_map()["command center"] == "Command Centre"


class Clock:
    t = 0.0

    def __call__(self):
        return self.t


def test_hold_to_talk_and_double_tap_lock():
    ev, clk = [], Clock()
    sm = HotkeyStateMachine(lambda m: ev.append(("start", m)), lambda m, d: ev.append(("stop", m, d)),
                            0.35, clock=clk)
    sm.press("dictation"); clk.t = 2.0; sm.release("dictation")          # hold
    assert ev == [("start", "dictation"), ("stop", "dictation", False)]
    ev.clear()
    clk.t = 10.0; sm.press("dictation"); clk.t = 10.1; sm.release("dictation")  # tap 1 (discard)
    clk.t = 10.2; sm.press("dictation"); clk.t = 10.3; sm.release("dictation")  # tap 2 -> lock
    assert sm.locked
    clk.t = 15.0; sm.press("dictation"); sm.release("dictation")              # stop tap
    assert ev == [("start", "dictation"), ("stop", "dictation", True),
                  ("start", "dictation"), ("stop", "dictation", False)]
    assert not sm.locked
