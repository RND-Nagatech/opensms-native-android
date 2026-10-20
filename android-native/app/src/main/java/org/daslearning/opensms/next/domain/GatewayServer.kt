package org.daslearning.opensms.next.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GatewayServerStatus { STOPPED, STARTING, RUNNING, DEGRADED, STOPPING, ERROR }
enum class GatewayNetworkMode { LOOPBACK, LAN, UNAVAILABLE }

data class GatewaySnapshot(
    val server: GatewayServerStatus = GatewayServerStatus.STOPPED,
    val network: GatewayNetworkMode = GatewayNetworkMode.UNAVAILABLE,
    val errorCode: String? = null,
    val address: String? = null,
    val port: Int? = null,
)

fun GatewaySnapshot.localNetworkEndpoint(): String? =
    if (server == GatewayServerStatus.RUNNING && network == GatewayNetworkMode.LAN && address != null && port != null) {
        "http://$address:$port"
    } else {
        null
    }

interface GatewayServer {
    suspend fun start(host: String, port: Int)
    suspend fun stop()
}

interface GatewayStatusSource {
    val snapshot: StateFlow<GatewaySnapshot>
}

class GatewayRuntimeState : GatewayStatusSource {
    private val mutableSnapshot = MutableStateFlow(GatewaySnapshot())
    override val snapshot: StateFlow<GatewaySnapshot> = mutableSnapshot

    fun update(snapshot: GatewaySnapshot) {
        mutableSnapshot.value = snapshot
    }
}

interface TrustedNetworkPolicy {
    fun allowLanListener(): Boolean
    fun localWifiAddress(): String?
    fun isAllowedPeer(address: String): Boolean
}
