# v2Text

Private, offline-first voice typing, inspired by Wispr Flow. The code is original. It is built to the
"Voice Typing Quality Blueprint V2" rules: quality is measured, the verbatim transcript is never changed,
numbers and IDs are protected, and no paid API is required.

| | Where | What |
|---|---|---|
| 📱 **Android app** (Galaxy A52s) | [`android/`](android/README.md) | Floating bubble over any app, on-device whisper.cpp or the system recognizer, accessibility insertion, retry, benchmark |
| ⬇️ **APK** | [`releases/`](releases/) | `v2Text-0.1.0-debug.apk` + `SHA256SUMS` |
| 💻 **Desktop app** (Windows / macOS / Linux) | this page, [`v2text/`](v2text/) | Push-to-talk hotkey, faster-whisper, optional Claude cleanup |
| 📄 **Product study** | [`docs/WISPR_FLOW_STUDY.md`](docs/WISPR_FLOW_STUDY.md) | How Wispr Flow works, and what v2Text does differently |

**Android quick start:** install the APK, then follow the in-app Setup checklist. Details are in
[android/README.md](android/README.md#install-on-the-phone).

---

## Desktop app

Hold a key, speak, and clean text appears in whatever app you're typing in.

## Install

```bash
pip install -e .            # core: offline speech + rule-based cleanup
pip install -e ".[ai]"      # optional Claude cleanup + voice command mode
```

Linux also needs `sudo apt install libportaudio2 xclip xdotool` (X11).
macOS needs Microphone and Accessibility permission for your terminal.

Download a speech model once (this is the only step that needs internet in offline mode):

```bash
v2text model download            # default "small" (~480 MB, multilingual)
v2text model download --name base   # faster, less accurate
```

## Use

```bash
v2text run
```

* **Hold Right Alt**: talk. Release to insert the text.
* **Double-tap Right Alt**: hands-free mode. Tap once more to stop.
* **Hold Right Ctrl** with text selected: speak an edit instruction. Needs AI mode.

What the formatter does (all local):

| You say | You get |
|---|---|
| "um so the l n g report, uh, is ready" | "So the LNG report is ready" |
| "meet at 4 pm, actually 3 pm" | "Meet at 3 pm" |
| "…at 5. Scratch that. …at 6." | "…at 6." |
| "agenda first budget, second hiring, third travel" | "Agenda:" followed by a numbered list |
| "hello new line world" | two lines |
| "my signature" (a snippet) | your saved text, verbatim |

Numbers, times, dates, ₹ amounts, vehicle registrations (e.g. `GJ 01 AB 1234`), emails and URLs
are never changed by the formatter.

## Commands

```bash
v2text transcribe clip.wav --lang hi      # prints VERBATIM and CLEAN, plus audio diagnostics
v2text diagnose clip.wav                  # level / clipping / silence / first-last-word warnings
v2text clean "um hello there"             # try the formatter on text
v2text history                            # 📎 = audio kept for retry
v2text retry 12                           # re-transcribe session 12 from saved audio
v2text copy 12 [--verbatim]               # put a transcript on the clipboard (if paste failed)
v2text dict add "Kubernetes" --alias "cube or netties" --category tech
v2text snippet add "my signature" "Regards, Your Name"
v2text config set language hinglish       # en-IN | hi | hinglish | gu | auto
v2text config set whisper_model medium
v2text stats
v2text purge --all                        # delete all saved audio and history
```

## Benchmark before you trust it

Record a fixed set of clips (16-bit WAV) and write human-checked references in a CSV like
[bench/manifest.example.csv](bench/manifest.example.csv). Then run:

```bash
v2text bench bench/manifest.csv --model small --out results/small
v2text bench bench/manifest.csv --model medium --out results/medium
```

It reports WER, CER, critical-term accuracy, first/last-word loss, empty outputs and latency,
overall and per language. Pick your default model from those numbers.

## Privacy and modes

| Setting | Default | Meaning |
|---|---|---|
| `offline_only` | `true` | Hard guarantee: the model loads with `local_files_only`, and Claude is never called |
| `ai_cleanup` | `false` | Optional Claude pass. Only takes effect with `offline_only=false`. Sends **text, never audio** |
| `context_awareness` | `false` | Opt-in. Reads only the active app's name, to pick a writing style |
| `blocked_apps` | password managers, banking | Nothing is transcribed or pasted while one of these is in front |
| `audio_retention` | `on_failure` | `never` / `on_failure` / `always`; purged after `audio_retention_days` (14) |
| `warm_mic` | `false` | Keeps the mic open for a 400 ms pre-roll so first words aren't clipped (mic light stays on) |

### Optional AI mode (paid)

```bash
export ANTHROPIC_API_KEY=...
v2text config set offline_only false
v2text config set ai_cleanup true
```

Before the text goes to Claude (`claude-opus-5-5`, effort `low`), protected values are swapped
for placeholders. Any reply that invents a number or placeholder is rejected. Refusals,
network errors and missing keys all fall back to the local result, so your words are never lost.

## Development

```bash
pip install -e ".[dev]" && pytest
```

## Status and limitations

* Unit and pipeline tests pass (37). The CLI flows were run end to end with synthetic audio.
* **Not yet tested:** real speech transcription and live hotkey/paste on a desktop session.
  The build environment couldn't download a Whisper model or reach a microphone.
* No GUI overlay or tray icon (terminal status only). The Android app is in `android/`.
