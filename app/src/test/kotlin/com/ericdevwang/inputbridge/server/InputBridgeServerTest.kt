package com.ericdevwang.inputbridge.server

import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import com.ericdevwang.inputbridge.core.data.repository.PersistenceResult
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameReader
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.protocol.BridgeError
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.ClearCommand
import com.ericdevwang.inputbridge.protocol.ClearSucceeded
import com.ericdevwang.inputbridge.protocol.HelloAck
import com.ericdevwang.inputbridge.protocol.HelloCommand
import com.ericdevwang.inputbridge.protocol.ProtocolConstants
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import com.ericdevwang.inputbridge.protocol.TextSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputBridgeServerTest {
    @Test
    fun tcpHandshakeReturnsInitialSnapshotAndClearResult() {
        val repository = FakeTextRepository(TextState("中文\n😀", 7L, 123L))
        val port = freePort()
        val server = InputBridgeServer(
            repository = repository,
            appVersion = "1.0.1",
            clock = { 100L },
            config = InputBridgeServerConfig(port = port),
        )
        server.start()

        Socket(DEFAULT_SERVER_HOST, port).use { socket ->
            val reader = LengthPrefixedFrameReader(socket.getInputStream())
            val writer = LengthPrefixedFrameWriter(socket.getOutputStream())
            writer.writeMessage(HelloCommand(ProtocolConstants.CURRENT_VERSION, "hello-1"))

            assertEquals(
                HelloAck("ok", "1.0.1", ProtocolConstants.CURRENT_VERSION, 100L, "hello-1"),
                reader.readMessage(),
            )
            assertEquals(TextSnapshot("中文\n😀", 7L, 123L), reader.readMessage())

            writer.writeMessage(ClearCommand(7L, "clear-1"))
            assertEquals(ClearSucceeded(7L, 8L, "clear-1"), reader.readMessage())
        }

        server.stop()
    }

    @Test
    fun secondClientReceivesServerBusyWhileFirstClientRemainsConnected() {
        val port = freePort()
        val server = InputBridgeServer(
            FakeTextRepository(TextState.initial(0L)),
            "1.0.1",
            config = InputBridgeServerConfig(port = port),
        )
        server.start()
        Socket(DEFAULT_SERVER_HOST, port).use { first ->
            Socket(DEFAULT_SERVER_HOST, port).use { second ->
                val message = LengthPrefixedFrameReader(second.getInputStream()).readMessage()
                assertEquals(BridgeError("SERVER_BUSY", "The server already has an active client."), message)
                assertTrue(first.isConnected && !first.isClosed)
            }
        }
        server.stop()
    }

    @Test
    fun invalidHandshakeIsRejectedWithProtocolError() {
        val port = freePort()
        val server = InputBridgeServer(
            FakeTextRepository(TextState.initial(0L)),
            "1.0.1",
            config = InputBridgeServerConfig(port = port),
        )
        server.start()

        Socket(DEFAULT_SERVER_HOST, port).use { socket ->
            val reader = LengthPrefixedFrameReader(socket.getInputStream())
            val writer = LengthPrefixedFrameWriter(socket.getOutputStream())
            writer.writeMessage(ClearCommand(0L, "clear-1"))
            assertEquals(
                BridgeError(
                    code = "INVALID_HANDSHAKE",
                    message = "The first TCP message must be hello.",
                ),
                reader.readMessage(),
            )
        }
        server.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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

private class FakeTextRepository(initial: TextState) : TextRepository {
    private val stateFlow = MutableStateFlow(initial)
    override val state: Flow<TextState> = stateFlow

    override suspend fun save(state: TextState): PersistenceResult {
        stateFlow.value = state
        return PersistenceResult.Succeeded(state.version)
    }

    override suspend fun clear(expectedVersion: Long): ClearResult =
        if (expectedVersion != stateFlow.value.version) {
            ClearResult.VersionConflict(stateFlow.value.version)
        } else {
            val cleared = stateFlow.value.clear(stateFlow.value.updatedAt + 1L)
            stateFlow.value = cleared
            ClearResult.Cleared(expectedVersion, cleared.version)
        }
}
