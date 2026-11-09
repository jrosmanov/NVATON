# NVATON

**A voice assistant for older people, in Azerbaijani and English.**
Say «Ali» and the assistant writes WhatsApp messages, records voice messages, makes calls, sets alarms and medicine reminders, reads a medicine box through the camera, explains the screen and reads incoming messages aloud. It sends nothing until you confirm.

- **Android app «NVATON»:** Kotlin, Jetpack Compose, Android 8.0+
- **Backend:** Node.js 20.12+, TypeScript, Express
- **AI and speech:** Gemini (or Claude), Azure Speech (optional), Vosk (offline wake word)

Built in November 2026 for the UNEX hackathon. Plan and demo script: [MVP_PLAN.md](MVP_PLAN.md).

## Quick start

```bash
# Terminal 1: backend
cd backend && cp .env.example .env   # put GEMINI_API_KEY in .env
npm install && npm run dev
```

```bash
# Terminal 2: from the repo root, phone connected by USB
scripts/get-wake-model.sh
(cd android && ./gradlew installDebug)
adb reverse tcp:8787 tcp:8787
```

Then open the app → ⚙️ → **Setup** and allow everything (microphone, camera, contacts, notification access, accessibility service).

The app is ARM-only: use a real phone or an Apple-silicon emulator (`scripts/emulator.sh`). For Wi-Fi use, set `HOST=0.0.0.0` and `APP_TOKEN` in `.env`, then enter the server address and token in ⚙️. Always set a token.

## API

| Endpoint           | Purpose                           |
| ------------------ | --------------------------------- |
| `GET /health`      | Health check                      |
| `POST /api/intent` | Sentence → structured action      |
| `POST /api/vision` | Camera photo + question → answer  |
| `POST /api/screen` | Screen text → explanation         |
| `POST /api/stt`    | Audio → text                      |
| `POST /api/tts`    | Text → Azerbaijani speech (Azure) |

When `APP_TOKEN` is set, send it in the `X-App-Token` header (the app does this).

## Features and commands

| Feature           | Example (Azerbaijani)                          | Example (English)                      |
| ----------------- | ---------------------------------------------- | -------------------------------------- |
| Message           | «Ali, nəvəmə yaz: salam»                       | _"Ali, text my daughter I'll be late"_ |
| Call              | «Oğluma zəng et»                               | _"call my son"_                        |
| Voice message     | «Əzizə səsli mesaj göndər», stop with «Mellon» | _"send my son a voice message"_        |
| Alarm / reminder  | «Hər gün axşam 9-da dərmanımı xatırlat»        | _"wake me up tomorrow at 6:30"_        |
| Camera            | «Bu dərman nə üçündür?»                        | _"what is this medicine?"_             |
| Screen            | «Ali, ekranı oxu»                              | _"read the screen"_                    |
| Incoming messages | «Ali, mesajlarımı oxu»                         | _"read my messages"_                   |

Every message is read back with **Send / Change / Cancel**. Switch language in ⚙️ → **Dil / Language** or say «dili ingilis dilinə dəyiş».
108 English commands run on the phone without the backend (see [`en.tsv`](android/app/src/main/assets/commands/en.tsv)).

## Configuration (`backend/.env`)

| Variable                                  | Default                                      | Purpose                                                  |
| ----------------------------------------- | -------------------------------------------- | -------------------------------------------------------- |
| `GEMINI_API_KEY`                          |                                              | Commands, camera, screen, Azerbaijani speech recognition |
| `GEMINI_MODEL` / `GEMINI_VISION_MODEL`    | `gemini-3.5-flash-lite` / `gemini-3.6-flash` | Text/speech and camera models                            |
| `AI_PROVIDER`                             | `gemini`                                     | `gemini` or `claude`                                     |
| `ANTHROPIC_API_KEY`, `CLAUDE_MODEL`       | `claude-opus-5-5`                            | Optional Claude provider                                 |
| `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION` |                                              | Natural Azerbaijani voice and recognition                |
| `HOST`, `PORT`                            | `127.0.0.1`, `8787`                          | Network binding                                          |
| `APP_TOKEN`                               |                                              | Shared secret for Wi-Fi use                              |

Restart the backend after editing `.env`.

## Commands

```bash
cd backend && npm run dev        # dev server
cd backend && npm test           # 34 backend tests
cd backend && npm run typecheck  # type check
cd android && ./gradlew installDebug          # debug install
cd android && ./gradlew testDebugUnitTest     # 97 Android tests
cd android && ./gradlew assembleRelease       # release APK
```

Debug builds only: send a command without speaking.

```bash
adb shell "am start -n az.nvaton.komekci/.MainActivity --es command 'nəvəmə yaz salam'"
adb shell am start -n az.nvaton.komekci/.MainActivity --ez simulate_wake true
adb logcat -s WakeWord
```

## Troubleshooting

| Problem                               | Fix                                                        |
| ------------------------------------- | ---------------------------------------------------------- |
| `No connected devices!`               | Connect a phone or start the emulator; check `adb devices` |
| «Serverə qoşula bilmədim»             | Backend running? Run `adb reverse tcp:8787 tcp:8787` again |
| Azerbaijani speech not understood     | Add a Gemini or Azure key, or type the command             |
| WhatsApp opens but message isn't sent | Turn on the accessibility service in ⚙️ → Setup            |
| Saying «Ali» does nothing             | Run `scripts/get-wake-model.sh` and reinstall              |

## Structure

```
android/app/src/main/java/az/nvaton/komekci/
├── a11y/       Accessibility service: WhatsApp Send, screen reading
├── actions/    Messages, voice messages, calls
├── alarms/     Alarms, reminders, full-screen alarm
├── core/       Dialog manager, local parser, fast commands
├── data/       Settings, contacts
├── net/        Backend client
├── notify/     Reads notifications aloud
├── ui/         Compose screens and theme
├── voice/      Speech input, TTS, voice-message recorder
└── wake/       Wake-word service (Vosk)

backend/src/    index.ts, app.ts (routes), ai.ts, gemini.ts, claude.ts,
                stt.ts, tts.ts, prompts.ts, schemas.ts
scripts/        emulator.sh, get-wake-model.sh
```

Request flow: wake word (offline) → speech to text → meaning (phone first, then AI) → confirmation → action.

## Security

- API keys live only in `backend/.env`, never in the APK.
- Wake-word audio is processed on the phone only; audio is sent to the backend only after «Ali» is heard.
- The accessibility service acts only in WhatsApp, and only sends a message you confirmed within the last 20 seconds. Voice messages are never sent automatically.
- On Gemini's free tier Google may use requests to improve its products: use the paid tier for real users.

## Credits

Vosk (`vosk-model-small-tr-0.3`, Apache-2.0), Chivo font (OFL 1.1), Material Symbols (Apache-2.0), Google Gemini, Anthropic Claude, Azure AI Speech.
