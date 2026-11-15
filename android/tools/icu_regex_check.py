#!/usr/bin/env python3
"""Compile every TextProcessor regex with ICU (the engine Android uses) and check key behaviour.

    ./gradlew testDebugUnitTest          # writes app/build/regex-patterns.txt
    python3 tools/icu_regex_check.py     # needs PyICU (Ubuntu: apt install python3-icu; use the matching python3.x)
Exit code 1 if any pattern fails to compile or behaves wrongly.
"""
import sys
from pathlib import Path
import icu

path = Path(__file__).resolve().parent.parent / "app" / "build" / "regex-patterns.txt"
pats = [p for p in path.read_text(encoding="utf-8").split("\n") if p]
bad = 0
for p in pats:
    try:
        icu.RegexPattern.compile(p, icu.URegexpFlag.CASE_INSENSITIVE)
    except Exception as e:
        bad += 1
        print("FAIL", p[:120], "->", e)

# Behaviour checks on ICU for the Unicode-sensitive patterns
WORD = r"[\p{L}\p{M}\p{N}_]"
def matches(p, text):
    m = icu.RegexPattern.compile(p, icu.URegexpFlag.CASE_INSENSITIVE).matcher(text)
    return m.find()
checks = [
    (rf"(?<!{WORD})\Qlng\E(?!{WORD})", "the lng tank", True),
    (rf"(?<!{WORD})\Qlng\E(?!{WORD})", "slngx", False),
    (r"(?:,\s*)?(?<![\p{L}\p{M}\p{N}_'])(?:अं)(?:\s*,)?(?![\p{L}\p{M}\p{N}_'])", "अं मैं कल", True),
    (r"(?:,\s*)?(?<![\p{L}\p{M}\p{N}_'])(?:um)(?:\s*,)?(?![\p{L}\p{M}\p{N}_'])", "drummer", False),
]
for p, text, want in checks:
    got = matches(p, text)
    if got != want:
        bad += 1
        print("WRONG", p, repr(text), "expected", want, "got", got)
print(f"{len(pats)} patterns compiled with ICU {icu.ICU_VERSION}; {len(checks)} behaviour checks; failures: {bad}")
sys.exit(1 if bad else 0)
