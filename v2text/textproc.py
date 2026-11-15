"""Deterministic, auditable text processing.

The raw (verbatim) transcript is never modified; every step here returns a
new string and records what it changed, so formatting can't hide
recognition errors.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# ---------------------------------------------------------------- protected tokens
# Anything matching these must leave the formatter byte-for-byte unchanged.
PROTECTED_PATTERNS = [
    ("email", r"[\w.+-]+@[\w-]+\.[\w.]+"),
    ("url", r"https?://\S+|www\.\S+"),
    # Indian vehicle registration: GJ 01 AB 1234, MH12DE1433, KA-05-MN-2020
    ("vehicle_reg", r"\b[A-Za-z]{2}[ -]?\d{1,2}[ -]?[A-Za-z]{1,3}[ -]?\d{4}\b"),
    ("date", r"\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b"),
    ("time", r"\b\d{1,2}(?::\d{2})?\s?(?:[ap]\.?m\.?)(?![\w])"),
    ("time24", r"\b\d{1,2}:\d{2}\b"),
    ("amount", r"(?:₹|rs\.?\s?|inr\s?|\$)\s?\d[\d,]*(?:\.\d+)?"),
    ("measure", r"\b\d[\d,]*(?:\.\d+)?\s?(?:%|kg|km|kl|mt|tonnes?|litres?|liters?|l)\b"),
    ("number", r"\b\d[\d,]*(?:\.\d+)?\b"),
]
_PROTECT_RE = re.compile("|".join(f"(?P<{n}>{p})" for n, p in PROTECTED_PATTERNS), re.IGNORECASE)
_PLACEHOLDER_RE = re.compile(r"\[\[(?:P|S)\d+\]\]")


def find_protected(text: str, extra_terms: list[str] = ()) -> list[str]:
    toks = [m.group(0) for m in _PROTECT_RE.finditer(text)]
    for t in extra_terms:
        toks += [m.group(0) for m in re.finditer(rf"(?<!\w){re.escape(t)}(?!\w)", text)]
    return toks


def mask_protected(text: str, extra_terms: list[str] = ()) -> tuple[str, dict[str, str]]:
    """Replace protected tokens with [[P0]].. placeholders (for the optional AI pass)."""
    mapping: dict[str, str] = {}

    def sub(m):
        tok = f"[[P{len(mapping)}]]"
        mapping[tok] = m.group(0)
        return tok

    for t in sorted(extra_terms, key=len, reverse=True):
        text = re.sub(rf"(?<!\w){re.escape(t)}(?!\w)", sub, text)
    # don't re-match inside placeholders already inserted
    parts = re.split(r"(\[\[P\d+\]\])", text)
    text = "".join(p if _PLACEHOLDER_RE.fullmatch(p) else _PROTECT_RE.sub(sub, p) for p in parts)
    return text, mapping


def unmask(text: str, mapping: dict[str, str]) -> str:
    for tok, val in mapping.items():
        text = text.replace(tok, val)
    return text


# ---------------------------------------------------------------- steps
@dataclass
class CleanResult:
    text: str
    changes: list[str] = field(default_factory=list)


def remove_fillers(text: str, fillers: tuple[str, ...]) -> str:
    alt = "|".join(re.escape(f) for f in sorted(fillers, key=len, reverse=True))
    rx = re.compile(rf"(?:,\s*)?(?<![\w'])(?:{alt})(?:\s*,)?(?![\w'])", re.IGNORECASE)
    return rx.sub("", text)


_SCRATCH_RE = re.compile(r"[.!?,]?\s*\bscratch that\b[.!?,]?\s*", re.I)
_SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")


def apply_scratch_that(text: str) -> str:
    """'... at 5. Scratch that. ... at 6.' -> drops the sentence before the command."""
    while (m := _SCRATCH_RE.search(text)):
        before, after = text[: m.start()], text[m.end():]
        kept = " ".join(_SENT_SPLIT.split(before.strip())[:-1])
        text = f"{kept} {after}".strip() if kept else after.strip()
    return text


# Self-correction is applied only when explicit AND both sides are the same kind
# of value (time->time, number->number). Anything else is left verbatim.
_VAL = r"(?:\b\d{1,2}(?::\d{2})?\s?[ap]\.?m\.?|(?:₹\s?)?\b\d[\d,]*(?:\.\d+)?)"
_CORR_RE = re.compile(
    rf"(?P<old>{_VAL})(?P<unit>\s?[a-z%]*)\s*,?\s*(?:actually|no wait|sorry|i mean|correction)\s*,?\s*"
    rf"(?P<new>{_VAL})", re.IGNORECASE)


def _kind(v: str) -> str:
    return "time" if re.search(r"[ap]\.?m", v, re.I) else "num"


def apply_self_corrections(text: str) -> str:
    def sub(m):
        if _kind(m.group("old")) != _kind(m.group("new")):
            return m.group(0)
        return m.group("new")  # any unit after the new value is kept by the surrounding text
    return _CORR_RE.sub(sub, text)


_SPOKEN = [(re.compile(r"\s*\bnew paragraph\b[.,]?\s*", re.I), "\n\n"),
           (re.compile(r"\s*\bnew line\b[.,]?\s*", re.I), "\n")]


def apply_spoken_commands(text: str) -> str:
    for rx, rep in _SPOKEN:
        text = rx.sub(rep, text)
    return text


_ORD = ["first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth"]
_ORD_RE = re.compile(r"(?:(?<=^)|(?<=[\s.,;:]))(" + "|".join(_ORD) + r")(?:ly)?\b[,:]?\s+", re.I)


def format_lists(text: str) -> str:
    """'Agenda first budget, second hiring, third travel' -> numbered list.

    Only fires when ordinals appear in order starting from 'first' (>= 2 items).
    """
    ms = list(_ORD_RE.finditer(text))
    seq = []
    for m in ms:
        if len(seq) < len(_ORD) and m.group(1).lower() == _ORD[len(seq)]:
            seq.append(m)
    if len(seq) < 2:
        return text
    intro = text[: seq[0].start()].strip().rstrip(",;")
    items = []
    for i, m in enumerate(seq):
        end = seq[i + 1].start() if i + 1 < len(seq) else len(text)
        item = text[m.end(): end].strip().strip(",;").strip()
        item = re.sub(r"\s+and$", "", item).rstrip(".")
        items.append(item[:1].upper() + item[1:])
    body = "\n".join(f"{i}. {it}" for i, it in enumerate(items, 1))
    if intro and not intro.endswith(":"):
        intro = intro.rstrip(".") + ":"
    return f"{intro}\n{body}" if intro else body


def apply_aliases(text: str, aliases: dict[str, str]) -> str:
    """Exact (case-insensitive, whole-word) alias -> preferred term. No fuzzy/phonetic matching."""
    for alias in sorted(aliases, key=len, reverse=True):
        if len(alias) < 3 or re.fullmatch(r"[\d\W]+", alias):
            continue  # never rewrite short tokens or numbers
        rx = re.compile(rf"(?<!\w){re.escape(alias)}(?!\w)", re.IGNORECASE)
        text = rx.sub(lambda _m, t=aliases[alias]: t, text)
    return text


def apply_term_case(text: str, terms: list[str]) -> str:
    """Force canonical casing ('lng' -> 'LNG') for dictionary terms."""
    for t in sorted(terms, key=len, reverse=True):
        text = re.sub(rf"(?<!\w){re.escape(t)}(?!\w)", lambda _m, t=t: t, text, flags=re.I)
    return text


def protect_snippets(text: str, snippets: dict[str, str]) -> tuple[str, dict[str, str]]:
    mapping: dict[str, str] = {}
    for i, trig in enumerate(sorted(snippets, key=len, reverse=True)):
        rx = re.compile(rf"(?<!\w){re.escape(trig)}(?!\w)[.!?]?", re.IGNORECASE)
        tok = f"[[S{i}]]"
        new = rx.sub(tok, text)
        if new != text:
            mapping[tok], text = snippets[trig], new
    return text, mapping


def tidy(text: str) -> str:
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" +([,.!?;:])", r"\1", text)
    text = re.sub(r",\s*([.!?])", r"\1", text)
    text = re.sub(r"([.!?])\1+", r"\1", text)
    text = re.sub(r"^[\s,]+", "", text)
    text = re.sub(r"[ \t]*\n[ \t]*", "\n", text).strip()
    text = re.sub(r"(^|[.!?]\s+|\n)([a-z])", lambda m: m.group(1) + m.group(2).upper(), text)
    return text


def rule_based_clean(raw: str, *, fillers: tuple[str, ...] = (), aliases: dict[str, str] | None = None,
                     terms: list[str] | None = None, snippets: dict[str, str] | None = None,
                     remove_filler_words: bool = True, self_corrections: bool = True,
                     lists: bool = True) -> CleanResult:
    res = CleanResult(raw)

    def step(name, fn):
        new = fn(res.text)
        if new != res.text:
            res.changes.append(name)
            res.text = new

    step("aliases", lambda t: apply_aliases(t, aliases or {}))
    text, snip_map = protect_snippets(res.text, snippets or {})
    if snip_map:
        res.changes.append("snippets")
    res.text = text
    if remove_filler_words:
        step("fillers", lambda t: remove_fillers(t, fillers))
    step("scratch_that", apply_scratch_that)
    if self_corrections:
        step("self_correction", apply_self_corrections)
    step("spoken_commands", apply_spoken_commands)
    if lists:
        step("list", format_lists)
    step("term_case", lambda t: apply_term_case(t, terms or []))
    step("tidy", tidy)
    res.text = unmask(res.text, snip_map)
    return res


def word_count(text: str) -> int:
    return len(re.findall(r"[\w'-]+", text))
