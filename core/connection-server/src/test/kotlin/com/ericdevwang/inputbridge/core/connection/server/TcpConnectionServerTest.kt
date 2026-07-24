package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameReader
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.Ping
import com.ericdevwang.inputbridge.protocol.Pong
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpConnectionServerTest {
    @Test
    fun sendsPingAndAcceptsMatchingPong() {
        val port = ServerSocket(0).use { it.localPort }
        val server = TcpConnectionServer(
            TcpConnectionServerConfig(
                port = port,
                heartbeatIntervalMillis = 20L,
                heartbeatTimeoutMillis = 200L,
            ),
            object : ConnectionServerListener {
                override fun onClientConnected(connection: ServerConnection) = Unit
            },
        )
        server.start()

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 1_000
            val reader = LengthPrefixedFrameReader(socket.getInputStream())
            val writer = LengthPrefixedFrameWriter(socket.getOutputStream())
            val ping = reader.readMessage() as Ping
            writer.writeMessage(Pong(ping.requestId))
            assertTrue(socket.isConnected)
        }
        server.stop()
    }
}

private fun LengthPrefixedFrameWriter.writeMessage(message: BridgeMessage) {
    write(
        ProtocolJson.default
            .encodeToString(BridgeMessage.serializer(), message)
            .toByteArray(StandardCharsets.UTF_8),
    )
}

private fun LengthPrefixedFrameReader.readMessage(): BridgeMessage {
    val frame = read() ?: error("Expected a protocol message")
    return ProtocolJson.default.decodeFromString(frame.toString(StandardCharsets.UTF_8))
}
