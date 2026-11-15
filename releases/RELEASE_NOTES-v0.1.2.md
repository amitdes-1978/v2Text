Crash diagnostics and crash-proofing after the first Whisper run on a Galaxy A52s closed the app.
**Installs over 0.1.0 / 0.1.1 (same signing key).**

### Fixed / added
- **The dictation path no longer crashes the app.** Any unexpected error during recognition, retry, insertion or
  model loading is caught and shown as a readable "Internal error" in History instead of closing v2Text.
- **Crash report on the phone:** if v2Text ever closes unexpectedly, the next launch shows a yellow card
  with Android's record of what happened (native crash, ANR, low memory, Java exception) and a **Copy crash report**
  button. Nothing is uploaded; it contains no audio or transcripts.
- Includes all 0.1.1 fixes (clearer model download screen, visible dropdowns, quiet-speech handling, gain boost).

### Verified
- The Whisper native code (model load, en / auto / Hindi with Devanagari prompt / Gujarati, beam search, near-silent
  input) was run on **emulated arm64 with the A52s CPU features (NEON, FP16, DOTPROD)** with no crash.
  It has not yet been confirmed on the real phone.
- 35 JVM + Robolectric tests pass; Android lint reports no errors.

Setup guide: [android/SETUP.md](../android/SETUP.md)

**SHA-256** `31c53f13cface73896c25b46efe9f9fdf72ef0194982a5d98b815e771c2206f3`  `v2Text-0.1.2-debug.apk`
