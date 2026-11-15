# v2Text Android: quality audit against Wispr Flow (v0.2.0)

Wispr Flow's internals are proprietary; the left column is based on its public documentation
(see docs/WISPR_FLOW_STUDY.md). The right columns are based on v2Text's own code and on evidence
from tests and the Galaxy A52s screenshots and video.

## 1. Why v2Text produced no text at all (fixed)

| Symptom on the A52s | Root cause | Evidence | Fix |
|---|---|---|---|
| 0.1.0 closed ("app has a bug") right after the model was downloaded | The text formatter's regexes used Java's `(?U)` flag. Android compiles regexes with **ICU**, which rejects it, so `TextProcessor` threw while loading; nothing caught it, so the app died | ICU 74.2 rejects exactly the 5 `(?U)` patterns (syntax error at column 3); the other 16 compile | Explicit `[\p{L}\p{M}\p{N}_]` classes (same on JVM and ICU) |
| 0.1.2: "Internal error (NoClassDefFoundError: com…)" on every dictation | Same bug: after the first failure Android reports the class as unloadable; 0.1.2's safety net turned the crash into this message | Video: red → purple → error toast each time | Same fix. The error also now gets a History entry |
| Why the tests didn't catch it | Unit tests run on the JVM regex engine, which accepts `(?U)` | — | `RegexCompatTest` blocks ICU-unsupported flags; `tools/icu_regex_check.py` compiles all 25 patterns with real ICU |

The Whisper native layer itself was run on **emulated arm64 with the A52s CPU features** (NEON/FP16/DOTPROD)
through the app's real JNI code. Model load, en/auto/hi/gu, beam search and silence all passed without a crash.

## 2. Capability comparison

| Stage | Wispr Flow (public docs) | v2Text 0.2.0 | Gap |
|---|---|---|---|
| UX | Floating bubble over the existing keyboard | Same model: bubble (tap / hold / drag), overlay + accessibility | — |
| Audio capture | Production capture, mic warnings | 16 kHz VOICE_RECOGNITION, optional pre-roll, 300 ms tail, live-audio indicator, route detection, level/clipping/quiet warnings (speech-only, adaptive) | Small |
| Pre-processing | Not documented | Silence trimming + optional gain for Samsung's low-gain mic | — |
| Recognition | **Cloud**, language-specific engines, candidate scoring | **On-device** whisper.cpp (tiny…small) or the phone's on-device recognizer | **Large: biggest remaining quality gap** |
| Hallucination control | Not documented | Non-speech token suppression at decode time; filter for "[Music]", invented outros and loops | — |
| Formatting | AI formatting model (fillers, corrections, lists, punctuation) | Rule-based: fillers, stutters, explicit self-corrections, scratch-that, lists, spoken new lines, casing; numbers/IDs protected | Medium: rules can't rephrase like an LLM |
| Context | Surrounding text continues sentences | Opt-in: 200 chars before the cursor prime Whisper and prevent mid-sentence capitals | Small |
| Vocabulary | Personal dictionary | Dictionary with aliases + recognizer hints | Small |
| Languages | 100+, best with one language per dictation | en-IN, Hindi, Hinglish (Roman), Gujarati, auto | Hinglish/Gujarati accuracy unmeasured |
| Latency | Streams to the cloud | Transcribes after you stop; first run loads the model | Medium (no streaming) |
| Recovery | Audio retry (14 days) | Audio kept on failure, Retry without re-recording, separate Insert/Copy, crash report | — |
| Measurement | Internal | Benchmark screen (WER/CER/critical terms/boundary loss/latency) | Needs your 50-clip set |

## 3. What would close the remaining recognition gap

1. **Measure first:** run the Benchmark with `base` vs `small-q5_1` on your own clips (see android/README.md).
2. **Optional online engine (needs your decision):** Wispr's accuracy comes from cloud models. The free equivalent on
   Android is Google's speech service through `SpeechRecognizer`. That would send audio to Google,
   which breaks the blueprint's offline-only default, so it would be opt-in with explicit consent.
3. **Streaming:** transcribe completed phrases while you are still speaking, to cut waiting time on long dictations.
4. **Larger on-device models** (e.g. large-v3-turbo-q5_0, 547 MiB) are more accurate but likely too slow on the A52s; only worth trying after the benchmark.
