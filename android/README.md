# v2Text for Android (Galaxy A52s)

Offline voice typing with a floating bubble that works next to your normal keyboard
(Samsung Keyboard, Gboard and others). Speech is recognised **on the phone**, and nothing is uploaded.

* **Target:** Samsung Galaxy A52s 5G (Snapdragon 778G, arm64), Android 13+ (`minSdk 33`, `targetSdk 35`)
* **APK:** [`../releases/v2Text-0.1.0-debug.apk`](../releases/) (debug-signed, for sideloading)
* **Status:** builds, unit tests, Robolectric pipeline tests and lint all pass. **Not yet run on a real phone.**
  See [Verification status](#verification-status).

## Install on the phone

1. Copy `v2Text-0.1.0-debug.apk` to the phone and open it. Allow "Install unknown apps" for your file manager when asked.
2. Open **v2Text** and work through **1 · Setup**:
   * **Microphone**: Allow.
   * **Notifications**: Allow. Android needs a notification while an app can use the mic.
   * **Display over other apps**: turn on for v2Text. This shows the bubble.
   * **Accessibility → v2Text voice typing**: turn on. This lets v2Text type into other apps.
     If the switch is greyed out ("Restricted setting"), tap **Open App info**, then **⋮** (top right),
     then **Allow restricted settings**, and try again. Android does this for every sideloaded app.
   * Samsung battery: Settings → Battery → Background usage limits → **Never sleeping apps** → add v2Text.
3. **2 · Speech engine**: tap **Download** next to **base** (~142 MiB, Wi-Fi). This is the only time
   v2Text uses the internet. The file is checked against the SHA-1 published by whisper.cpp.
   Already have a `ggml-*.bin` model? Use **Import a model file** instead.
4. **3 · Language**: English (India), Hindi, Hinglish (Roman script), Gujarati, or Auto.
5. **4 · Dictate**: tap **Start bubble**. Try it in the box on the same screen, then in WhatsApp, Gmail or Samsung Notes.

## Using the bubble

| Gesture | What it does |
|---|---|
| Tap | Start recording. Tap again to stop (hands-free). |
| Press and hold | Push-to-talk. Release to stop. |
| Drag | Move the bubble. A recording started by that touch is discarded. |

| Colour | Meaning |
|---|---|
| 🔵 Blue | Idle |
| 🟠 Amber | Mic is starting. It turns red only when real audio arrives. |
| 🔴 Red | Recording. The ring shows your voice level. |
| 🟣 Purple | Recognising |
| 🟢 Green / 🟠 Orange | Done / problem (a short message explains) |

The text is typed at the cursor of the field you were using. If that fails, it is left on your clipboard and a
notification offers **Insert** and **Copy**. If recognition fails, the notification offers **Retry transcription**,
which re-runs the saved audio without recording again.

## Architecture

```
ui/MainActivity ── setup, engine/model, language, start bubble
service/BubbleService (foreground service, type=microphone)
  └─ BubbleView overlay (FLAG_NOT_FOCUSABLE: the text field keeps focus)
  └─ audio/Recorder: AudioRecord 16 kHz mono, VOICE_RECOGNITION source,
       optional pre-roll ring buffer, tail, onLive callback, route detection
DictationController
  1. blocked-app check (password managers, banking, UPI)
  2. core/AudioDiagnostics: RMS, peak, clipping, silence, onset/trailing warnings
  3. too_short / no_speech, audio saved atomically BEFORE recognition
  4. SpeechEngine (provider-independent)
       engine/WhisperEngine: whisper.cpp via JNI (src/main/cpp), one thread, fp16+dotprod build
       engine/AndroidRecognizerEngine: createOnDeviceSpeechRecognizer + EXTRA_AUDIO_SOURCE (our PCM)
  5. VERBATIM transcript stored (never updated)
  6. core/TextProcessor: clean text + change log
       aliases, snippets, fillers, "scratch that", explicit self-correction,
       spoken new line/paragraph, numbered lists, term casing, tidy;
       protected tokens (numbers, times, dates, ₹, vehicle regs, emails, URLs) untouched
  7. service/V2TextAccessibilityService.insert(): ACTION_SET_TEXT at the cursor, then ACTION_PASTE;
       never password fields; clipboard + notification on failure
  8. data/HistoryDb (SQLite): status, verbatim, clean, diagnostics, route, timings, inserted flag
  9. audio retention: never | on_failure (default) | always, purged after N days
ui/HistoryActivity: verbatim vs clean, Copy, Copy verbatim, Retry, Delete
ui/DictionaryActivity: terms (aliases, category, search) and snippets
ui/BenchmarkActivity: WER, CER, critical terms, first/last-word loss, latency over a manifest.csv folder
```

`core/` is pure Kotlin. It is the same logic as the desktop app's `v2text/textproc.py` and is tested with the same cases.

## Build

Needs JDK 17+, Android SDK platform 35, NDK `27.2.12479018` and CMake `3.22.1`.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 29 tests (JVM + Robolectric)
./gradlew lintDebug
```

whisper.cpp is vendored in `third_party/whisper.cpp`. See `VENDORED_FROM.txt` for the commit; unused GPU
backends were removed. Native code is always built `-O3`, because whisper is unusably slow at `-O0`.

## Benchmark on the phone (blueprint §10)

1. Make a folder, e.g. `Documents/v2text-bench`, with your WAV clips and a `manifest.csv`:
   ```
   audio,reference,language,critical_terms
   clips/en01.wav,"Send the LNG report to Command Centre by 3 pm.",en-IN,"LNG;Command Centre;3 pm"
   ```
   The references must be checked by a person.
2. In v2Text: **Benchmark → Choose benchmark folder → Run with current engine**.
3. Repeat for each engine and model (tiny, base, small, the Android recognizer). Each run writes `results-<engine>-<model>-<time>.csv`.
4. Choose the default engine and model from the numbers, not from model names.

Record the clips with any recorder that can save 16-bit PCM WAV (any sample rate; v2Text resamples to
16 kHz), or convert them on a PC first, e.g. `ffmpeg -i in.m4a -ac 1 -ar 16000 -sample_fmt s16 out.wav`.
Use the same clips for every engine you compare.

## Verification status

| Check | Result |
|---|---|
| `assembleDebug` (Kotlin + NDK/CMake whisper.cpp) | ✅ builds, APK signature v2 verifies |
| Native library exports all 5 JNI functions, built with NEON/FP16/DOTPROD | ✅ checked with `llvm-nm` / `strings` |
| Text formatter, diagnostics, WER/CER, WAV I/O (JVM tests) | ✅ 20/20 |
| Pipeline with real SQLite / prefs / clipboard / files (Robolectric, fake engine) | ✅ 9/9 |
| Android lint | ✅ 0 errors |
| Whisper inference with a real model | ❌ **not run**: the build machine couldn't download models |
| Bubble, mic, accessibility insertion, on-device recognizer | ❌ **not run**: no device or emulator available |

### First things to test on the A52s (blueprint Appendix A)

| ID | Test | Look for |
|---|---|---|
| A01–A03 | Tap and speak at once; stop right after the last word | History: first and last words present, "mic start" ms. If clipped, turn on pre-roll. |
| A04–A05 | Very quiet speech, then very close and loud | Warnings in History; no invented text |
| A07 | Connect / disconnect a Bluetooth headset mid-session | Route shown in History |
| A08–A11 | en-IN, Hindi, Hinglish, Gujarati | Benchmark WER/CER per language and engine |
| A12 | "vehicle GJ 01 AB 1234 at 10:30, 2,500 litres" | Tokens unchanged in the clean text |
| A13 | "at 4 pm, actually 3 pm" | Clean: "at 3 pm"; Verbatim keeps both |
| A14 | Airplane mode, Whisper engine | Works fully offline |
| A15 | Tap, stay silent, stop | "No speech", audio kept, Retry offered |
| A17 | Delete the model file, then dictate | Clear "model missing" message, no crash |
| A20 | Dictate with no text field focused | Text on clipboard + Insert/Copy notification, no duplicate when inserted later |
| — | Password field (e.g. a login screen) | Nothing typed |
| — | WhatsApp, Gmail, Samsung Notes, Chrome address bar | Insertion works, or falls back cleanly |

## Known limitations

* Whisper speed on the A52s is unmeasured. Start with **base**. **small** may be too slow for long dictation.
* The Android on-device recognizer path relies on the recognizer honouring `EXTRA_AUDIO_SOURCE`. Not every
  implementation does, so check it with the Benchmark screen before you rely on it.
* No streaming or partial results: text appears after you stop.
* No AI rewriting or command mode. That would need a paid or online model, which the blueprint rules out.
* Debug-signed APK: an update built on a different machine needs an uninstall first. A release build needs your own keystore.
* Some apps (games, some banking apps, custom editors) refuse accessibility text insertion. For those, the text is left on the clipboard.
