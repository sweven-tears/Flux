package cn.luoluna.sweven.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolJsonRedactionTest {
    @Test
    fun toolErrorJsonRedactsUpstreamCredentialFormats() {
        val body = toolErrorJson("HTTP 401 sk-abcdefgh12345678; Bearer very-secret-token-987")
        val message = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonPrimitive.content

        assertFalse(message.contains("sk-abcdefgh12345678"))
        assertFalse(message.contains("very-secret-token-987"))
        assertTrue(message.contains("sk-***"))
        assertTrue(message.contains("Bearer ***"))
    }
}
