# Verbo Keyboard

An Android keyboard that acts on what you type.

- **Action bar** – the wide orange bar types a space on tap. Hold it and the text in the
  field is treated as a request ("tell sam i'm 15 min late, friendly") and replaced with the result.
- **Skill keys** – the strip above the keys. Each one applies a saved instruction to your
  text (Polish, Reply, Shorter, Formal, Casual, English, Look up, List).
- **Skill builder** – add, edit and delete skills in the Verbo app.
- **Web search** – actions can look up current facts (can be turned off).
- **Links** – an action can offer to open a map, dialer, email or web page.
- **Undo** – one tap restores your original text.

Actions use the Claude API with your own key (entered in the app, stored on the phone).
Text is only sent when you trigger an action, and never from password fields.

## Build

Needs JDK 17+ and the Android SDK (platform 35).

    ./gradlew :app:assembleDebug

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Install

1. Copy the APK to the phone and open it (allow installing from that source).
2. Open Verbo, tap **Open keyboard settings** and enable Verbo Keyboard.
3. Tap **Choose keyboard** and pick Verbo.
4. Paste a Claude API key and tap **Save key**.

## Code

- `VerboIme.kt` – the keyboard and the action flow
- `Ai.kt` – the Claude request and result parsing
- `Store.kt` – settings and skills
- `MainActivity.kt` – setup and settings screen
