"""The optional Claude pass, exercised with a fake client (no network)."""
from types import SimpleNamespace as NS

from v2text.cleanup import Cleaner
from v2text.config import Config


class FakeClient:
    def __init__(self, reply=None, stop="end_turn", exc=None):
        self.reply, self.stop, self.exc, self.sent = reply, stop, exc, []
        self.beta = NS(messages=NS(create=self._create))

    def _create(self, **kw):
        self.sent.append(kw)
        if self.exc:
            raise self.exc
        reply = self.reply(kw) if callable(self.reply) else self.reply
        return NS(stop_reason=self.stop, content=[NS(type="text", text=reply)])


def cfg():
    c = Config()
    c.offline_only, c.ai_cleanup = False, True
    return c


def user_text(kw):
    return kw["messages"][0]["content"]


def test_ai_sees_placeholders_not_values():
    fc = FakeClient(reply=lambda kw: user_text(kw).split("<transcript>\n")[1].split("\n</transcript>")[0])
    out = Cleaner(client=fc).clean_dictation("truck GJ 01 AB 1234 at 3 pm", cfg=cfg())
    sent = user_text(fc.sent[0])
    assert "GJ 01 AB 1234" not in sent and "3 pm" not in sent and "[[P" in sent
    assert out.method == "rules+claude" and "GJ 01 AB 1234" in out.text and "3 pm" in out.text
    assert fc.sent[0]["model"] == "claude-opus-5-5"
    assert fc.sent[0]["fallbacks"] == "default"


def test_ai_inventing_numbers_is_rejected():
    out = Cleaner(client=FakeClient(reply="Truck at 4 pm")).clean_dictation("um truck at 3 pm", cfg=cfg())
    assert out.method == "rules" and "3 pm" in out.text and "rejected" in out.note


def test_ai_inventing_placeholder_is_rejected():
    out = Cleaner(client=FakeClient(reply="Hi [[P9]]")).clean_dictation("hi there", cfg=cfg())
    assert out.method == "rules" and out.text == "Hi there"


def test_refusal_falls_back():
    out = Cleaner(client=FakeClient(reply="", stop="refusal")).clean_dictation("um hello", cfg=cfg())
    assert out.method == "rules" and out.text == "Hello"


def test_connection_error_falls_back():
    import anthropic
    import httpx2
    err = anthropic.APIConnectionError(request=httpx2.Request("POST", "https://api.anthropic.com"))
    out = Cleaner(client=FakeClient(exc=err)).clean_dictation("uh okay", cfg=cfg())
    assert out.method == "rules" and out.text == "Okay" and "network" in out.note


def test_missing_credentials_falls_back():
    def boom(**kw):  # what the SDK raises when no key/profile exists at all
        raise TypeError('"Could not resolve authentication method. Expected one of api_key"')
    fc = FakeClient()
    fc.beta.messages.create = boom
    out = Cleaner(client=fc).clean_dictation("um hi", cfg=cfg())
    assert out.text == "Hi" and out.method == "rules" and "API key" in out.note
