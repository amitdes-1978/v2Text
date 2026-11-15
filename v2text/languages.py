"""Explicit language profiles. Mixed-language quality must be measured, not assumed."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class LanguageProfile:
    code: str
    whisper_language: str | None   # passed to Whisper; None = auto-detect
    prompt: str                    # decoder priming text (style/script hint)
    fillers: tuple[str, ...]       # only clearly non-semantic sounds


_COMMON = ("um", "umm", "uh", "uhh", "erm", "er", "ah", "hmm", "mm")

PROFILES: dict[str, LanguageProfile] = {
    "en-IN": LanguageProfile("en-IN", "en",
                             "Office dictation in Indian English. Names, amounts in rupees, times like 3 pm.",
                             _COMMON),
    "hi": LanguageProfile("hi", "hi", "नमस्ते, यह हिंदी में बोला गया संदेश है।", _COMMON + ("अं", "उम्म", "हम्म")),
    # Romanised Hinglish: decode as English with a code-switched prompt so Hindi words stay in Latin script.
    "hinglish": LanguageProfile("hinglish", "en",
                                "Haan, main kal office aaunga. Meeting 3 baje hai, report bhej dena.",
                                _COMMON),
    "gu": LanguageProfile("gu", "gu", "નમસ્તે, આ ગુજરાતીમાં બોલાયેલો સંદેશ છે.", _COMMON + ("અં",)),
    "auto": LanguageProfile("auto", None, "", _COMMON),
}


def get_profile(code: str) -> LanguageProfile:
    return PROFILES.get(code, PROFILES["auto"])
