# Wispr Flow — product study and what v2Text takes from it

**Method.** No Wispr Flow binary was decompiled for this study. It is based on Wispr's public
product pages, Help Center and security docs, third-party reviews, and the "Voice Typing
Quality Improvement Blueprint V2" (15 Nov 2026). Wispr's internals (models, routing, prompts)
are not public. None of them were guessed at or copied. v2Text is an original
implementation that aims for similar user outcomes.

## 1. What Wispr Flow is

A system-wide voice-dictation layer. You hold a hotkey (Fn on Mac) or tap a floating bubble
(Android) and speak. The words appear, cleaned up, in whatever text field has focus. It
works with your existing keyboard and does not replace it.

| Capability | Publicly documented behaviour |
|---|---|
| Push-to-talk | Hold hotkey to talk; hands-free mode for long dictation |
| AI auto-editing | Removes fillers, applies self-corrections ("…5, no wait, 6"), punctuation, numbered lists |
| Command Mode | Select text, speak an instruction ("make this friendlier") and the text is rewritten |
| Dictionary | Custom vocabulary; learns from your corrections; shared on team plans |
| Snippets | Spoken shortcut that inserts saved text |
| Styles | Tone per app category (formal email vs casual chat) |
| Context awareness | Uses surrounding / on-screen text to improve output (an admin can disable it) |
| Languages | 100+; best results with one selected language per dictation |
| Retry | Failed transcription can be retried from audio saved on the device (≈14 days) |
| Platform | Desktop app is Electron; Android uses a bubble overlay plus accessibility; Android 13+ |
| Processing | **Cloud**: transcription happens on Wispr's servers; internet is required |
| Privacy | "Privacy Mode" (off by default) stops storage/training on your dictation; TLS in transit |

## 2. The trade-offs

* **Cloud-only.** Quality comes from server models, but audio leaves the device and nothing
  works offline.
* **Context awareness sends on-screen text** along with your voice.
* **Resource use.** Reviews report ~800 MB RAM on Windows (Electron).
* **Subscription** with weekly word limits on the free tier.

## 3. v2Text design decisions

| Wispr Flow | v2Text | Why |
|---|---|---|
| Cloud STT | **Local faster-whisper** (offline by default) | Free, private, works offline (blueprint §6) |
| AI cleanup always on (cloud LLM) | **Rule-based cleanup by default**; optional Claude pass on *text only* | No mandatory paid API (blueprint budget rule) |
| Single cleaned output | **Immutable verbatim + separate clean text + change log** | Formatting must not hide recognition errors (§8.3) |
| AI may rewrite anything | **Protected tokens** (numbers, dates, times, ₹ amounts, vehicle regs, IDs, dictionary terms) masked before AI, AI output rejected if it changes digits | §8.4, gate "no formatter changes protected tokens" |
| Self-correction | Only explicit cues ("actually", "no wait", "I mean") and same value type | §8.4 "only when explicit and unambiguous" |
| Context = surrounding text | Opt-in, **app name only**; blocked list for password managers / banking | §9.2 privacy |
| Retry from saved audio | Audio kept **on failure / no speech**, purged after N days; `retry` and `copy` are separate | §9.3: retry-transcription ≠ retry-paste |
| Bubble overlay (Android) | Desktop hotkey (hold / double-tap lock) | This build targets desktop; see §5 |
| Proprietary quality claims | **Benchmark harness**: WER, CER, critical-term accuracy, boundary loss, latency | §10: measure before claiming "better" |

## 4. Pipeline

```
hotkey press ─► Recorder (optional warm-mic pre-roll) ─► release + tail_ms
   ─► diagnostics (RMS, peak, clipping %, silence, onset/offset warnings)
   ─► too_short / no_speech?  ── yes ─► record status, keep audio for retry
   ─► SpeechEngine (faster-whisper, language profile + dictionary hint prompt)
   ─► VERBATIM transcript (immutable, stored)
   ─► rule-based formatter (aliases → snippets → fillers → scratch-that →
       self-correction → spoken newlines → lists → term casing → tidy), change-logged
   ─► [optional] Claude pass on masked text, guarded
   ─► paste via clipboard (restores old clipboard) ─► paste_failed is its own status
   ─► SQLite history + audio retention policy
```

## 5. Known gaps vs Wispr Flow (honest list)

* **Accuracy is unmeasured.** No benchmark has been run yet. Use `v2text bench` on your own
  recordings before comparing with Wispr.
* **Android:** built in `android/` for the Galaxy A52s: a bubble over the existing keyboard
  (Wispr's documented Android model), on-device whisper.cpp or the system recognizer, accessibility
  insertion, retry and benchmark. It has not yet been tested on a physical device (see android/README.md).
* Desktop: no GUI overlay or tray yet (terminal status only). Neither app streams partial results.
* The dictionary does not auto-learn from your edits (Wispr does).
* Hinglish/Gujarati quality depends on the Whisper model size and has to be measured. `small`
  is the default; `medium` is likely better but slower.
* Wayland: active-app detection and simulated paste are limited. X11, macOS and Windows are supported.

## Sources

* [What is Wispr Flow? (Help Center)](https://docs.wisprflow.ai/articles/2772472373-what-is-flow)
* [Wispr Flow features](https://wisprflow.ai/features)
* [Wispr Flow — What's New](https://roadmap.wisprflow.ai)
* [Enterprise privacy & security overview](https://wisprflow.ai/post/enterprise-privacy-and-security-overview)
* [Security Overview (Help Center)](https://docs.wisprflow.ai/articles/3147443438-security-overview)
* [Data Security & Encryption](https://docs.wisprflow.ai/articles/1922179110-data-security-encryption)
* [Wispr Flow overview (eesel.ai)](https://eesel.ai/blog/wispr-flow-overview)
* [Wispr Flow review (letterly)](https://letterly.app/blog/wispr-flow-review/)
* [Wispr Flow review (Spokenly)](https://spokenly.app/blog/wispr-flow-review)
* Voice Typing Quality Improvement Blueprint V2 (user-supplied PDF, 15 Nov 2026)
