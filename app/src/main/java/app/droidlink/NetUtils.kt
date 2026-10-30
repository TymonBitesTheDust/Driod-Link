package app.droidlink

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetUtils {
    // Cellular, VPN and virtual interfaces: the other phone can't reach these addresses.
    private val skippedPrefixes = listOf("rmnet", "ccmni", "dummy", "tun", "v4-", "clat", "lo", "ppp", "ip6", "ifb")

    /** Private IPv4 addresses of this phone on Wi-Fi or its hotspot, Wi-Fi first. */
    fun localAddresses(): List<String> {
        val wifi = mutableListOf<String>()
        val others = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in Collections.list(interfaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: ""
                if (skippedPrefixes.any { name.startsWith(it) }) continue
                for (address in Collections.list(ni.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress || !address.isSiteLocalAddress) continue
                    val ip = address.hostAddress ?: continue
                    if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap")) wifi.add(ip) else others.add(ip)
                }
            }
        } catch (e: Exception) {
            // fall through with whatever was collected
        }
        return (wifi + others).distinct()
    }
}
