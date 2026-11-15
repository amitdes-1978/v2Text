# v2Text releases

| File | For | Notes |
|---|---|---|
| `v2Text-0.1.0-debug.apk` | Android 13+ arm64 (built for Galaxy A52s) | Debug-signed, for sideloading and testing |

Check the download: `sha256sum -c SHA256SUMS`

Built from `android/` with `./gradlew assembleDebug` (AGP 8.7.3, Kotlin 2.0.21, NDK 27.2.12479018,
compileSdk/targetSdk 35, minSdk 33). Install steps and the device test checklist are in
[android/README.md](../android/README.md).

This build has passed its build, unit, Robolectric and lint checks, but **has not yet been run on a
physical phone**. Please report what works and what doesn't.
