package org.daslearning.opensms.next.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySnapshotTest {
    @Test
    fun endpointIsShownOnlyForRunningLanGateway() {
        val lan = GatewaySnapshot(
            server = GatewayServerStatus.RUNNING,
            network = GatewayNetworkMode.LAN,
            address = "192.168.1.24",
            port = 9292,
        )
        assertEquals("http://192.168.1.24:9292", lan.localNetworkEndpoint())
        assertNull(lan.copy(network = GatewayNetworkMode.LOOPBACK).localNetworkEndpoint())
        assertNull(lan.copy(server = GatewayServerStatus.STOPPED).localNetworkEndpoint())
    }
}
