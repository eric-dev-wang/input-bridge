package com.ericdevwang.inputbridge.plugin.connection

import java.time.Duration

object BridgeNetworkConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 18080
    const val ADB_TIMEOUT_SECONDS = 5L
    const val TCP_CONNECT_TIMEOUT_SECONDS = 2L
    const val REQUEST_TIMEOUT_SECONDS = 4L
    const val TCP_CONNECT_TIMEOUT_MILLIS = TCP_CONNECT_TIMEOUT_SECONDS * 1_000L

    val adbTimeout: Duration = Duration.ofSeconds(ADB_TIMEOUT_SECONDS)
    val requestTimeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)
}
