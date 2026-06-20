package com.hermes.android.data.gateway

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Gson decoding of the interactive-request payloads to the exact wire
 * shapes the Hermes `tui_gateway` emits (see tui_gateway/server.py), especially
 * the snake_case `@SerializedName` mappings.
 */
class InteractiveRequestPayloadTest {

    private val gson = Gson()

    private inline fun <reified T> parse(json: String): T =
        gson.fromJson(JsonParser.parseString(json).asJsonObject, T::class.java)

    @Test
    fun `approval payload decodes command, description and allow_permanent`() {
        val p = parse<ApprovalRequestPayload>(
            """{"command":"rm -rf /tmp/x","description":"recursive delete","allow_permanent":false}"""
        )
        assertEquals("rm -rf /tmp/x", p.command)
        assertEquals("recursive delete", p.description)
        assertEquals(false, p.allowPermanent)
    }

    @Test
    fun `clarify payload decodes question, choices and request_id`() {
        val p = parse<ClarifyRequestPayload>(
            """{"question":"Which file?","choices":["a.txt","b.txt"],"request_id":"abc123"}"""
        )
        assertEquals("Which file?", p.question)
        assertEquals(listOf("a.txt", "b.txt"), p.choices)
        assertEquals("abc123", p.requestId)
    }

    @Test
    fun `sudo payload decodes request_id`() {
        val p = parse<SudoRequestPayload>("""{"request_id":"sud0"}""")
        assertEquals("sud0", p.requestId)
    }

    @Test
    fun `secret payload decodes prompt, env_var and request_id`() {
        val p = parse<SecretRequestPayload>(
            """{"prompt":"Enter API key","env_var":"OPENAI_API_KEY","request_id":"sec1"}"""
        )
        assertEquals("Enter API key", p.prompt)
        assertEquals("OPENAI_API_KEY", p.envVar)
        assertEquals("sec1", p.requestId)
    }

    @Test
    fun `missing optional fields decode to null without throwing`() {
        val p = parse<ApprovalRequestPayload>("""{"command":"ls"}""")
        assertEquals("ls", p.command)
        assertNull(p.description)
        assertNull(p.allowPermanent)
    }

    @Test
    fun `unknown fields are ignored (lenient parsing)`() {
        val p = parse<ClarifyRequestPayload>(
            """{"question":"q","choices":[],"request_id":"r","extra":"ignored"}"""
        )
        assertEquals("q", p.question)
        assertTrue(p.choices!!.isEmpty())
        assertEquals("r", p.requestId)
    }
}
