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

    /**
     * This phone's Tailscale address, or null when Tailscale isn't on. Tailscale gives each device
     * an address in 100.64.0.0/10 on its VPN interface, and any phone signed in to the same
     * Tailscale network can reach it from anywhere, over Wi-Fi or mobile data.
     */
    fun tailscaleAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in Collections.list(interfaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (address in Collections.list(ni.inetAddresses)) {
                    if (address is Inet4Address && isTailscale(address.address)) return address.hostAddress
                }
            }
        } catch (e: Exception) {
            // no address to show
        }
        return null
    }

    /** 100.64.0.0/10: the first byte is 100 and the second is 64 to 127. */
    internal fun isTailscale(ip: ByteArray): Boolean =
        ip.size == 4 && (ip[0].toInt() and 0xFF) == 100 && (ip[1].toInt() and 0xC0) == 64
}
