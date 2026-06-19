# hermes-android

Native Android app (Kotlin + Jetpack Compose) that provides a text chat UI for a
**Hermes Agent** running locally on the same device (typically in Termux).

## Architecture (text-only MVP)

The app is a thin REST client. It talks **directly** to Hermes's built-in
OpenAI-compatible API server on `127.0.0.1:8642` — there is **no custom bridge
server**. Hermes owns all conversation persistence (`~/.hermes/state.db`).

```
Android app (Compose UI → ViewModel → Repository → Retrofit)
        │  HTTP (Bearer auth) on localhost:8642
        ▼
Hermes gateway api_server  ──►  Hermes agent + SQLite sessions
```

## Key docs

- **[HERMES_INTEGRATION.md](HERMES_INTEGRATION.md)** — how the app talks to Hermes:
  enabling the API server, the exact endpoint contract, response shapes, and
  Android/Termux gotchas. **Read this before touching the `data/` layer.**
- **[hermes_android_app_techspec.md](hermes_android_app_techspec.md)** — the original
  product spec. Note: its "Python Flask bridge" is intentionally **not** built; see
  [HERMES_INTEGRATION.md](HERMES_INTEGRATION.md) for why.

## `hermes-agent/` is READ-ONLY

`./hermes-agent/` is a vendored copy of the upstream Hermes source, kept **only as a
reference** for the API contract (the server lives at
`hermes-agent/gateway/platforms/api_server.py`). It is **not part of this app's
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

In: text chat, session list/create/delete, settings (server URL + API key),
connection gate. Out (deferred): voice (Groq STT / Edge TTS), media attachments,
camera, SSE streaming, Room offline cache.
