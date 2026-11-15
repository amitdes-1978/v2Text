**Fixes "Internal error (NoClassDefFoundError)" and the crash after downloading a model, and adds a recognition-quality pass.**
Installs over earlier versions (same signing key); your downloaded model, history and settings are kept.

### The bug
The text formatter used a regex flag (`(?U)`) that Android's regex engine (ICU) rejects, so the formatter could never
load on a phone. Recording and recognition were working; every result failed at the formatting step.
Fixed, and now guarded: a unit test blocks ICU-unsupported flags, and all 25 patterns are compiled with real ICU before release.

### Quality improvements (compared with Wispr Flow's documented pipeline)
- **Hallucination control:** Whisper suppresses non-speech tokens; the clean text drops "[Music]", invented outros such as
  "Thanks for watching", and repeated-phrase loops (the verbatim transcript is kept unchanged in History).
- **Stutter removal:** "the the report" → "the report" (legitimate doubles such as "that that" are kept).
- **Context continuation (opt-in):** uses up to 200 characters before the cursor, on the phone only, to guide recognition
  (names, style) and to avoid a capital letter when you continue a sentence. Never from password fields or blocked apps.
- **Silence trimming** before recognition: less hallucination, faster.
- **Better model guidance:** ★ **small-q5_1** is now recommended for Indian English, Hindi and Gujarati; **base** stays the fast option.
- **Every failure appears in History**, including internal errors.

### Verified
47 JVM and Robolectric tests; 25 regex patterns compiled with ICU 74.2; Android lint reports no errors; Whisper JNI run on emulated arm64.
**Not yet confirmed on the phone:** accuracy and speed. Please run the Benchmark, or report examples.

Full analysis: [docs/QUALITY_AUDIT.md](../docs/QUALITY_AUDIT.md) · Setup: [android/SETUP.md](../android/SETUP.md)

**SHA-256** `17ee96fe3b3a8d004f2c021a85fbe70e30f53750855de9de4dc27ccba4087148`  `v2Text-0.2.0-debug.apk`
