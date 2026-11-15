from v2text import textproc as tp
from v2text.languages import get_profile

F = get_profile("en-IN").fillers


def clean(s, **kw):
    kw.setdefault("fillers", F)
    return tp.rule_based_clean(s, **kw).text


def test_fillers_removed_and_capitalised():
    assert clean("um so I think, uh, we should go") == "So I think we should go"


def test_filler_inside_words_untouched():
    assert clean("the drummer hummed") == "The drummer hummed"


def test_scratch_that():
    assert clean("Meet at 5. Scratch that. Meet at 6.") == "Meet at 6."


def test_self_correction_same_kind_only():
    assert clean("let's meet at 4 pm, actually 3 pm") == "Let's meet at 3 pm"
    assert clean("send 20 kg, no wait 25 kg") == "Send 25 kg"
    # different kinds -> left verbatim
    assert "4 pm" in clean("at 4 pm, actually 3 boxes")


def test_plain_no_is_not_a_correction():
    assert clean("I need 5, no 6 is too many") == "I need 5, no 6 is too many"


def test_spoken_newlines():
    assert clean("hello new line world new paragraph bye") == "Hello\nWorld\n\nBye"


def test_numbered_list():
    out = clean("agenda for today first budget review, second hiring plan and third travel")
    assert out == "Agenda for today:\n1. Budget review\n2. Hiring plan\n3. Travel"


def test_list_needs_ordered_ordinals():
    s = "the second option is better than the first"
    assert clean(s) == "The second option is better than the first"


def test_aliases_exact_only_and_never_short_or_numeric():
    out = clean("check the l n g tank and command center", aliases={"l n g": "LNG", "command center": "Command Centre", "5": "five", "ab": "X"})
    assert out == "Check the LNG tank and Command Centre"
    assert clean("ab 5", aliases={"5": "five", "ab": "X"}) == "Ab 5"


def test_term_case():
    assert clean("ugel report", terms=["UGEL"]) == "UGEL report"


def test_snippets_expand_verbatim():
    out = clean("here is my calendar link.", snippets={"my calendar link": "https://cal.example/me"})
    assert out == "Here is https://cal.example/me"


def test_protected_tokens_survive_cleanup():
    raw = "um vehicle GJ 01 AB 1234 left at 10:30 with 2,500 litres on 12/11/2026 cost ₹45,000"
    out = clean(raw)
    for tok in ["GJ 01 AB 1234", "10:30", "2,500 litres", "12/11/2026", "₹45,000"]:
        assert tok in out


def test_mask_and_unmask_roundtrip():
    s = "Truck MH12DE1433 at 3 pm, 40 kg, LNG"
    m, mp = tp.mask_protected(s, ["LNG"])
    assert "MH12DE1433" not in m and "40" not in m and "LNG" not in m
    assert tp.unmask(m, mp) == s


def test_change_log_records_steps():
    r = tp.rule_based_clean("um hello", fillers=F)
    assert "fillers" in r.changes and r.text == "Hello"


def test_devanagari_untouched():
    s = "मैं कल ऑफिस आऊंगा"
    assert clean(s, fillers=get_profile("hi").fillers) == s
