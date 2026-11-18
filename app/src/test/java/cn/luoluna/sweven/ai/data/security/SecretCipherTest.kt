package cn.luoluna.sweven.ai.data.security

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class SecretCipherTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripUsesRandomNonceAndNeverStoresPlaintext() {
        val key = newKey()
        val cipher = SecretCipher { key }
        val secret = "sk-test-密钥-1234567890"
        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)
        assertTrue(SecretCipher.isEncrypted(first))
        assertFalse(first.contains(secret))
        assertNotEquals(first, second)
        assertEquals(secret, cipher.decrypt(first))
        assertEquals(secret, cipher.decrypt(second))
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test fun tamperingAndWrongKeyFailClosed() {
        val key = newKey()
        val cipher = SecretCipher { key }
        val stored = cipher.encrypt("secret")
        val encoded = stored.substringAfterLast(':')
        val bytes = Base64.getDecoder().decode(encoded)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val corrupt = stored.substringBeforeLast(':') + ":" + Base64.getEncoder().encodeToString(bytes)
        assertThrows(Exception::class.java) { cipher.decrypt(corrupt) }
        assertThrows(Exception::class.java) { SecretCipher { newKey() }.decrypt(stored) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("plaintext-is-not-accepted") }
    }
}
