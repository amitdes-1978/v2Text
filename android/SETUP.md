# v2Text: complete setup on the Galaxy A52s (about 5 minutes)

Do these in order. Each step says what you should see when it's done.

## 1. Install (or update) the app
1. Download the latest APK from [`releases/`](../releases/) (currently **v2Text-0.1.2-debug.apk**) and open it.
2. If asked, allow **Install unknown apps** for Chrome or My Files. If Play Protect warns, tap **More details → Install anyway**.
3. Updating from an older v2Text? Just tap **Update**: your model, history and settings are kept.

✅ The v2Text icon (blue mic) is on your home screen.

## 2. Give the four permissions (v2Text → "1 · Setup")
Open v2Text. Each item has a button; tap it, allow, then come back. The item turns ✅.

| Item | What to do |
|---|---|
| Microphone | Tap **Allow microphone** → **While using the app** |
| Notifications | Tap **Allow notifications** → **Allow** |
| Display over other apps | Tap **Open setting** → find **v2Text** → switch **on** → press Back |
| Type into other apps (Accessibility) | Tap **Open Accessibility settings** → **Installed apps** → **v2Text voice typing** → switch **on** → **Allow** |

**If the Accessibility switch is greyed out** ("Restricted setting"): go back to v2Text, tap
**Open App info (for restricted settings)** → tap **⋮** (top right) → **Allow restricted settings** → confirm,
then repeat the Accessibility step. Android does this for every app installed outside the Play Store.

**Recommended (Samsung):** Settings → Battery → Background usage limits → **Never sleeping apps** → **+** → v2Text.

✅ All four items show ✅.

## 3. Download the speech model (v2Text → "2 · Speech engine")
1. Make sure **Engine** says **Whisper (whisper.cpp) · offline**.
2. Connect to **Wi-Fi**. Under **★ base**, tap **Download** → **Download**.
3. Watch the progress inside the box (about 142 MiB, a minute or two on Wi-Fi).

✅ "✅ ggml-base.bin installed and selected" and **Model in use: ggml-base.bin**.

## 4. Choose your language (v2Text → "3 · Language")
Pick the language you will speak: **English (India)**, **Hindi**, **Hinglish (Roman)** or **Gujarati**.
Use one language per dictation for best results.

## 5. Start the bubble (v2Text → "4 · Dictate")
Tap **Start bubble**. A blue round mic appears at the side of the screen, and a "v2Text bubble is on"
notification appears (Android requires it whenever an app can use the mic).

## 6. First test (inside v2Text)
1. Tap the **Try it here** box so the cursor is in it.
2. Tap the bubble once. It turns **amber**, then **red**: speak a sentence, e.g. "Please send the LNG report to Command Centre by 3 pm."
3. Tap the bubble again. It turns **purple** while it works (the first time takes longer while the model loads), then **green**.

✅ Your sentence appears in the box. The first run on base may take a few seconds; tell us how long.

Then try WhatsApp, Gmail or Samsung Notes: tap into the message box first, then use the bubble.

## If something goes wrong
* **Bubble flashes orange and v2Text opens**: something isn't set up yet; the message says what.
* **Text didn't appear in the app** but you see an **Insert / Copy** notification: the app refused direct typing.
  Tap **Insert**, or long-press the message box → **Paste**.
* **"No speech detected"**: speak a little closer; check History for a "quiet" warning.
* **History → Retry**: re-transcribes any saved failed recording without speaking again.
* **v2Text closes or crashes**: open v2Text again. A yellow card **"v2Text stopped unexpectedly"** appears at the top:
  tap **Copy crash report** and paste it to the developer. It contains no audio or transcripts.
