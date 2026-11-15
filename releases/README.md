# v2Text releases

| File | For | Notes |
|---|---|---|
| `v2Text-0.1.1-debug.apk` | Android 13+ arm64 (built for Galaxy A52s) | **Latest.** Fixes from the first device test ([notes](RELEASE_NOTES-v0.1.1.md)). Installs over 0.1.0. |
| `v2Text-0.1.0-debug.apk` | same | First build ([notes](RELEASE_NOTES-v0.1.0.md)) |

Check the download: `sha256sum -c SHA256SUMS`

Built from `android/` with `./gradlew assembleDebug` (AGP 8.7.3, Kotlin 2.0.21, NDK 27.2.12479018,
compileSdk/targetSdk 35, minSdk 33). Install steps and the device test checklist are in
[android/README.md](../android/README.md).

0.1.0 has been installed on a Galaxy A52s: the bubble, microphone service, history and notifications worked. Whisper
transcription on the phone is not confirmed yet. Please report what works and what doesn't.
