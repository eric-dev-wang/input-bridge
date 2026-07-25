package com.ericdevwang.inputbridge.core.connection.server

data class TcpConnectionServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 18080,
    val heartbeatIntervalMillis: Long = 15_000,
    val heartbeatTimeoutMillis: Long = 30_000,
)
