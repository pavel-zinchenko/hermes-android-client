# Hermes Integration Notes

How the Hermes Android app talks to the Hermes Agent. This is the result of an
investigation of the vendored Hermes source at `./hermes-agent/`, recorded so we
don't have to re-derive it.

## TL;DR — there is no custom bridge

The original techspec (`hermes_android_app_techspec.md`) assumed we'd build a Python
Flask "bridge" that shells out to the Hermes CLI. That turned out to be unnecessary:
**Hermes already ships a complete, OpenAI-compatible REST API server** that exposes
everything a text chat client needs. The Android app talks to it **directly** over
localhost. No bridge process exists in v1.

A thin bridge would only be re-introduced later for **voice** (Groq Whisper STT +
Edge TTS), because the Termux install (`.[termux]`) can't run local Whisper. That is
out of scope for the current text-only MVP.

## The server

- Implementation: `hermes-agent/gateway/platforms/api_server.py` (~196 KB, `aiohttp`).
- Routes are registered at `api_server.py:4169-4204`.
- Default bind: `127.0.0.1:8642` (`api_server.py:88-89`, constants
  `DEFAULT_HOST` / `DEFAULT_PORT`).
- Auth: `Authorization: Bearer <API_SERVER_KEY>` on every route **except** `/health`
  (`_check_auth` at `api_server.py:897`; `_handle_health` at `:1077`).
- Sessions/messages persist to `~/.hermes/state.db` (SQLite) — the same store the
  Hermes CLI uses, so chats started here show up in the CLI and vice versa.

### Enabling it (off by default)

Configured purely through environment variables, read in
`gateway/config.py:1651-1675`:

```
API_SERVER_ENABLED=true
API_SERVER_KEY=<secret, min 8 chars for network access>
# optional:
API_SERVER_PORT=8642
API_SERVER_HOST=127.0.0.1
API_SERVER_MODEL_NAME=hermes-agent
```

Put these in `~/.hermes/.env` (or export them), then start the gateway:

```bash
hermes gateway run
```

Smoke test from the same device:

```bash
curl http://127.0.0.1:8642/health
curl -H "Authorization: Bearer <key>" http://127.0.0.1:8642/api/sessions
```

## Endpoint contract used by the app

Verified against the source (handler line numbers in `api_server.py`).

| App action | Method + path | Request | Response |
|---|---|---|---|
| Reachability | `GET /health` | — (no auth) | `{status:"ok", platform, version}` (`:1077`) |
| List sessions | `GET /api/sessions` | `?limit&offset` | `{object:"list", data:[session]}` (`:1362`) |
| Create session | `POST /api/sessions` | `{model?, title?}` | `201 {object:"hermes.session", session}` (`:1391`) |
| Get session | `GET /api/sessions/{id}` | — | `{session}` (`:1428`) |
| Rename | `PATCH /api/sessions/{id}` | `{title}` | `{session}` (`:1438`) |
| Delete | `DELETE /api/sessions/{id}` | — | `{deleted:true}` (`:1466`) |
| History | `GET /api/sessions/{id}/messages` | — | `{object:"list", data:[message]}` (`:1479`) |
| Send (sync) | `POST /api/sessions/{id}/chat` | `{message}` | `{message:{role,content}, usage}` (`:1544`) |
| Send (stream) | `POST /api/sessions/{id}/chat/stream` | `{message}` | SSE events (`:1588`) — deferred |

There is also a stateless OpenAI-shaped `POST /v1/chat/completions`. The app uses the
**stateful `/api/sessions/...` endpoints** instead, because they map directly to the
Sessions and Chat screens and let Hermes own conversation persistence.

### Shapes

`_session_response` (`api_server.py:1308`) — client-safe session fields:
`id, source, model, title, started_at, ended_at, end_reason, message_count,
tool_call_count, parent_session_id, last_active, preview, has_system_prompt,
has_model_config` (plus token/cost counters).

`_message_response` (`api_server.py:1326`):
`id, session_id, role, content, tool_call_id, tool_calls, tool_name, timestamp,
token_count, finish_reason, reasoning`.

`/chat` accepts `message` **or** `input` (`_session_chat_user_message` at `:352`) and
returns:

```json
{
  "object": "hermes.session.chat.completion",
  "session_id": "api_...",
  "message": { "role": "assistant", "content": "..." },
  "usage": { "input_tokens": 0, "output_tokens": 0, "total_tokens": 0 }
}
```

## Android-side gotchas

- **Cleartext to localhost.** Android blocks cleartext HTTP by default on API 29+.
  The app ships a `network_security_config.xml` that permits cleartext only to
  `127.0.0.1` / `localhost`. (No TLS is needed; both ends are on-device.)
- The app and Hermes run on the **same phone**, so `127.0.0.1:8642` resolves to the
  Hermes process. For an emulator on a dev machine running Hermes on the host, use
  `10.0.2.2:8642` instead.
- Hermes must have a working LLM provider key configured (e.g. `OPENROUTER_API_KEY`)
  in `~/.hermes/.env`, or `/chat` will error — that's Hermes config, not the app's.

## Termux notes

- Install: `pip install -e '.[termux]' -c constraints-termux.txt` (see
  `hermes-agent/website/docs/getting-started/termux.md`).
- Local Whisper (`faster-whisper`/`ctranslate2`) has no Android wheels — voice STT
  would need a cloud provider (Groq/OpenAI). This is why voice is deferred.
- Android may suspend Termux background processes; keeping `hermes gateway run` alive
  benefits from `termux-wake-lock`.

## Deferred (future) integration work

- Voice: add Groq Whisper STT + Edge TTS. Likely a small local helper rather than
  routing through this API server, since voice tooling isn't in the `.[termux]` deps.
- Media attachments (image/audio/video) and phone-camera-to-chat.
- SSE streaming via `/api/sessions/{id}/chat/stream`.
