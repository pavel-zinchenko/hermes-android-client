# hermes-android

A native Android client (Kotlin + Jetpack Compose) for a **[Hermes Agent](https://github.com/)**
running locally on the same device — typically inside [Termux](https://termux.dev/).
It provides a clean text-chat UI plus push-to-talk voice, talking **directly** to
Hermes's built-in OpenAI-compatible API. There is no custom bridge server.

> Status: early MVP (`v0.1.0`). Text chat and voice mode work; see [Roadmap](#roadmap).

## How it works

The app is a thin REST client. It connects straight to Hermes's gateway API server on
`127.0.0.1:8642` using Bearer-token auth. Hermes owns all conversation persistence
(`~/.hermes/state.db`) — the app keeps no separate database.

```
Android app (Compose UI → ViewModel → Repository → Retrofit)
        │  HTTP (Bearer auth) on localhost:8642
        ▼
Hermes gateway api_server  ──►  Hermes agent + SQLite sessions
```

See **[HERMES_INTEGRATION.md](HERMES_INTEGRATION.md)** for the exact endpoint contract,
response shapes, and Android/Termux gotchas.

## Features

- 💬 **Text chat** with a locally running Hermes agent
- 🗂️ **Sessions** — list, create, and delete conversations (persisted by Hermes)
- 🎙️ **Voice mode** — push-to-talk speech-to-text and text-to-speech via Hermes audio endpoints
- ⚙️ **Settings** — configurable server URL and API key
- 🔌 **Connection gate** — verifies the agent is reachable before entering chat

## Requirements

- A running Hermes agent with its OpenAI-compatible API server enabled
  (default `127.0.0.1:8642`). See [HERMES_INTEGRATION.md](HERMES_INTEGRATION.md).
- Android device/emulator running **API 29 (Android 10)** or newer.

## Build

Toolchain: AGP 9.1 · Gradle 9.3.1 · Kotlin 2.2.20 · Compose BOM 2026.04.01.

- **JDK 17 or 21** (AGP 9.1 requires JDK 17 minimum).
- Android SDK packages:
  `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`.

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

App identity: `com.hermes.android` · `minSdk 29` · `targetSdk 36`.

## Project layout

```
app/src/main/java/com/hermes/android/
├── audio/      # STT/TTS recording & playback
├── data/       # Retrofit client, DTOs, repository (the Hermes API layer)
└── ui/         # Compose screens: chat, sessions, settings, startup, voice, theme
```

## Documentation

- **[HERMES_INTEGRATION.md](HERMES_INTEGRATION.md)** — how the app talks to Hermes
  (read this before touching the `data/` layer).
- **[hermes_android_app_techspec.md](hermes_android_app_techspec.md)** — original product
  spec. Note: its "Python Flask bridge" is intentionally **not** built; the app talks to
  Hermes directly instead.
- **[CLAUDE.md](CLAUDE.md)** — contributor/architecture notes.

## Roadmap

Deferred for later: media attachments, camera, SSE streaming, and a Room offline cache.

## License

[MIT](LICENSE) © 2026 pavel-zinchenko
