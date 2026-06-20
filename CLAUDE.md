# hermes-android

Native Android app (Kotlin + Jetpack Compose) that provides a text chat UI for a
**Hermes Agent** running locally on the same device (typically in Termux).

## Architecture

The app talks to a **single** Hermes backend — the **dashboard**
(`hermes dashboard`, `127.0.0.1:9119`) — over two channels on that one port:

- **Chat + sessions** ride the **JSON-RPC gateway WebSocket** (`/api/ws`), which
  streams responses, tool activity, thinking traces, and interactive requests
  (approvals/clarify/secrets). This is the same protocol the Ink TUI and Electron
  desktop app drive.
- **Voice (STT/TTS)** uses the REST audio endpoints (`/api/audio/transcribe`,
  `/api/audio/speak`) on the same server. The phone owns audio I/O (records OGG
  locally, plays returned audio); the server is just a provider proxy. The
  gateway's own `voice.*` methods drive the *server's* mic/speakers (the desktop
  model) and are **deliberately not used** on Android.

There is **no custom bridge server**, and the legacy api_server (8642) has been
retired. Hermes owns all conversation persistence (`~/.hermes/state.db`).

```
Android app (Compose UI → ViewModel → Repository)
   │  ws://…:9119/api/ws   (gateway: chat + sessions, ?token= auth)
   │  http://…:9119/api/audio/*  (REST: STT/TTS, Bearer auth)
   ▼
hermes dashboard (9119)  ──►  Hermes agent + SQLite sessions
```

See **[rpc-gateway.md](rpc-gateway.md)** for the phased migration that produced
this (gateway transport + single-backend consolidation).

## Key docs

- **[HERMES_INTEGRATION.md](HERMES_INTEGRATION.md)** — how the app talks to Hermes:
  enabling the API server, the exact endpoint contract, response shapes, and
  Android/Termux gotchas. **Read this before touching the `data/` layer.**
- **[hermes_android_app_techspec.md](hermes_android_app_techspec.md)** — the original
  product spec. Note: its "Python Flask bridge" is intentionally **not** built; see
  [HERMES_INTEGRATION.md](HERMES_INTEGRATION.md) for why.

## `hermes-agent/` is READ-ONLY

`./hermes-agent/` is a vendored copy of the upstream Hermes source, kept **only as a
reference** for the API contract: the gateway protocol lives at
`hermes-agent/tui_gateway/server.py` and the REST audio endpoints at
`hermes-agent/gateway/platforms/api_server.py`. It is **not part of this app's
build** and is git-ignored. **Never edit anything under `hermes-agent/`** — read it
to understand Hermes, but make no changes there.

## Build

Toolchain (AGP 9.1 / Gradle 9.3.1 / Kotlin 2.2.20 / Compose BOM 2026.04.01):

- **JDK 17 or 21** (both LTS; AGP 9.1 requires JDK 17 minimum).
- Android SDK: `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`.
- Bootstrap the Gradle wrapper once (the binary `gradlew`/wrapper jar aren't checked
  in): `gradle wrapper --gradle-version 9.3.1` (AGP 9.1 requires Gradle ≥ 9.3.1).

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
adb install app-debug.apk
```

## Scope

In: streaming text chat with live tool/thinking activity and interactive requests,
session list/create/delete, voice mode (Groq STT / Edge TTS over REST audio),
settings (single server URL + token), connection gate. Out (deferred): media
attachments, camera, Room offline cache.
