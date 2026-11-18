package cn.luoluna.sweven.ai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthSecurityTest {
    @Test fun tokenIsRandom256BitHexAndOnlyHashMatches() {
        val first = DeviceAuthSecurity.newToken()
        val second = DeviceAuthSecurity.newToken()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, second)
        val hash = DeviceAuthSecurity.sha256Hex(first)
        assertEquals(64, hash.length)
        assertTrue(DeviceAuthSecurity.matches(hash, first))
        assertFalse(DeviceAuthSecurity.matches(hash, second))
        assertFalse(DeviceAuthSecurity.matches("bad", first))
        assertFalse(DeviceAuthSecurity.matches(hash, null))
    }

    @Test fun supportedHeadersAreParsedWithoutAcceptingOtherSchemes() {
        assertEquals("abc", DeviceAuthSecurity.tokenFromHeaders(" abc ", "Bearer ignored"))
        assertEquals("abc", DeviceAuthSecurity.tokenFromHeaders(null, "Bearer abc"))
        assertEquals("abc", DeviceAuthSecurity.tokenFromHeaders(null, "bearer abc"))
        assertNull(DeviceAuthSecurity.tokenFromHeaders(null, "Basic abc"))
        assertNull(DeviceAuthSecurity.tokenFromHeaders(null, "Bearer"))
        assertNull(DeviceAuthSecurity.tokenFromHeaders(" ", null))
    }

    @Test fun failuresAreLimitedPerIpOverRollingMinute() {
        val limiter = AuthFailureLimiter(maxFailures = 3, windowMs = 60_000)
        assertFalse(limiter.recordFailure("a", 1_000))
        assertFalse(limiter.recordFailure("a", 2_000))
        assertTrue(limiter.recordFailure("a", 3_000))
        assertTrue(limiter.isLimited("a", 4_000))
        assertFalse(limiter.isLimited("b", 4_000))
        assertFalse(limiter.isLimited("a", 61_000))
    }

    @Test fun limiterRetainsBoundedIpState() {
        val limiter = AuthFailureLimiter(maxFailures = 2, windowMs = 100, maxTrackedIps = 2)
        limiter.recordFailure("one", 0)
        limiter.recordFailure("two", 0)
        limiter.recordFailure("three", 0)
        assertFalse(limiter.isLimited("one", 1))
        assertTrue(limiter.recordFailure("three", 1))
    }
}
