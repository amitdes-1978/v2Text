First build of **v2Text for Android**: offline voice typing with a floating bubble that works next to your normal keyboard. Built for the **Samsung Galaxy A52s** (Android 13+, arm64).

> ⚠️ **Pre-release: not yet tested on a physical phone.** The build, 29 unit and Robolectric tests and Android lint all pass, but the bubble, microphone, text insertion and real speech recognition haven't been run on a device yet. Please report what works and what doesn't.

### Install
1. Download `v2Text-0.1.0-debug.apk` below and open it on the phone (allow "Install unknown apps").
2. In v2Text, complete the **Setup** checklist: microphone, notifications, display over other apps, and accessibility.
   If the Accessibility switch is greyed out: **App info → ⋮ → Allow restricted settings**.
3. Under **Speech engine**, download the **base** model (~142 MiB, Wi-Fi). This is the only time the app uses the internet.
4. Tap **Start bubble**: tap to start and again to stop, or hold to talk.

### What's in it
- Floating dictation bubble (tap to toggle, hold for push-to-talk, drag to move)
- On-device speech: **whisper.cpp** (built for the A52s's processor) or Android's on-device recognizer. Audio is never uploaded.
- Types at the cursor in other apps; never types into password fields; blocked for banking, UPI and password-manager apps; clipboard fallback with Insert/Copy notifications
- Exact verbatim transcript kept separately from the cleaned text. Numbers, times, ₹ amounts and vehicle numbers (e.g. `GJ 01 AB 1234`) are never altered.
- Filler removal, "scratch that", explicit corrections ("4 pm, actually 3 pm"), spoken lists and new lines
- English (India), Hindi, Hinglish and Gujarati profiles; personal dictionary (LNG, UGEL, Retail Outlet, Command Centre pre-loaded) and snippets
- Audio kept on failure for **Retry transcription** without re-recording; history with diagnostics
- In-app **Benchmark**: WER, CER, critical-term accuracy and first/last-word loss on your own recordings

### Known limitations
- Whisper speed and accuracy on the A52s are not yet measured. Start with **base**.
- Text appears after you stop (no streaming).
- No AI rewriting or voice command mode on Android (no paid APIs, per the blueprint).
- Debug-signed: a future build from another machine may need an uninstall first.

Full device test checklist: [android/README.md](https://github.com/amitdes-1978/v2Text/blob/main/android/README.md)

**SHA-256** `2f524fa5379b36cabd7133ac60d512299828cccf87bdec61dc1f2f3345ad7000`  `v2Text-0.1.0-debug.apk`
