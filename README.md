# Verbo Keyboard

An Android keyboard that acts on what you type.

- **Fast typing** – one custom-drawn key view with per-finger tracking, word suggestions,
  auto-correct (backspace undoes a correction), double-space full stop, key sound and vibration.
- **Shortcut wheel** – hold the wide ✦ bar and a wheel opens under your finger: slide to Ask AI, Translate,
  Rewrite, Meeting link, Voice or Clips (or any skill you put there; up to six) and let go. Results open as a card in place of the keys
  with Copy / Insert (Send in chat apps whose enter key sends), and a small map for a place.
- **Meeting link** – a wheel shortcut that inserts a Google Meet link (with Google connected on the dashboard)
  or an instant no-account link.
- **Translate** – pick a language, or translate into all of your languages at once.
- **Voice panel** – a listening panel with a level meter; Transcribe tidies speech, Command treats it as a request.
- **Clips** – save copied text and paste it any time.
- **Skill library** – ready-made skills on shelves (Featured, Work, Social, Fun, Languages), added with one tap.
- **Keys as shortcuts** – bind a skill to a letter and hold that key to run it (marked keys show a glow).
- **Context** – copy a message you received and tap "Use copied text" so the assistant knows what you are answering.
- **Skill keys** – the strip above the keys. Each one applies a saved instruction to your
  text. Type `/` and a skill's name to run it as a command.
- **Agent mode** – Auto applies results, Ask First shows them for approval, Manual only runs skill keys.
- **Voice** – tap 🎙 to dictate (filler words, self-corrections and punctuation are tidied up);
  hold 🎙 to speak a request.
- **Connections** – web search, Weather, Notion and Calendly (paste a token), and any HTTPS API.
- **Templates** – ready-made daily requests that open Gmail, WhatsApp, Calendar, Maps, YouTube or X
  with a draft filled in; some can be added as skill keys.
- **Skins** – ten colour skins, seven key styles (raised 3D, rounded, square, bubble, glass, outline, flat) and an optional number row.
- **Sound and vibration** – six key sound effects (typewriter with a bell, mechanical, pop, water drop,
  soft tap, phone default), volume, and three vibration strengths.
- **Battery** – follows the phone's Battery Saver (no key feedback or background sync) and releases the
  microphone and audio player whenever the keyboard is hidden.
- **Appearance** – System, Dark, Light, Amoled or a custom accent colour, and adjustable key height.
- **Dashboard** – optional web app (see `dashboard/`) to manage skills and connections
  from a browser; pair the keyboard with a one-time code and it syncs.
- **Export / import** – settings and skills as a JSON file (keys and tokens are left out).

Actions run on your own API key: Claude (Anthropic), GPT (OpenAI), or any OpenAI-compatible gateway
such as AI Router (address, model name and key; no web search through gateways); pick the model in
the app and enter the matching key. Keys are stored on the phone. Text is only sent when you
trigger an action, and never from password fields.

## Build

Needs JDK 17+ and the Android SDK (platform 35).

    ./gradlew :app:assembleRelease

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Use the release build on a
phone: debug builds type noticeably slower. It is signed with the debug key for now.

Tests (typing logic, plus screenshots of every screen written to `app/build/shots/`):

    ./gradlew :app:testDebugUnitTest

## Install

1. Copy the APK to the phone and open it (allow installing from that source).
2. Open Verbo, tap **Open keyboard settings** and enable Verbo Keyboard.
3. Tap **Choose keyboard** and pick Verbo.
4. Paste a Claude API key and tap **Save key**.

## Code

- `VerboIme.kt` – the keyboard service: word tracking, suggestions, actions, voice
- `KeyboardView.kt` – the keys: drawing, themes and touch handling
- `Dictionary.kt` – word list lookups for suggestions and auto-correct
- `Library.kt` – the catalogue of ready-made skills
- `Ui.kt`, `PageActivity.kt` – settings look and detail screens
- `Ai.kt` – the Claude request, prompts and result parsing
- `ChatCompat.kt` – the same action through OpenAI-compatible gateways (Chat Completions)
- `OpenAi.kt` – the same action on OpenAI's Responses API
- `Connections.kt` – connected apps and APIs as assistant tools
- `Sync.kt` – pairing and syncing with the dashboard
- `Store.kt` – settings and skills
- `MainActivity.kt` – setup and settings screen

## Credits

- Icons: [Lucide](https://lucide.dev) (ISC licence).
- Word list: [SCOWL](http://wordlist.aspell.net/) via the `wordlist-english` npm package (MIT).

`tools/make_assets.py` regenerates both from those packages. The key sounds are synthesized by
`tools/make_sounds.py`, so they have no third-party licence.
