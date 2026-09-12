package cn.luoluna.sweven.ai.server

import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS 广播：把网关以固定主机名发布到局域网，客户端直接填 flux.local 而不必记 IP。
 * - 默认广播 flux.local；设置了前缀标签则广播 <前缀>.flux.local（替换默认）
 * - 同时注册 _http._tcp 服务，dns-sd / Avahi 等浏览工具可发现
 * - 同名主机被占用时 jmDNS 探测冲突会自动改名（如 flux-2），实际广播名经 ServerState.mdnsHost 暴露
 *
 * JmDNS 的 create/register/close 都是阻塞网络操作，全部串行到单线程执行；
 * 网络切换等重复触发靠签名（IP|主机名|端口）去重。
 */
object MdnsAdvertiser {

    private const val SERVICE_TYPE = "_http._tcp.local."
    private const val INSTANCE_NAME = "Flux"

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Flux:Mdns").apply { isDaemon = true }
    }

    /** 以下两个字段仅在 executor 线程访问 */
    private var current: JmDNS? = null
    private var currentSig: String? = null

    fun start(label: String?, port: Int) {
        executor.execute { advertise(label, port) }
    }

    fun stop() {
        executor.execute {
            closeCurrent()
            currentSig = null
            ServerState.mdnsHost.value = null
        }
    }

    private fun advertise(label: String?, port: Int) {
        val ip = NetUtil.lanIps().firstOrNull()
        val host = label?.takeIf { it.isNotBlank() }?.let { "$it.flux" } ?: "flux"
        val target = if (ip != null && port > 0) "$ip|$host|$port" else null
        if (target != null && target == currentSig) return
        closeCurrent()
        currentSig = target
        if (target == null) {
            ServerState.mdnsHost.value = null
            return
        }
        var jmd: JmDNS? = null
        try {
            jmd = JmDNS.create(InetAddress.getByName(ip), host)
            jmd.registerService(
                ServiceInfo.create(
                    SERVICE_TYPE,
                    INSTANCE_NAME,
                    port,
                    0,
                    0,
                    mapOf("path" to "/v1", "api" to "openai"),
                ),
            )
            current = jmd
            updateHost(jmd)
            // 冲突改名发生在探测阶段（约 1~2s 后），延迟刷新实际广播名
            scheduleRefresh(jmd, 2)
            scheduleRefresh(jmd, 5)
        } catch (_: Exception) {
            jmd?.let { runCatching { it.close() } }
            currentSig = null
            ServerState.mdnsHost.value = null
        }
    }

    private fun updateHost(jmd: JmDNS) {
        ServerState.mdnsHost.value = jmd.hostName?.removeSuffix(".")
    }

    private fun scheduleRefresh(jmd: JmDNS, delaySeconds: Long) {
        executor.schedule({
            if (current === jmd) updateHost(jmd)
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun closeCurrent() {
        current?.let { jmd ->
            runCatching {
                jmd.unregisterAllServices()
                jmd.close()
            }
        }
        current = null
    }
}
