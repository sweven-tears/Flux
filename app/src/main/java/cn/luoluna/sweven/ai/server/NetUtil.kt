package cn.luoluna.sweven.ai.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /** 本机局域网 IPv4 列表（wlan 优先），供局域网客户端填写 */
    fun lanIps(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in interfaces.asSequence()) {
                if (!ni.isUp || ni.isLoopback) continue
                val isWifi = ni.name?.startsWith("wlan") == true
                for (addr in ni.inetAddresses.asSequence()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isWifi) result.add(0, ip) else result.add(ip)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.distinct()
    }
}
