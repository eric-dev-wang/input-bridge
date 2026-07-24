package com.ericdevwang.inputbridge.plugin.connection

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeNetworkConfigTest {
    @Test
    fun usesFixedBridgePortAndTimeouts() {
        assertEquals(18080, BridgeNetworkConfig.PORT)
        assertEquals(Duration.ofSeconds(2), Duration.ofSeconds(BridgeNetworkConfig.TCP_CONNECT_TIMEOUT_SECONDS))
        assertEquals(Duration.ofSeconds(4), BridgeNetworkConfig.requestTimeout)
        assertEquals(Duration.ofSeconds(5), BridgeNetworkConfig.adbTimeout)
    }
}
