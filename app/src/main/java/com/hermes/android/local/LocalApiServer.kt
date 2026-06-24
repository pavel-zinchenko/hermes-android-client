package com.hermes.android.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import fi.iki.elonen.NanoHTTPD

/**
 * Tiny loopback HTTP server that lets the Hermes agent trigger in-app actions it
 * cannot perform from the Termux shell (anything needing this app's UI or Android
 * SDK access). Hermes discovers the menu via `GET /capabilities` and invokes an
 * action via `POST /action`.
 *
 * Bound to `127.0.0.1` only, so it is reachable from other local processes (Termux
 * `curl 127.0.0.1:7117`) but never from the network. Listening on loopback needs no
 * `INTERNET` permission. NanoHTTPD runs [serve] on its own worker threads; matched
 * actions are handed to [onAction], which forwards them to the UI without blocking.
 */
class LocalApiServer(
    port: Int = DEFAULT_PORT,
    private val onAction: (AppAction) -> Unit,
) : NanoHTTPD(LOOPBACK_HOST, port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        // Even though we bind to loopback, a page in the device's browser can reach
        // us via DNS rebinding (its Host header keeps the attacker's domain). Reject
        // any request whose Host isn't loopback so only on-device clients (Termux
        // curl) get through.
        if (!isLoopbackHost(session)) {
            return json(Response.Status.FORBIDDEN, """{"error":"forbidden"}""")
        }
        return when {
            session.method == Method.GET && session.uri == "/capabilities" ->
                json(Response.Status.OK, CAPABILITIES_JSON)

            session.method == Method.POST && session.uri == "/action" ->
                handleAction(session)

            else -> json(Response.Status.NOT_FOUND, """{"error":"not found"}""")
        }
    }

    /** True if the request's Host header targets loopback (or is absent, as with
     *  some minimal non-browser clients). The port suffix is ignored. */
    private fun isLoopbackHost(session: IHTTPSession): Boolean {
        val host = session.headers["host"]?.substringBeforeLast(":")?.trim()
            ?: return true
        return host == LOOPBACK_HOST || host == "localhost"
    }

    private fun handleAction(session: IHTTPSession): Response {
        // For non-form bodies NanoHTTPD stashes the raw payload under "postData".
        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (e: Exception) {
            return json(Response.Status.BAD_REQUEST, """{"error":"unreadable body"}""")
        }

        val request = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            return json(Response.Status.BAD_REQUEST, """{"error":"invalid json"}""")
        } ?: return json(Response.Status.BAD_REQUEST, """{"error":"empty body"}""")

        val type = request.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val params = request.getAsJsonObject("params") ?: JsonObject()

        val action = when (type) {
            "show_snackbar" -> {
                val message = params.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return json(Response.Status.BAD_REQUEST, """{"error":"missing params.message"}""")
                AppAction.ShowSnackbar(message)
            }

            null -> return json(Response.Status.BAD_REQUEST, """{"error":"missing type"}""")
            else -> return json(Response.Status.BAD_REQUEST, """{"error":"unknown type: $type"}""")
        }

        onAction(action)
        return json(Response.Status.OK, """{"ok":true}""")
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    companion object {
        const val DEFAULT_PORT = 7117
        private const val LOOPBACK_HOST = "127.0.0.1"

        /** Returned by `GET /capabilities`; the menu Hermes reads before acting. */
        private val CAPABILITIES_JSON = """
            [
              {
                "type": "show_snackbar",
                "description": "Show a snackbar message inside the hermes-android app",
                "params": { "message": "string — text to display" }
              }
            ]
        """.trimIndent()
    }
}
