package cn.luoluna.sweven.ai.net

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Policy for tool-controlled HTTP, deliberately separate from user-configured model providers. */
object OutboundPolicy {
    enum class Mode { PUBLIC_ONLY, ALLOW_LAN }

    /** Messages contain no URL, query, credentials, or resolver/server error text. */
    class Violation(message: String) : IOException(message)

    internal data class Target(val url: HttpUrl, val addresses: List<InetAddress>)

    internal fun resolve(
        url: HttpUrl,
        mode: Mode,
        dns: Dns,
        localAddresses: () -> List<InetAddress>,
    ): Target {
        if (url.scheme != "http" && url.scheme != "https") deny("只允许 http/https")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) deny("不允许 URL 内嵌认证信息")
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost")) deny("不允许访问本机回环地址")
        if (host == "flux.local" || host.endsWith(".flux.local")) deny("不允许访问本 App 网关")
        if ('%' in host) deny("不允许带区域标识的 IPv6 地址")
        // Reject legacy decimal/octal/hex spellings before platform-specific resolver parsing.
        if (host.all { it.isDigit() || it == '.' }) {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || (it.length > 1 && it[0] == '0') || it.toIntOrNull() !in 0..255 }) {
                deny("不允许非标准 IP 地址格式")
            }
        }
        if (host.split('.').any { it.startsWith("0x", ignoreCase = true) && it.drop(2).all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }) {
            deny("不允许十六进制 IP 地址格式")
        }

        val addresses = try {
            dns.lookup(url.host).toList()
        } catch (_: Exception) {
            deny("域名无法解析")
        }
        if (addresses.isEmpty()) deny("域名无解析结果")
        val ownAddresses = try {
            localAddresses().map { it.address.toList() }.toSet()
        } catch (_: Exception) {
            deny("无法校验本机网络地址")
        }
        // Reject mixed public/private answers rather than letting route fallback choose a private IP.
        addresses.forEach { address ->
            checkAddress(address, mode)
            if (address.address.toList() in ownAddresses) deny("不允许访问本 App 所在设备（防止网关递归）")
        }
        // Numeric copies cannot trigger another name lookup, including when retrying another route.
        return Target(url, addresses.map { InetAddress.getByAddress(it.address) })
    }

    private fun checkAddress(address: InetAddress, mode: Mode) {
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address.isAnyLocalAddress) deny("不允许访问通配地址")
        if (address.isLoopbackAddress) deny("不允许访问本机回环地址")
        if (address.isLinkLocalAddress) deny("不允许访问链路本地/元数据地址")
        if (address.isMulticastAddress) deny("不允许访问组播地址")
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            if (a == 0 || a >= 224) deny("不允许访问保留地址")
            if (a == 100 && b in 64..127) deny("不允许访问 CGNAT/元数据地址")
            if (bytes == listOf(168, 63, 129, 16)) deny("不允许访问云平台内部地址")
            if ((a == 192 && b == 0 && c in listOf(0, 2)) ||
                (a == 192 && b == 88 && c == 99) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)
            ) deny("不允许访问特殊用途地址")
            val privateLan = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
            if (privateLan && mode == Mode.PUBLIC_ONLY) deny("不允许访问内网地址")
            return
        }
        if (bytes.size != 16) deny("不支持的 IP 地址")
        // AWS IMDS IPv6 is a ULA: it must remain denied even for a configured LAN search engine.
        if (bytes == listOf(0xfd, 0, 0x0e, 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54)) {
            deny("不允许访问云元数据地址")
        }
        if (bytes[0] and 0xfe == 0xfc) {
            if (mode == Mode.PUBLIC_ONLY) deny("不允许访问 IPv6 内网地址")
            return
        }
        // Only native global unicast IPv6 is public. This excludes mapped/compatible IPv4,
        // NAT64 (including local-use prefixes), site-local, discard, and other special ranges.
        if (bytes[0] and 0xe0 != 0x20) deny("不允许访问特殊用途 IPv6 地址")
        val teredoOrSpecial = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 2
        val documentation = bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8) ||
            (bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] and 0xf0 == 0)
        val isatap = bytes[8] in listOf(0, 2) && bytes[9] == 0 && bytes[10] == 0x5e && bytes[11] == 0xfe
        if (bytes.take(2) == listOf(0x20, 0x02) || teredoOrSpecial || documentation || isatap) {
            deny("不允许访问 IPv6 转换/特殊用途地址")
        }
    }

    internal class PinnedDns(private val target: Target) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!hostname.equals(target.url.host, ignoreCase = true)) throw UnknownHostException("未校验的目标主机")
            return target.addresses
        }
    }

    /**
     * Every hop resolves once, validates *all* answers, and pins the actual direct connection.
     * ALLOW_LAN applies only to the original origin; redirects cannot grant another origin LAN access.
     * A fresh non-reusable connection pool prevents HTTP/2 coalescing or a previous route bypass.
     * Resolver/socket injection supports real JVM regression tests without contacting the Internet.
     */
    internal class Transport(
        private val baseClient: OkHttpClient,
        private val dns: Dns = Dns.SYSTEM,
        private val localAddresses: () -> List<InetAddress> = ::interfaceAddresses,
    ) {
        fun execute(request: Request, mode: Mode = Mode.PUBLIC_ONLY, timeoutMillis: Long = 30_000): Response {
            val initialOrigin = request.url
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceIn(1, 60_000))
            var next = request
            var redirects = 0
            while (true) {
                val hopMode = if (mode == Mode.ALLOW_LAN && sameOrigin(initialOrigin, next.url)) mode else Mode.PUBLIC_ONLY
                val target = resolve(next.url, hopMode, dns, localAddresses)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw InterruptedIOException("请求超时")
                val client = baseClient.newBuilder()
                    .dns(PinnedDns(target))
                    .proxy(Proxy.NO_PROXY)
                    .proxyAuthenticator(Authenticator.NONE)
                    .authenticator(Authenticator.NONE)
                    .cookieJar(CookieJar.NO_COOKIES)
                    .cache(null)
                    .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(remaining, TimeUnit.NANOSECONDS)
                    .build()
                val response = client.newCall(next.newBuilder()
                    .removeHeader("Host")
                    .removeHeader("Proxy-Authorization")
                    .removeHeader("Proxy-Connection")
                    .header("Connection", "close")
                    .build()).execute()
                if (response.code !in REDIRECT_CODES || response.header("Location") == null) return response
                next = response.use {
                    if (redirects >= MAX_REDIRECTS) deny("重定向超过 3 跳限制")
                    val redirectedUrl = next.url.resolve(response.header("Location")!!)
                        ?: deny("重定向地址不合法")
                    redirectedRequest(next, redirectedUrl, response.code)
                }
                redirects++
            }
        }
    }

    private fun redirectedRequest(request: Request, url: HttpUrl, status: Int): Request {
        val crossOrigin = !sameOrigin(request.url, url)
        val switchToGet = (status == 303 && request.method != "HEAD") ||
            (status in listOf(301, 302) && request.method == "POST")
        val body = if (switchToGet) null else request.body
        // A body may contain credentials just like headers. Never replay one to another origin.
        if (crossOrigin && body != null) deny("不允许跨源重定向转发请求体")
        if (body != null && (body.isOneShot() || body.isDuplex())) deny("重定向无法安全重放请求体")
        val builder = request.newBuilder().url(url)
        if (switchToGet) {
            builder.method("GET", null)
            builder.removeHeader("Content-Type").removeHeader("Content-Length").removeHeader("Transfer-Encoding")
        }
        if (crossOrigin) {
            // An allowlist also catches user-defined X-Token/X-Api-Key and future auth headers.
            request.headers.names().filter { it.lowercase(Locale.ROOT) !in SAFE_REDIRECT_HEADERS }
                .forEach(builder::removeHeader)
        }
        return builder.build()
    }

    internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun interfaceAddresses(): List<InetAddress> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: deny("无法校验本机网络地址")
        return interfaces.toList().flatMap { it.inetAddresses.toList() }
            .also { if (it.isEmpty()) deny("无法校验本机网络地址") }
    }

    private fun deny(message: String): Nothing = throw Violation(message)

    private const val MAX_REDIRECTS = 3
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val SAFE_REDIRECT_HEADERS = setOf("accept", "accept-language", "user-agent", "accept-encoding", "range")
}
