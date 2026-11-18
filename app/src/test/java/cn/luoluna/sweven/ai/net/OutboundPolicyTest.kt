package cn.luoluna.sweven.ai.net

import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** Real OkHttp calls through loopback fixture sockets; no external DNS, Internet, Android, or MockWebServer. */
class OutboundPolicyTest {
    private val publicIp = ip("93.184.216.34")
    private val privateIp = ip("192.168.50.2")

    @Test fun blocksForbiddenIpv4InBothModes() {
        val forbidden = listOf(
            "0.0.0.0", "0.1.2.3", "127.0.0.1", "127.255.1.2", "169.254.169.254", "169.254.170.2",
            "100.64.0.1", "100.100.100.200", "100.127.255.254", "224.0.0.1", "239.255.255.250",
            "240.0.0.1", "255.255.255.255", "168.63.129.16", "192.0.0.1", "198.18.0.1",
            "192.0.2.1", "198.51.100.1", "203.0.113.1",
        )
        for (mode in OutboundPolicy.Mode.values()) for (literal in forbidden) {
            assertDenied { resolve(literal, mode) }
        }
    }

    @Test fun onlyExplicitLanModeAllowsRfc1918AndUla() {
        for (literal in listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.1", "fc00::1", "fd12:3456::1")) {
            assertDenied { resolve(literal) }
            resolve(literal, OutboundPolicy.Mode.ALLOW_LAN)
        }
        for (literal in listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "2606:4700:4700::1111")) resolve(literal)
    }

    @Test fun blocksIpv6BypassesAndMetadataInBothModes() {
        val forbidden = listOf(
            "::", "::1", "fe80::1", "fec0::1", "ff02::1", "::127.0.0.1", "::ffff:127.0.0.1",
            "::ffff:169.254.169.254", "64:ff9b::7f00:1", "64:ff9b:1::c0a8:1", "2002:7f00:1::",
            "2001::1", "2001:2::1", "2001:db8::1", "3fff::1", "fd00:ec2::254",
            "2606:4700::200:5efe:7f00:1",
        )
        for (mode in OutboundPolicy.Mode.values()) for (literal in forbidden) assertDenied { resolve(literal, mode) }
    }

    @Test fun canonicalizedNumericHostsCannotBypassValidation() {
        for (host in listOf("2130706433", "127.1", "0177.0.0.1", "0x7f000001", "[::ffff:127.0.0.1]")) {
            val url = "http://$host/".toHttpUrl()
            // JVM's resolver differs from Android for noncanonical forms: either denial or DNS failure is safe.
            assertDenied { OutboundPolicy.resolve(url, OutboundPolicy.Mode.PUBLIC_ONLY, Dns.SYSTEM) { emptyList() } }
        }
    }

    @Test fun deniesMixedDnsAnswersEmptyAnswersAndResolutionErrors() {
        val url = "https://mixed.test/".toHttpUrl()
        assertDenied { OutboundPolicy.resolve(url, OutboundPolicy.Mode.PUBLIC_ONLY, dns { listOf(publicIp, privateIp) }) { emptyList() } }
        assertDenied { OutboundPolicy.resolve(url, OutboundPolicy.Mode.PUBLIC_ONLY, dns { emptyList() }) { emptyList() } }
        assertDenied { OutboundPolicy.resolve(url, OutboundPolicy.Mode.PUBLIC_ONLY, dns { error("secret DNS diagnostics") }) { emptyList() } }
        assertDenied { OutboundPolicy.resolve(url, OutboundPolicy.Mode.ALLOW_LAN, dns { listOf(privateIp) }) { error("interfaces unavailable") } }
    }

    @Test fun deniesOwnGatewayByHostnameAndResolvedInterfaceEvenInLanMode() {
        for (host in listOf("flux.local", "my.flux.local", "localhost", "localhost.")) {
            assertDenied { OutboundPolicy.resolve("http://$host:8787/v1/models".toHttpUrl(), OutboundPolicy.Mode.ALLOW_LAN, dns { listOf(privateIp) }) { emptyList() } }
        }
        assertDenied { OutboundPolicy.resolve("http://alias.test:8787/v1/models".toHttpUrl(), OutboundPolicy.Mode.ALLOW_LAN, dns { listOf(privateIp) }) { listOf(privateIp) } }
        assertDenied { OutboundPolicy.resolve("https://public-alias.test/".toHttpUrl(), OutboundPolicy.Mode.PUBLIC_ONLY, dns { listOf(publicIp) }) { listOf(publicIp) } }
        assertDenied { OutboundPolicy.resolve("http://name:secret@public.test/".toHttpUrl(), OutboundPolicy.Mode.PUBLIC_ONLY, dns { listOf(publicIp) }) { emptyList() } }
    }

    @Test fun pinsValidatedDnsAndDoesNotConsultSystemOrConfiguredProxy() {
        Fixture().use { fixture ->
            fixture.enqueue("200 OK", body = "pinned")
            var lookups = 0
            var proxyAuthCalls = 0
            val dns = dns { lookups++; if (lookups == 1) listOf(publicIp) else listOf(ip("127.0.0.1")) }
            val base = fixture.client().newBuilder()
                .dns(dns { error("base DNS must be replaced") })
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
                .proxyAuthenticator(Authenticator { _, _ -> proxyAuthCalls++; null })
                .build()
            transport(base, dns).execute(request("http://public.test/start")).use { assertEquals("pinned", it.body!!.string()) }
            assertEquals(1, lookups)
            assertEquals(0, proxyAuthCalls)
            assertEquals(listOf(publicIp.hostAddress), fixture.connectedAddresses)
            assertEquals("public.test", fixture.take().headers["host"])
        }
    }

    @Test fun privateRedirectIsRejectedBeforeASecondConnection() {
        Fixture().use { fixture ->
            fixture.enqueue("302 Found", "Location" to "http://internal.test/private")
            val dns = dns { if (it == "internal.test") listOf(privateIp) else listOf(publicIp) }
            assertDenied { transport(fixture.client(), dns).execute(request("http://public.test/")) }
            assertEquals(1, fixture.connectedAddresses.size)
        }
    }

    @Test fun sameOriginRedirectRevalidatesDnsAgainstRebinding() {
        Fixture().use { fixture ->
            fixture.enqueue("302 Found", "Location" to "/next")
            var lookups = 0
            val dns = dns { lookups++; if (lookups == 1) listOf(publicIp) else listOf(privateIp) }
            assertDenied { transport(fixture.client(), dns).execute(request("http://public.test/")) }
            assertEquals(2, lookups)
            assertEquals(1, fixture.connectedAddresses.size)
        }
    }

    @Test fun acceptsExactlyThreeRedirectsButRefusesTheFourth() {
        Fixture().use { fixture ->
            repeat(3) { fixture.enqueue("302 Found", "Location" to "/hop${it + 1}") }
            fixture.enqueue("200 OK", body = "done")
            transport(fixture.client()).execute(request("http://public.test/")).use { assertEquals("done", it.body!!.string()) }
            assertEquals(4, fixture.connectedAddresses.size)
        }
        Fixture().use { fixture ->
            repeat(4) { fixture.enqueue("302 Found", "Location" to "/hop${it + 1}") }
            assertDenied { transport(fixture.client()).execute(request("http://public.test/")) }
            assertEquals(4, fixture.connectedAddresses.size)
        }
    }

    @Test fun crossOriginClearsCustomAuthCookiesAndSensitiveHeaders() {
        Fixture().use { fixture ->
            fixture.enqueue("302 Found", "Location" to "http://other.test/next")
            fixture.enqueue("200 OK", body = "done")
            var cookieLoads = 0
            val client = fixture.client().newBuilder().cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    cookieLoads++
                    return listOf(Cookie.Builder().name("jarSecret").value("secret").domain(url.host).build())
                }
            }).build()
            val request = request("http://public.test/").newBuilder()
                .header("Authorization", "Bearer secret").header("Proxy-Authorization", "Basic secret")
                .header("Cookie", "session=secret").header("X-Api-Key", "secret").header("X-Custom-Token", "secret")
                .header("Referer", "https://origin.test/?key=secret").header("Origin", "https://origin.test")
                .header("Host", "192.168.1.1").header("Accept", "application/json").build()
            transport(client).execute(request).use { it.body!!.string() }
            val first = fixture.take()
            val second = fixture.take()
            assertEquals("Bearer secret", first.headers["authorization"])
            assertEquals("public.test", first.headers["host"])
            for (header in listOf("authorization", "proxy-authorization", "cookie", "x-api-key", "x-custom-token", "referer", "origin")) {
                assertFalse("leaked $header", second.headers.containsKey(header))
            }
            assertEquals("application/json", second.headers["accept"])
            assertEquals("other.test", second.headers["host"])
            assertEquals(0, cookieLoads)
        }
    }

    @Test fun sameOriginRetainsAuthBut307CannotReplayBodyAcrossOrigin() {
        Fixture().use { fixture ->
            fixture.enqueue("307 Temporary Redirect", "Location" to "/next")
            fixture.enqueue("200 OK")
            val request = request("http://public.test/").newBuilder().header("Authorization", "Bearer secret").post("body".toRequestBody()).build()
            transport(fixture.client()).execute(request).use { it.body!!.string() }
            fixture.take()
            val second = fixture.take()
            assertEquals("Bearer secret", second.headers["authorization"])
            assertEquals("body", second.body)
            assertTrue(second.line.startsWith("POST "))
        }
        Fixture().use { fixture ->
            fixture.enqueue("307 Temporary Redirect", "Location" to "http://other.test/")
            assertDenied { transport(fixture.client()).execute(request("http://public.test/").newBuilder().post("secret".toRequestBody()).build()) }
            assertEquals(1, fixture.connectedAddresses.size)
        }
    }

    @Test fun post302ChangesToGetAndStripsEntityHeaders() {
        Fixture().use { fixture ->
            fixture.enqueue("302 Found", "Location" to "/next")
            fixture.enqueue("200 OK")
            transport(fixture.client()).execute(request("http://public.test/").newBuilder().post("secret".toRequestBody()).build()).use { it.body!!.string() }
            fixture.take()
            val second = fixture.take()
            assertTrue(second.line.startsWith("GET "))
            assertEquals("", second.body)
            assertFalse(second.headers.containsKey("content-type"))
        }
    }

    @Test fun configuredLanRedirectGrantCannotEscapeItsOriginOrReachOwnDevice() {
        Fixture().use { fixture ->
            fixture.enqueue("302 Found", "Location" to "/next")
            fixture.enqueue("200 OK")
            transport(fixture.client(), dns { listOf(privateIp) }).execute(request("http://searx.test/"), OutboundPolicy.Mode.ALLOW_LAN).use { it.body!!.string() }
            assertEquals(2, fixture.connectedAddresses.size)
        }
        Fixture().use { fixture ->
            fixture.enqueue("302 Found", "Location" to "http://different-lan.test/")
            assertDenied { transport(fixture.client(), dns { listOf(privateIp) }).execute(request("http://searx.test/"), OutboundPolicy.Mode.ALLOW_LAN) }
            assertEquals(1, fixture.connectedAddresses.size)
        }
        Fixture().use { fixture ->
            assertDenied { OutboundPolicy.Transport(fixture.client(), dns { listOf(privateIp) }) { listOf(privateIp) }.execute(request("http://searx.test:8787/"), OutboundPolicy.Mode.ALLOW_LAN) }
            assertEquals(0, fixture.connectedAddresses.size)
        }
    }

    @Test fun pinnedDnsRejectsUnexpectedHost() {
        val target = OutboundPolicy.resolve("http://public.test/".toHttpUrl(), OutboundPolicy.Mode.PUBLIC_ONLY, dns { listOf(publicIp) }) { emptyList() }
        val pinned = OutboundPolicy.PinnedDns(target)
        assertEquals(listOf(publicIp), pinned.lookup("public.test"))
        assertTrue(runCatching { pinned.lookup("other.test") }.isFailure)
    }

    private fun resolve(literal: String, mode: OutboundPolicy.Mode = OutboundPolicy.Mode.PUBLIC_ONLY) =
        OutboundPolicy.resolve("https://policy.test/".toHttpUrl(), mode, dns { listOf(ip(literal)) }) { emptyList() }

    private fun transport(client: OkHttpClient, dns: Dns = dns { listOf(publicIp) }) =
        OutboundPolicy.Transport(client, dns) { emptyList() }

    private fun request(url: String) = Request.Builder().url(url).get().build()
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)
    private fun dns(lookup: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookup.invoke(hostname)
    }

    private fun assertDenied(block: () -> Any?) {
        try {
            block()
            throw AssertionError("Expected SSRF policy rejection")
        } catch (error: OutboundPolicy.Violation) {
            assertTrue(error.message.orEmpty().isNotBlank())
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    private data class RecordedRequest(val line: String, val headers: Map<String, String>, val body: String)

    private class Fixture : Closeable {
        private val server = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
        private val responses = LinkedBlockingQueue<String>()
        private val requests = LinkedBlockingQueue<RecordedRequest>()
        val connectedAddresses: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(isDaemon = true, name = "outbound-policy-test-server") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val line = readLine(input)
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val bytes = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) error("Unexpected end of body")
                        offset += count
                    }
                    requests.put(RecordedRequest(line, headers, bytes.toString(Charsets.UTF_8)))
                    val response = responses.poll(3, TimeUnit.SECONDS) ?: error("No fixture response")
                    socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            } catch (error: Exception) {
                if (!server.isClosed) throw error
            }
        }

        fun enqueue(status: String, vararg headers: Pair<String, String>, body: String = "") {
            responses.put(buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Content-Length: ").append(body.toByteArray().size).append("\r\nConnection: close\r\n\r\n").append(body)
            })
        }

        fun take(): RecordedRequest {
            val request = requests.poll(3, TimeUnit.SECONDS)
            assertNotNull("Expected HTTP request", request)
            return request!!
        }

        fun client(): OkHttpClient = OkHttpClient.Builder().socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val address = endpoint as InetSocketAddress
                    connectedAddresses.add(address.address.hostAddress)
                    // Test-only transparent tunnel: production still connects to the pinned public/LAN IP.
                    super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), timeout)
                }
            }
            override fun createSocket(host: String, port: Int): Socket = error("Unexpected socket overload")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket overload")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected socket overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket overload")
        }).retryOnConnectionFailure(false).build()

        override fun close() {
            server.close()
            worker.join(1_000)
        }

        private fun readLine(input: BufferedInputStream): String {
            val result = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) error("Unexpected end of HTTP headers")
                if (value == 10) return result.toString().removeSuffix("\r")
                result.append(value.toChar())
            }
        }
    }
}
