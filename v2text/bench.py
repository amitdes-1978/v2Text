"""Benchmark harness: WER, CER, critical-term accuracy, boundary loss, latency.

Manifest CSV columns:
  audio,reference,language,critical_terms
  clips/en01.wav,"Send the LNG report by 3 pm.",en-IN,"LNG;3 pm"

The same recordings must be used for every engine/model being compared.
References must be human-checked; this tool never generates them.
"""
from __future__ import annotations

import csv
import json
import re
import statistics
import time
import unicodedata
from dataclasses import asdict, dataclass
from pathlib import Path

from .audio import load_wav
from .languages import get_profile


def normalize(text: str) -> str:
    """Lowercase, strip punctuation (Unicode-aware, so Devanagari/Gujarati survive)."""
    text = unicodedata.normalize("NFC", text.lower())
    text = "".join(" " if unicodedata.category(c).startswith("P") else c for c in text)
    return re.sub(r"\s+", " ", text).strip()


def edit_distance(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h) / max(len(r), 1)


def cer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).replace(" ", ""), normalize(hyp).replace(" ", "")
    return edit_distance(list(r), list(h)) / max(len(r), 1)


def critical_hits(terms: list[str], hyp: str) -> tuple[int, int]:
    n = normalize(hyp)
    hits = sum(1 for t in terms if t and normalize(t) in n)
    return hits, len([t for t in terms if t])


def boundary_loss(ref: str, hyp: str) -> dict:
    r, h = normalize(ref).split(), normalize(hyp).split()
    return {"first_word_lost": bool(r) and (not h or h[0] != r[0]),
            "last_word_lost": bool(r) and (not h or h[-1] != r[-1])}


@dataclass
class Row:
    audio: str
    language: str
    reference: str
    verbatim: str
    wer: float
    cer: float
    critical_hit: int
    critical_total: int
    first_word_lost: bool
    last_word_lost: bool
    latency_s: float
    error: str | None = None


def run(manifest: Path, engine, hint_words: list[str] = (), out_dir: Path | None = None) -> dict:
    rows: list[Row] = []
    base = manifest.parent
    with manifest.open(newline="", encoding="utf-8") as f:
        for rec in csv.DictReader(f):
            path = base / rec["audio"]
            lang = rec.get("language") or "auto"
            terms = [t.strip() for t in (rec.get("critical_terms") or "").split(";")]
            t0 = time.perf_counter()
            try:
                audio = load_wav(path)
                hyp = engine.transcribe(audio, 16000, get_profile(lang), list(hint_words)).text
                err = None
            except Exception as e:
                hyp, err = "", str(e)
            lat = time.perf_counter() - t0
            hit, tot = critical_hits(terms, hyp)
            b = boundary_loss(rec["reference"], hyp)
            rows.append(Row(rec["audio"], lang, rec["reference"], hyp, round(wer(rec["reference"], hyp), 4),
                            round(cer(rec["reference"], hyp), 4), hit, tot, b["first_word_lost"],
                            b["last_word_lost"], round(lat, 3), err))
    summary = summarize(rows)
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
        with (out_dir / "results.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(asdict(rows[0]).keys()) if rows else ["audio"])
            w.writeheader()
            for r in rows:
                w.writerow(asdict(r))
        (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
    return summary


def summarize(rows: list[Row]) -> dict:
    def agg(rs: list[Row]) -> dict:
        if not rs:
            return {}
        ct = sum(r.critical_total for r in rs)
        return {"n": len(rs),
                "wer": round(statistics.mean(r.wer for r in rs), 4),
                "cer": round(statistics.mean(r.cer for r in rs), 4),
                "critical_term_accuracy": round(sum(r.critical_hit for r in rs) / ct, 4) if ct else None,
                "first_word_lost": sum(r.first_word_lost for r in rs),
                "last_word_lost": sum(r.last_word_lost for r in rs),
                "errors": sum(1 for r in rs if r.error),
                "empty_outputs": sum(1 for r in rs if not r.verbatim.strip()),
                "latency_p50_s": round(statistics.median(r.latency_s for r in rs), 3)}
    out = {"overall": agg(rows)}
    for lang in sorted({r.language for r in rows}):
        out[lang] = agg([r for r in rows if r.language == lang])
    return out
