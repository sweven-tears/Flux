package cn.luoluna.sweven.ai.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque

/** Pure-JVM helpers shared by gateway authorization and regression tests. */
internal object DeviceAuthSecurity {
    private val random = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    /** Generates a 256-bit pairing secret as 64 lowercase hexadecimal characters. */
    fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encodeHex(bytes)
    }

    fun sha256Hex(value: String): String = encodeHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    )

    /** Compare stored hashes in constant time; malformed hashes never authenticate. */
    fun matches(tokenHash: String, token: String?): Boolean {
        if (tokenHash.length != 64 || token == null || token.length != 64) return false
        if (!token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        val expected = decodeHex(tokenHash) ?: return false
        val actual = runCatching { MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)) }
            .getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    /** Accepts X-Device-Token or Authorization: Bearer <token>, preferring the explicit header. */
    fun tokenFromHeaders(deviceToken: String?, authorization: String?): String? {
        deviceToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val value = authorization?.trim() ?: return null
        val separator = value.indexOf(' ')
        if (separator <= 0 || !value.substring(0, separator).equals("Bearer", ignoreCase = true)) return null
        return value.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != 64) return null
        val result = ByteArray(32)
        for (i in result.indices) {
            val hi = value[i * 2].digitToIntOrNull(16) ?: return null
            val lo = value[i * 2 + 1].digitToIntOrNull(16) ?: return null
            result[i] = ((hi shl 4) or lo).toByte()
        }
        return result
    }

    private fun encodeHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

/** Bounded per-IP rolling-window failure limiter; safe for concurrent server requests. */
class AuthFailureLimiter(
    private val maxFailures: Int = 10,
    private val windowMs: Long = 60_000L,
    private val maxTrackedIps: Int = 4096,
) {
    private val failures = LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true)

    @Synchronized
    fun recordFailure(ip: String, now: Long): Boolean {
        val times = failures.getOrPut(ip) { ArrayDeque() }
        prune(times, now)
        times.addLast(now)
        while (failures.size > maxTrackedIps) {
            val iterator = failures.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return times.size >= maxFailures
    }

    @Synchronized
    fun isLimited(ip: String, now: Long): Boolean {
        val times = failures[ip] ?: return false
        prune(times, now)
        if (times.isEmpty()) failures.remove(ip)
        return times.size >= maxFailures
    }

    private fun prune(times: ArrayDeque<Long>, now: Long) {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
    }
}
