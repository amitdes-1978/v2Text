"""v2text command line."""
from __future__ import annotations

import argparse
import json
import logging
import sys
from dataclasses import asdict
from pathlib import Path

from . import __version__
from .config import Config, app_home


def _pipeline(cfg, injector=None):
    from .app import build_pipeline
    return build_pipeline(cfg, injector)


def _print_session(s, verbose=True):
    print(f"session #{s.id}  status={s.status}  engine={s.engine}/{s.model}  stt={s.stt_seconds:.2f}s")
    if s.error:
        print(f"  error: {s.error}")
    print(f"  VERBATIM : {s.verbatim}")
    print(f"  CLEAN    : {s.clean}")
    if verbose:
        print(f"  method={s.clean_method} changes={s.changes}")
        if s.report:
            r = s.report
            print(f"  audio: {r.duration_s}s rms={r.rms_dbfs}dBFS peak={r.peak_dbfs}dBFS "
                  f"clip={r.clipping_pct}% silence={r.silence_ratio} onset={r.onset_ms}ms")
            for w in r.warnings:
                print(f"  ⚠ {w}")
        for n in s.notes:
            print(f"  ℹ {n}")


def _coerce(cur, value: str):
    if isinstance(cur, bool):
        return value.lower() in ("1", "true", "yes", "on")
    if isinstance(cur, int):
        return int(value)
    if isinstance(cur, float):
        return float(value)
    if isinstance(cur, (list, dict)):
        return json.loads(value)
    return value


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="v2text", description="v2Text - private voice dictation")
    ap.add_argument("--version", action="version", version=f"v2text {__version__}")
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("run", help="start the push-to-talk dictation app")

    p = sub.add_parser("transcribe", help="transcribe a WAV file (verbatim + clean)")
    p.add_argument("wav")
    p.add_argument("--lang")

    p = sub.add_parser("clean", help="run the text formatter on some text")
    p.add_argument("text")
    p.add_argument("--style", default="default")

    p = sub.add_parser("diagnose", help="audio level/clipping/silence report for a WAV file")
    p.add_argument("wav")

    p = sub.add_parser("bench", help="WER/CER benchmark over a manifest CSV")
    p.add_argument("manifest")
    p.add_argument("--out", default="bench-results")
    p.add_argument("--model", help="override whisper model for this run")

    p = sub.add_parser("model", help="manage the local speech model")
    p.add_argument("action", choices=["download", "info"])
    p.add_argument("--name")

    p = sub.add_parser("history", help="recent sessions")
    p.add_argument("-n", type=int, default=15)
    sub.add_parser("stats", help="usage statistics")

    p = sub.add_parser("retry", help="re-transcribe a session from its saved audio")
    p.add_argument("id", type=int)
    p = sub.add_parser("copy", help="copy a session's clean text to the clipboard")
    p.add_argument("id", type=int)
    p.add_argument("--verbatim", action="store_true")

    p = sub.add_parser("dict", help="personal dictionary")
    p.add_argument("action", choices=["list", "add", "remove", "search"])
    p.add_argument("term", nargs="?")
    p.add_argument("--alias", action="append", default=[])
    p.add_argument("--category", default="general")

    p = sub.add_parser("snippet", help="voice snippets")
    p.add_argument("action", choices=["list", "add", "remove"])
    p.add_argument("trigger", nargs="?")
    p.add_argument("text", nargs="?")

    p = sub.add_parser("config", help="show or change settings")
    p.add_argument("action", choices=["show", "set", "path"])
    p.add_argument("key", nargs="?")
    p.add_argument("value", nargs="?")

    p = sub.add_parser("purge", help="delete all saved audio (and history with --all)")
    p.add_argument("--all", action="store_true")

    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.WARNING,
                        format="%(levelname)s %(name)s: %(message)s")
    cfg = Config.load()

    if a.cmd == "run":
        from .app import App
        from .stt import STTError
        try:
            App(cfg).run()
        except STTError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        return 0

    if a.cmd == "transcribe":
        from .audio import load_wav
        if a.lang:
            cfg.language = a.lang
        pl = _pipeline(cfg)
        s = pl.dictate(load_wav(a.wav, cfg.sample_rate), app="")
        _print_session(s)
        return 0 if s.status == "ok" else 1

    if a.cmd == "clean":
        from .cleanup import Cleaner
        out = Cleaner(cfg.claude_model, cfg.effort).clean_dictation(a.text, cfg=cfg, style=a.style)
        print(out.text)
        print(f"[method={out.method} changes={out.changes}{' note=' + out.note if out.note else ''}]",
              file=sys.stderr)
        return 0

    if a.cmd == "diagnose":
        from .audio import load_wav
        from .diagnostics import analyze
        print(json.dumps(analyze(load_wav(a.wav, cfg.sample_rate), cfg.sample_rate).as_dict(), indent=2))
        return 0

    if a.cmd == "bench":
        from . import bench
        from .stt import WhisperEngine
        eng = WhisperEngine(a.model or cfg.whisper_model, cfg.whisper_device, cfg.offline_only)
        summary = bench.run(Path(a.manifest), eng, cfg.dictionary_terms(), Path(a.out))
        print(json.dumps(summary, indent=2, ensure_ascii=False))
        print(f"per-clip results: {Path(a.out) / 'results.csv'}")
        return 0

    if a.cmd == "model":
        name = a.name or cfg.whisper_model
        if a.action == "download":
            from .stt import download_model
            print(f"Downloading Whisper model '{name}' from Hugging Face "
                  "(tiny≈75MB, base≈145MB, small≈480MB, medium≈1.5GB)...")
            print("saved to", download_model(name))
        else:
            from .stt import WhisperEngine
            WhisperEngine(name, cfg.whisper_device, offline_only=True).load()
            print(f"model '{name}' is installed and loads offline.")
        return 0

    if a.cmd in ("history", "stats", "retry", "copy"):
        from .history import History
        h = History(app_home() / "history.sqlite3")
        if a.cmd == "history":
            for r in h.recent(a.n):
                text = (r["clean"] or r["verbatim"] or "").replace("\n", " ⏎ ")
                print(f"#{r['id']:<4} {r['status']:<12} {r['language'] or '':<8} "
                      f"{'📎' if r['audio_path'] else '  '} {text[:90]}")
            return 0
        if a.cmd == "stats":
            print(json.dumps(h.stats(), indent=2))
            return 0
        row = h.get(a.id)
        if row is None:
            print(f"no session #{a.id}", file=sys.stderr)
            return 1
        if a.cmd == "copy":
            import pyperclip
            pyperclip.copy(row["verbatim"] if a.verbatim else (row["clean"] or row["verbatim"] or ""))
            print("copied.")
            return 0
        # retry
        if not row["audio_path"] or not Path(row["audio_path"]).exists():
            print(f"session #{a.id} has no saved audio (retention: {cfg.audio_retention}).", file=sys.stderr)
            return 1
        from .audio import load_wav
        pl = _pipeline(cfg)
        s = pl.retry(a.id, load_wav(row["audio_path"], cfg.sample_rate), Path(row["audio_path"]))
        _print_session(s)
        return 0 if s.status == "ok" else 1

    if a.cmd == "dict":
        if a.action == "list":
            for d in cfg.dictionary:
                print(f"{d['term']:<24} [{d.get('category', '')}] aliases: {', '.join(d.get('aliases', []))}")
        elif a.action == "search":
            q = (a.term or "").lower()
            for d in cfg.dictionary:
                if q in d["term"].lower() or any(q in x.lower() for x in d.get("aliases", [])):
                    print(d["term"], d.get("aliases", []))
        elif a.action == "add":
            if not a.term:
                ap.error("dict add needs a term")
            cfg.dictionary = [d for d in cfg.dictionary if d["term"] != a.term]
            cfg.dictionary.append({"term": a.term, "aliases": a.alias, "category": a.category})
            cfg.save()
            print(f"added {a.term}")
        elif a.action == "remove":
            cfg.dictionary = [d for d in cfg.dictionary if d["term"] != a.term]
            cfg.save()
            print(f"removed {a.term}")
        return 0

    if a.cmd == "snippet":
        if a.action == "list":
            for k, v in cfg.snippets.items():
                print(f"{k!r} -> {v!r}")
        elif a.action == "add":
            if not (a.trigger and a.text):
                ap.error('snippet add "<spoken trigger>" "<text>"')
            cfg.snippets[a.trigger] = a.text
            cfg.save()
        elif a.action == "remove":
            cfg.snippets.pop(a.trigger, None)
            cfg.save()
        return 0

    if a.cmd == "config":
        if a.action == "path":
            print(Config.path())
        elif a.action == "show":
            print(json.dumps(asdict(cfg), indent=2, ensure_ascii=False))
        else:
            if a.key not in Config.__dataclass_fields__:
                ap.error(f"unknown setting {a.key}")
            setattr(cfg, a.key, _coerce(getattr(cfg, a.key), a.value))
            cfg.save()
            print(f"{a.key} = {getattr(cfg, a.key)!r}")
            if a.key in ("offline_only", "ai_cleanup") and cfg.ai_enabled:
                print("note: AI cleanup sends transcribed text (not audio) to Anthropic; "
                      "set ANTHROPIC_API_KEY. This is a paid API.")
        return 0

    if a.cmd == "purge":
        from .store import AudioStore
        n = AudioStore(app_home() / "audio").delete_all()
        print(f"deleted {n} audio files")
        if a.all:
            from .history import History
            History(app_home() / "history.sqlite3").clear()
            print("history cleared")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
