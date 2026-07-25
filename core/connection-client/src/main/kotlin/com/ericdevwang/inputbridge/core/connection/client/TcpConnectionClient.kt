package com.ericdevwang.inputbridge.core.connection.client

import com.ericdevwang.inputbridge.core.connection.client.internal.TcpClientConnection
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class TcpConnectionClient(
    private val config: TcpConnectionClientConfig = TcpConnectionClientConfig(),
) : ClientConnectionFactory {
    @Throws(IOException::class)
    override fun connect(listener: ClientConnectionListener): ClientConnection {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMillis)
        } catch (cause: IOException) {
            runCatching { socket.close() }
            throw cause
        }

        return TcpClientConnection(socket, listener).also { it.start() }
    }
}
