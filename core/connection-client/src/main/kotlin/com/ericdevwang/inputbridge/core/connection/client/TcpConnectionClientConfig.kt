package com.ericdevwang.inputbridge.core.connection.client

data class TcpConnectionClientConfig(
    val sharedSecret: String,
    val host: String = "127.0.0.1",
    val port: Int = 18080,
    val connectTimeoutMillis: Int = 2_000,
    val handshakeTimeoutMillis: Int = 2_000,
)
