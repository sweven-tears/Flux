package cn.luoluna.sweven.ai.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {
    @Test
    fun redactsApiKeysBearerAndJsonSecrets() {
        val output = SensitiveDataRedactor.redactUpstreamError(
            "401 sk-abcdefgh12345678; Bearer abcdefghijklmnop; " +
                "{\"api_key\":\"api-key-secret-123\",\"token\":\"token-secret-456\",\"authorization\":\"Bearer auth-secret-789\"}"
        )
        listOf("sk-abcdefgh12345678", "abcdefghijklmnop", "api-key-secret-123", "token-secret-456", "auth-secret-789")
            .forEach { assertFalse("Leaked $it in $output", output.contains(it)) }
        assertTrue(output.contains("sk-***"))
        assertTrue(output.contains("Bearer ***"))
        assertTrue(output.contains("\"api_key\":\"***\""))
    }

    @Test
    fun redactsLongHighEntropyStringsAndKnownInjectedKeys() {
        val knownKey = "known-test-key-7f9b2c"
        val highEntropy = "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6"
        val output = SensitiveDataRedactor.redactText("detail $knownKey $highEntropy", knownSecrets = listOf(knownKey), maxLength = 300)
        assertFalse(output.contains(knownKey))
        assertFalse(output.contains(highEntropy))
        assertTrue(output.contains("detail"))

        val explicitlyRedacted = SensitiveDataRedactor.redactText("provider returned short-key", knownSecrets = listOf("short-key"))
        assertFalse(explicitlyRedacted.contains("short-key"))
    }

    @Test
    fun redactsCredentialUrlsHeadersAndSecretsBeforeLengthLimit() {
        assertEquals("https://example.test/path?api_key=***&page=1", SensitiveDataRedactor.redactUrl("https://example.test/path?api_key=sk-abcdefgh12345678&page=1"))
        assertEquals("***", SensitiveDataRedactor.redactHeader("Authorization", "Bearer very-secret-value"))
        assertEquals("***", SensitiveDataRedactor.redactHeader("X-Device-Token", "device-secret-987654"))
        assertEquals("https://example.test/path?X-Device-Token=***", SensitiveDataRedactor.redactUrl("https://example.test/path?X-Device-Token=device-secret-987654"))
        val long = "prefix sk-abcdefgh12345678 " + "y ".repeat(200)
        val output = SensitiveDataRedactor.redactUpstreamError(long)
        assertEquals(300, output.length)
        assertFalse(output.contains("sk-abcdefgh12345678"))
        assertEquals(4, SensitiveDataRedactor.redactText("123456", maxLength = 4).length)
        assertEquals("", SensitiveDataRedactor.redactUpstreamError(null))
    }

    @Test
    fun preservesShortBenignTextAndRedactsKnownRealLookingApiKeyPattern() {
        assertEquals("invalid request", SensitiveDataRedactor.redactUpstreamError("invalid request"))
        val output = SensitiveDataRedactor.redactUpstreamError("error sk-abcdefgh12345678")
        assertEquals("error sk-***", output)
    }
}
