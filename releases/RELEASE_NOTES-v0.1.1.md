Fixes from the first test on a real Galaxy A52s. **Installs over 0.1.0 (same signing key); no need to uninstall.**

> ⚠️ Still a pre-release: Whisper transcription has not yet been confirmed on a phone.

### Fixed
- **Model download screen:** the model descriptions were cut off and "Download" wrapped onto two lines. Each model now shows its full description with its buttons underneath, **base** is marked as the recommended first download, and download progress plus a **Cancel** button now appear inside the Speech engine box.
- **Dropdowns** (Engine, Language, settings) showed blank text; they now use the standard layout.
- **Bubble with no model:** it no longer records when no engine is ready. It shows what's missing and opens v2Text.
- **"Input is very quiet" on every recording:** loudness is now measured on speech only, not averaged with the pauses.
- **Quiet speech dropped as "no speech":** the speech threshold now adapts to each recording's background noise (floor -60 dBFS), so quiet but clear speech from Samsung's low-gain mic is still transcribed.
- **Duplicate page titles** removed from History, Settings, Dictionary and Benchmark.

### Added
- **Boost quiet speech** (Settings → Microphone, on by default): up to +24 dB of gain on the copy sent to the speech engine. Recordings are saved unchanged; the applied gain is recorded with each session's diagnostics in the history database. Compare on/off with the Benchmark screen.
- The notification for a "no model" failure explains that the recording is saved, so you can download a model and then tap **Retry**.

### After updating
1. Open v2Text → **2 · Speech engine** → tap **Download** under **★ base** (Wi-Fi). Wait for "✅ ggml-base.bin installed and selected".
2. Open **History** and tap **Retry** on your earlier failed dictations; they transcribe from the saved audio without speaking again.

Tests: 34 JVM and Robolectric tests pass; Android lint reports no errors.

**SHA-256** `e3f0ed653994a4d72bae39aeb35b435e2714cb960e372211f72f8879a38c8e9a`  `v2Text-0.1.1-debug.apk`
