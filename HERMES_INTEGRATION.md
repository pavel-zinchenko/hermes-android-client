# Hermes Integration Notes

How the Hermes Android app talks to the Hermes Agent. This is the result of an
investigation of the vendored Hermes source at `./hermes-agent/`, recorded so we
don't have to re-derive it.

## TL;DR — one backend, two channels, no bridge

The original techspec (`hermes_android_app_techspec.md`) assumed we'd build a Python
Flask "bridge" that shells out to the Hermes CLI. That is unnecessary: Hermes ships a
**dashboard** server (`hermes dashboard`, `127.0.0.1:9119`) that the app talks to
**directly** over localhost. No bridge process exists.

The app drives that one server over two channels:

1. **Gateway WebSocket** (`/api/ws`) — a JSON-RPC dialect (the same one the Ink TUI
   and Electron desktop app use) for **chat + sessions**, with streaming responses,
   live tool/thinking activity, and interactive requests. See `data/gateway/`.
2. **REST audio** (`/api/audio/transcribe`, `/api/audio/speak`) — for **voice**
   (STT/TTS). The phone records OGG locally and plays returned audio; the server is
   only a provider proxy.

The legacy api_server (8642) has been retired — the dashboard is a strict superset
(gateway + `/api/sessions/*` + `/api/audio/*`). Sessions/messages persist to
`~/.hermes/state.db` (SQLite), the same store the Hermes CLI uses.

## The server

- Gateway protocol: `hermes-agent/tui_gateway/server.py` (JSON-RPC methods +
  `event` frames).
- REST audio + health: `hermes-agent/gateway/platforms/api_server.py` (mirrored by
  the dashboard web server, `hermes_cli/web_server.py`).
- Default bind: `127.0.0.1:9119`.
- Auth: the gateway accepts the session token as a `?token=` query param in loopback
  mode (`ws://…/api/ws?token=<HERMES_DASHBOARD_SESSION_TOKEN>`); REST routes use
  `Authorization: Bearer <token>` (except `/health`, which is unauthenticated).

### Enabling it

Run the dashboard on-device:

```bash
hermes dashboard          # serves gateway + REST + audio on 9119
```

Smoke test from the same device:

```bash
curl http://127.0.0.1:9119/health
```

## Transport contract used by the app

### Gateway (chat + sessions) — `tui_gateway/server.py`

- **Client → server:** `{jsonrpc:"2.0", id, method, params}` → `{... result}` or
  `{... error}`.
- **Server → client:** unsolicited `{jsonrpc:"2.0", method:"event",
  params:{type, session_id, payload}}` frames, dispatched by `type`.
- The gateway keys a per-connection **live** session id, distinct from the stored DB
  id the app carries; `session.resume` maps stored → live (and returns the
  transcript). See `HermesRepository` for the mapping.

| App action | Method | Notes |
|---|---|---|
| List sessions | `session.list` | → rows mapped to `ChatSession` |
| Create session | `session.create` | `{source, title?}`; must originate here so the session carries the gateway's model |
| Resume / history | `session.resume` | `{session_id}` → live sid + transcript |
| Delete | `session.delete` | `{session_id}` (refuses a live session, RPC 4023) |
| Send turn | `prompt.submit` | `{session_id, text}` → `{status:"streaming"}`; answer arrives as `message.*` / `tool.*` / `thinking.*` events |
| Stop | `session.interrupt` | `{session_id}` |
| Approvals / clarify / secret / sudo | `approval.respond` / `clarify.respond` / `secret.respond` / `sudo.respond` | responses to the matching `*.request` events |

Voice mode reuses `prompt.submit` (via `HermesRepository.sendMessageBlocking`, which
collects the stream to its `message.complete`) rather than a separate REST chat call.

> **Why voice does not use the gateway `voice.*` methods.** `voice.record` →
> `start_continuous()` captures from the **server's** microphone; `voice.tts` →
> `speak_text()` plays through the **server's** speakers and returns no audio
> (`tui_gateway/server.py` ~10011–10101). That's the desktop/TUI model. On Android
> the phone owns audio I/O, so voice uses the REST audio endpoints below instead.

### REST audio + health

| App action | Method + path | Request | Response |
|---|---|---|---|
| Reachability | `GET /health` | — (no auth) | `{status:"ok", platform, version}` |
| Transcribe (STT) | `POST /api/audio/transcribe` | `{data_url, mime_type}` | `{ok, transcript, provider}` |
| Speak (TTS) | `POST /api/audio/speak` | `{text}` | `{ok, data_url, mime_type, provider}` |

Audio payloads are base64 `data:` URLs in both directions.

## Android-side gotchas

- **Cleartext to localhost.** Android blocks cleartext HTTP by default on API 29+.
  The app ships a `network_security_config.xml` that permits cleartext only to
  `127.0.0.1` / `localhost`. (No TLS is needed; both ends are on-device.)
- The app and Hermes run on the **same phone**, so `127.0.0.1:9119` resolves to the
  dashboard process. For an emulator on a dev machine running Hermes on the host, use
  `10.0.2.2:9119` instead.
- Hermes must have a working LLM provider key configured (e.g. `OPENROUTER_API_KEY`)
  in `~/.hermes/.env`, or `/chat` will error — that's Hermes config, not the app's.

## Termux notes

- Install: `pip install -e '.[termux]' -c constraints-termux.txt` (see
  `hermes-agent/website/docs/getting-started/termux.md`).
- Local Whisper (`faster-whisper`/`ctranslate2`) has no Android wheels, so voice STT
  must use a cloud provider (Groq/OpenAI) — configured server-side; the app just
  ships audio bytes to `/api/audio/*`.
- Android may suspend Termux background processes; keeping `hermes dashboard` alive
  benefits from `termux-wake-lock`.

## Deferred (future) integration work

- Media attachments (image/audio/video) and phone-camera-to-chat.
- Backgrounding: a foreground service for long turns vs. reconnect-and-rehydrate
  (see `rpc-gateway.md` risks).
