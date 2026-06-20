package com.hermes.android.data.gateway

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * End-to-end framing tests for [HermesGateway] against a real in-process
 * WebSocket (OkHttp MockWebServer). Exercises the JSON-RPC dialect the way the
 * Hermes `tui_gateway` speaks it: id-correlated request/response plus
 * fire-and-forget server events.
 */
class HermesGatewayTest {

    private lateinit var server: MockWebServer
    private val gson = Gson()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Echoes the request id into a result, and pushes one event after replying. */
    private fun enqueueServer(
        onClientFrame: (server: WebSocket, frame: JsonObject) -> Unit,
    ) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JsonParser.parseString(text).asJsonObject
                    onClientFrame(webSocket, frame)
                }
            })
        )
        server.start()
    }

    private fun newGateway() = HermesGateway(OkHttpClient(), gson)

    @Test
    fun `request resolves with the matching result`() = runBlocking {
        enqueueServer { ws, frame ->
            val id = frame.get("id").asString
            val reply = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("result", JsonObject().apply { addProperty("session_id", "live123") })
            }
            ws.send(gson.toJson(reply))
        }
        val gateway = newGateway()
        gateway.connect(server.url("/api/ws").toString())

        val result = withTimeout(3000) {
            gateway.call("session.resume", mapOf("session_id" to "stored"))
        }
        val resume = gson.fromJson(result, ResumeResult::class.java)
        assertEquals("live123", resume.sessionId)
        assertEquals(ConnectionState.OPEN, gateway.connectionState.value)
    }

    @Test
    fun `server event is dispatched to on(type) subscribers`() = runBlocking {
        enqueueServer { ws, frame ->
            val id = frame.get("id").asString
            ws.send(gson.toJson(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("result", JsonObject())
            }))
            // Then push an unsolicited event frame.
            val event = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("method", "event")
                add("params", JsonObject().apply {
                    addProperty("type", "message.delta")
                    addProperty("session_id", "live123")
                    add("payload", JsonObject().apply { addProperty("text", "hello") })
                })
            }
            ws.send(gson.toJson(event))
        }
        val gateway = newGateway()
        gateway.connect(server.url("/api/ws").toString())

        val received = CompletableDeferred<GatewayEvent>()
        val job = launch {
            gateway.on("message.delta").collect { received.complete(it) }
        }
        delay(150) // let the subscription register before the server emits

        gateway.call("noop")
        val event = withTimeout(3000) { received.await() }
        job.cancel()

        assertEquals("message.delta", event.type)
        assertEquals("live123", event.sessionId)
        val payload = gson.fromJson(event.payload, DeltaPayload::class.java)
        assertEquals("hello", payload.text)
    }

    @Test
    fun `rpc error rejects the call`() = runBlocking {
        enqueueServer { ws, frame ->
            val id = frame.get("id").asString
            ws.send(gson.toJson(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                add("error", JsonObject().apply {
                    addProperty("code", 4009)
                    addProperty("message", "session busy")
                })
            }))
        }
        val gateway = newGateway()
        gateway.connect(server.url("/api/ws").toString())

        try {
            withTimeout(3000) { gateway.call("prompt.submit") }
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertEquals("session busy", e.message)
            assertEquals(4009, e.code)
        }
    }

    @Test
    fun `call before connect fails fast`() = runBlocking {
        val gateway = newGateway()
        try {
            gateway.call("session.list")
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertTrue(e.message!!.contains("not connected"))
        }
    }
}
