package com.wyspr.feature.onboarding.share

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Find the device's LAN IPv4 address — the one the Invitee's phone can
 * reach from the same WiFi network. Preference order:
 *   1. RFC 1918 private addresses (192.168.x, 10.x, 172.16-31.x) on a
 *      non-loopback, up interface. These are the typical home/café
 *      router subnets.
 *   2. Any other non-loopback IPv4 — covers WiFi Direct's 192.168.49.x
 *      and corporate networks using unusual ranges.
 *
 * Tailscale (100.x), VPN tunnels, and link-local (169.254.x) are
 * deprioritised because the Invitee's phone cannot route to them
 * without first joining the same overlay network.
 *
 * Returns null if no suitable interface is up — caller surfaces a
 * "connect to WiFi first" hint to the user.
 */
internal object LocalIp {

    fun find(): String? {
        val candidates = collectIpv4()
        return candidates.firstOrNull { isPrivate(it) }
            ?: candidates.firstOrNull { !isLinkLocal(it) && !isCgnat(it) }
            ?: candidates.firstOrNull()
    }

    private fun collectIpv4(): List<String> {
        val out = ArrayList<String>(4)
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }
            .getOrNull() ?: return emptyList()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            if (runCatching { iface.isLoopback || !iface.isUp || iface.isVirtual }.getOrDefault(true)) continue
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress) continue
                if (addr is Inet4Address) {
                    val host = addr.hostAddress ?: continue
                    out.add(host)
                }
            }
        }
        return out
    }

    private fun isPrivate(host: String): Boolean {
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    private fun isLinkLocal(host: String): Boolean = host.startsWith("169.254.")

    /** Tailscale and CGNAT operate inside 100.64.0.0/10. */
    private fun isCgnat(host: String): Boolean {
        if (!host.startsWith("100.")) return false
        val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 64..127
    }
}
