# Hermes Android Native App — Technical Specification

## Overview

A native Android app that connects to an existing Hermes Agent instance running in Termux on the same device. Provides text chat and voice chat UI without requiring Telegram or any external messaging gateway.

---

## Architecture

```
Android App (Kotlin + Jetpack Compose)
        │
        │  HTTP/WebSocket (localhost)
        ▼
Flask/FastAPI bridge server (Python, running in Termux)
        │
        ├── Hermes CLI (subprocess or API)
        │
        ├── Groq Whisper API (STT)
        │
        └── Edge TTS (TTS)
```

The app itself contains no AI logic — it's a UI shell. All intelligence runs in Termux.

---

## Prerequisites

### Development machine (Windows)

Android Studio is NOT required. Claude Code generates all source files. Only needed:

- **JDK 17** (~180MB) — https://adoptium.net
- **Android command-line tools** (~150MB) — https://developer.android.com/studio#command-tools

After extracting command-line tools, install SDK components:
```powershell
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Build and install without Android Studio:
```powershell
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

- **Git**

No NDK required — pure Kotlin app, no native code.

### Phone (Termux side)

- Termux (F-Droid/GitHub build, not Play Store)
- Hermes Agent installed and configured
- Python packages:
  ```bash
  pip install flask edge-tts groq
  ```
- `termux-api` package + Termux:API companion app

---

## Components

### Termux Compatibility

The bridge server runs cleanly in Termux — it has no problematic native dependencies:

| Component | Status | Notes |
|-----------|--------|-------|
| Flask HTTP server | ✅ Works | Pure Python |
| SQLite | ✅ Works | Python stdlib |
| Hermes subprocess calls | ✅ Works | Shell subprocess |
| Groq API (STT) | ✅ Works | Pure HTTP calls |
| Edge TTS | ✅ Works | Pure Python + HTTP, saves MP3 to disk |
| Audio format handling | ✅ Works | `pkg install ffmpeg` if conversion needed |
| Microphone | Not needed | App handles recording |
| Speaker | Not needed | App handles playback |

The server has no GUI, no ML dependencies, no native audio libs — just HTTP in, file handling, HTTP out.

**One note:** Edge TTS outputs MP3. Android `MediaPlayer` handles MP3 natively — no conversion needed.

---

### 1. Bridge Server (Python, Termux)

A small Flask server that runs alongside Hermes and exposes a local REST API.

**Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| POST | `/chat` | Send text message, get text response |
| POST | `/voice` | Upload audio file, get audio response |
| GET | `/sessions` | List chat sessions |
| GET | `/sessions/{id}` | Get session history |
| DELETE | `/sessions/{id}` | Delete session |
| GET | `/health` | Ping / status check |

**Voice endpoint flow:**
1. Receive audio file (WAV/OGG from Android)
2. Send to Groq Whisper → get transcript
3. Send transcript to Hermes → get text response
4. Pass response through Edge TTS → get audio file
5. Return audio file to app

**Session storage:** SQLite via Python `sqlite3` (stdlib, no extra deps).

### 2. Android App (Kotlin)

**Language:** Kotlin
**UI framework:** Jetpack Compose
**Min SDK:** API 29 (Android 10)
**Target SDK:** API 34 (Android 14)

#### Screens

- **Chat screen** — message list + text input + voice button
- **Sessions screen** — list of past sessions, tap to switch
- **Settings screen** — server URL, API keys, TTS/STT config

#### Key libraries

```kotlin
// build.gradle.kts dependencies
implementation("com.squareup.retrofit2:retrofit:2.9.0")          // HTTP client
implementation("com.squareup.retrofit2:converter-gson:2.9.0")    // JSON
implementation("com.squareup.okhttp3:okhttp:4.12.0")             // WebSocket
implementation("androidx.compose.ui:ui:1.6.0")                   // Compose UI
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
implementation("androidx.room:room-runtime:2.6.1")               // Local DB cache
```

No third-party AI SDKs needed — all AI runs on the Termux side.

#### Audio recording

Use Android's built-in `MediaRecorder` API — no extra libraries.
- Format: OGG/Opus (good compression, Android native)
- Send as multipart/form-data to bridge server

#### Permissions required

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

No other permissions needed — the app only talks to localhost.

---

## Communication

The app connects to `http://localhost:5000` (or configurable port).

Since both app and server run on the same device, no authentication is needed. No SSL required for localhost.

**Text chat request:**
```json
POST /chat
{
  "message": "Hello",
  "session_id": "abc123"
}
```

**Text chat response:**
```json
{
  "response": "Hello! How can I help?",
  "session_id": "abc123"
}
```

**Voice:** multipart upload of audio file, returns audio file in response.

---

## Session Management

Sessions stored in SQLite on the bridge server side. App fetches session list on startup and caches locally via Room.

Each session has:
- `id` (UUID)
- `title` (first message truncated)
- `created_at`
- `updated_at`
- `messages[]`

---

## Startup Flow

1. User opens app
2. App pings `GET /health`
3. If no response → show "Start Hermes in Termux" instruction screen
4. If response → load sessions, open last active session
5. User types or holds mic button to record
6. Send to bridge → display response (text) or play audio (voice)

---

## Bridge Server Startup (Termux)

Add to `~/.termux/boot/start.sh`:

```bash
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
tmux new-session -d -s hermes 'hermes'
tmux new-session -d -s bridge 'python ~/hermes-bridge/server.py'
```

---

## Build & Run

1. Clone/create project in Android Studio
2. Connect phone via USB, enable USB debugging
3. Run → select device → install APK
4. Or: Build → Generate Signed APK for sideload

No Play Store account needed for personal use — install directly via ADB or Android Studio.

---

## Limitations

- App requires Termux and bridge server running in background
- Android may kill Termux background processes despite wake-lock
- Voice latency: ~1-3s (Groq STT round-trip + Edge TTS generation)
- No push notifications — app must be open to receive responses
- Bridge server on localhost only — not accessible remotely

---

## Estimated Development Effort

| Component | Effort |
|-----------|--------|
| Bridge server (Flask) | 2-4 hours |
| Android app skeleton + chat UI | 4-6 hours |
| Voice record/playback | 2-3 hours |
| Session management | 2-3 hours |
| Settings screen | 1-2 hours |
| Polish + error handling | 2-4 hours |
| **Total** | **~13-22 hours** |

---

## Future Extensions

- WebSocket for streaming responses (instead of waiting for full reply)
- Background service to keep bridge alive
- Widget for quick voice input from home screen
- Notification when Hermes completes a long-running task
