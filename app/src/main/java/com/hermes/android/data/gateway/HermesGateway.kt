package com.hermes.android.data.gateway

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when a gateway RPC fails, times out, or the socket is unavailable. */
class GatewayException(message: String, val code: Int? = null) : Exception(message)

/**
 * JSON-RPC client over a single [okhttp3.WebSocket], speaking the Hermes
 * `tui_gateway` dialect. Mirrors `apps/shared/src/json-rpc-gateway.ts`:
 * id-correlated request/response plus fire-and-forget server events.
 *
 * One instance is shared app-wide (held by `HermesRepository`). It is safe to
 * call [connect]/[call] from multiple coroutines; a [Mutex] serializes connects
 * and the pending-call map is concurrent.
 */
class HermesGateway(
    private val client: OkHttpClient,
    private val gson: Gson,
) {
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val connectMutex = Mutex()

    /**
     * Incremented each time a fresh socket completes its handshake. A live sid is
     * only valid on the connection that minted it, so callers tag cached sids with
     * this id and discard them once it changes (i.e. after a reconnect).
     */
    private val _connectionId = AtomicInteger(0)
    val connectionId: Int get() = _connectionId.get()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var currentUrl: String? = null

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // replay=0: events are live. The WebSocket reader thread can't suspend, so it
    // hands each event to an unbounded [eventQueue]; a forwarder coroutine drains
    // that into the SharedFlow with a *suspending* emit, so a slow collector parks
    // the forwarder (backpressure) instead of dropping tokens. Events that arrive
    // with no subscriber are discarded at the source — none would be replayed.
    private val _events = MutableSharedFlow<GatewayEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val events: Flow<GatewayEvent> = _events

    private val eventQueue = Channel<GatewayEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            for (event in eventQueue) _events.emit(event)
        }
    }

    /** Stream of events of a single [type]. */
    fun on(type: String): Flow<GatewayEvent> = events.filter { it.type == type }

    /**
     * Ensures an open socket to [wsUrl]. Idempotent: returns immediately if
     * already open to the same URL; reconnects if the URL changed or the socket
     * dropped. Fails with [GatewayException] if the handshake doesn't land within
     * [connectTimeoutMs] (a half-open socket must error, never hang).
     */
    suspend fun connect(wsUrl: String, connectTimeoutMs: Long = 15_000) {
        if (socket != null && currentUrl == wsUrl && _connectionState.value == ConnectionState.OPEN) {
            return
        }
        connectMutex.withLock {
            if (socket != null && currentUrl == wsUrl && _connectionState.value == ConnectionState.OPEN) {
                return
            }
            // Drop any stale socket before reconnecting.
            closeInternal(ConnectionState.CONNECTING)
            _connectionState.value = ConnectionState.CONNECTING

            val opened = CompletableDeferred<Unit>()
            // OkHttp's Request.url accepts only http(s); coerce the ws scheme.
            val httpUrl = wsUrl
                .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
                .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            val request = Request.Builder().url(httpUrl).build()
            val ws = client.newWebSocket(request, Listener(opened))
            socket = ws
            currentUrl = wsUrl

            try {
                withTimeout(connectTimeoutMs) { opened.await() }
            } catch (e: Exception) {
                closeInternal(ConnectionState.ERROR)
                throw GatewayException(
                    if (e is TimeoutCancellationException) "gateway connection timed out"
                    else e.message ?: "gateway connection failed"
                )
            }
        }
    }

    /**
     * Sends a JSON-RPC request and suspends until the matching response. Returns
     * the raw `result` element (callers deserialize with their own Gson). Throws
     * [GatewayException] on RPC error, timeout, or a closed socket.
     */
    suspend fun call(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 120_000,
    ): JsonElement {
        val ws = socket
            ?: throw GatewayException("gateway not connected")
        if (_connectionState.value != ConnectionState.OPEN) {
            throw GatewayException("gateway not connected")
        }

        val id = "r${nextId.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred

        val frame = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", gson.toJsonTree(params))
        }

        if (!ws.send(gson.toJson(frame))) {
            pending.remove(id)
            throw GatewayException("failed to send: $method")
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw GatewayException("request timed out: $method")
        }
    }

    fun close() {
        closeInternal(ConnectionState.CLOSED)
    }

    private fun closeInternal(newState: ConnectionState) {
        socket?.cancel()
        socket = null
        currentUrl = null
        rejectAllPending(GatewayException("gateway closed"))
        _connectionState.value = newState
    }

    private fun rejectAllPending(error: Throwable) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            it.next().value.completeExceptionally(error)
            it.remove()
        }
    }

    private fun handleMessage(text: String) {
        val frame = runCatching { gson.fromJson(text, JsonRpcFrame::class.java) }.getOrNull()
            ?: return

        // Response to a pending call.
        if (frame.id != null) {
            val call = pending.remove(frame.id) ?: return
            val error = frame.error
            if (error != null) {
                call.completeExceptionally(
                    GatewayException(error.message ?: "Hermes RPC failed", error.code)
                )
            } else {
                call.complete(frame.result ?: JsonObject())
            }
            return
        }

        // Unsolicited server event. Drop it at the source when nobody is
        // listening (replay=0 means it could never be delivered anyway); otherwise
        // hand it to the unbounded queue — trySend never fails on UNLIMITED.
        if (frame.method == "event" && frame.params?.type != null) {
            if (_events.subscriptionCount.value == 0) return
            eventQueue.trySend(
                GatewayEvent(
                    type = frame.params.type,
                    sessionId = frame.params.sessionId,
                    payload = frame.params.payload,
                )
            )
        }
    }

    private inner class Listener(
        private val opened: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            _connectionId.incrementAndGet()
            _connectionState.value = ConnectionState.OPEN
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socket = null
            currentUrl = null
            rejectAllPending(GatewayException("gateway closed"))
            _connectionState.value = ConnectionState.CLOSED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            currentUrl = null
            rejectAllPending(GatewayException(t.message ?: "gateway connection failed"))
            _connectionState.value = ConnectionState.ERROR
            if (!opened.isCompleted) opened.completeExceptionally(t)
        }
    }
}
