package org.daslearning.opensms.next.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import org.daslearning.opensms.next.data.AppPreferences
import org.daslearning.opensms.next.domain.TrustedNetworkPolicy

@Singleton
class AndroidTrustedNetworkPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) : TrustedNetworkPolicy {
    override fun allowLanListener(): Boolean = preferences.trustedLanCidr.value
        ?.let(::parsePrivateIpv4Cidrs) != null && localWifiAddress() != null

    override fun localWifiAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return manager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }

    override fun isAllowedPeer(address: String): Boolean {
        val cidrs = preferences.trustedLanCidr.value?.let(::parsePrivateIpv4Cidrs) ?: return false
        val candidate = runCatching { InetAddress.getByName(address) }.getOrNull() as? Inet4Address ?: return false
        return cidrs.any { it.contains(candidate.address) }
    }

    private fun parsePrivateIpv4Cidrs(value: String): List<Ipv4Cidr>? {
        val entries = value.split(',')
        if (entries.isEmpty()) return null
        return entries.map { entry -> parsePrivateIpv4Cidr(entry.trim()) ?: return null }
    }

    private fun parsePrivateIpv4Cidr(value: String): Ipv4Cidr? {
        val parts = value.split('/')
        if (parts.size != 2) return null
        if (!IPV4.matches(parts[0])) return null
        val address = runCatching { InetAddress.getByName(parts[0]) }.getOrNull() as? Inet4Address ?: return null
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 8..30 } ?: return null
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val isPrivateRange = first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
        if (!isPrivateRange) return null
        return Ipv4Cidr(bytes, prefix)
    }

    private data class Ipv4Cidr(val network: ByteArray, val prefix: Int) {
        fun contains(candidate: ByteArray): Boolean {
            for (bit in 0 until prefix) {
                val mask = 1 shl (7 - bit % 8)
                if ((network[bit / 8].toInt() and mask) != (candidate[bit / 8].toInt() and mask)) return false
            }
            return true
        }
    }

    private companion object {
        val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    }
}
