package com.ericdevwang.inputbridge.server

import com.ericdevwang.inputbridge.core.connection.client.ClientConnection
import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionListener
import com.ericdevwang.inputbridge.core.connection.client.TcpConnectionClient
import com.ericdevwang.inputbridge.core.connection.client.TcpConnectionClientConfig
import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import com.ericdevwang.inputbridge.core.data.repository.PersistenceResult
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.protocol.BridgeError
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.ClearCommand
import com.ericdevwang.inputbridge.protocol.ClearSucceeded
import com.ericdevwang.inputbridge.protocol.HelloAck
import com.ericdevwang.inputbridge.protocol.HelloCommand
import com.ericdevwang.inputbridge.protocol.ProtocolConstants
import com.ericdevwang.inputbridge.protocol.TextSnapshot
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
            config = InputBridgeServerConfig(port = port, sharedSecret = TEST_SECRET),
        )
        server.start()

        TestClient(port).use { client ->
            client.send(HelloCommand(ProtocolConstants.CURRENT_VERSION, "hello-1"))
            assertEquals(
                HelloAck("ok", "1.0.1", ProtocolConstants.CURRENT_VERSION, 100L, "hello-1"),
                client.nextMessage(),
            )
            assertEquals(TextSnapshot("中文\n😀", 7L, 123L), client.nextMessage())

            client.send(ClearCommand(7L, "clear-1"))
            assertEquals(ClearSucceeded(7L, 8L, "clear-1"), client.nextMessage())
        }

        server.stop()
    }

    @Test
    fun secondClientReceivesServerBusyWhileFirstClientRemainsConnected() {
        val port = freePort()
        val server = InputBridgeServer(
            FakeTextRepository(TextState.initial(0L)),
            "1.0.1",
            config = InputBridgeServerConfig(port = port, sharedSecret = TEST_SECRET),
        )
        server.start()
        TestClient(port).use { first ->
            TestClient(port).use { second ->
                assertEquals(
                    BridgeError("SERVER_BUSY", "The server already has an active client."),
                    second.nextMessage(),
                )
                assertTrue(first.isOpen)
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
            config = InputBridgeServerConfig(port = port, sharedSecret = TEST_SECRET),
        )
        server.start()

        TestClient(port).use { client ->
            client.send(ClearCommand(0L, "clear-1"))
            assertEquals(
                BridgeError(
                    code = "INVALID_HANDSHAKE",
                    message = "The first TCP message must be hello.",
                ),
                client.nextMessage(),
            )
        }
        server.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val TEST_SECRET = "app-test-shared-secret"
    }
}

private class TestClient(port: Int) : AutoCloseable {
    private val incoming = LinkedBlockingQueue<BridgeMessage>()
    private val closed = AtomicBoolean(false)
    private val connection: ClientConnection = TcpConnectionClient(
        TcpConnectionClientConfig(sharedSecret = "app-test-shared-secret", port = port),
    ).connect(object : ClientConnectionListener {
        override fun onMessage(message: BridgeMessage) {
            incoming.offer(message)
        }

        override fun onClosed(cause: Throwable?) {
            closed.set(true)
        }
    })

    val isOpen: Boolean
        get() = !closed.get()

    fun send(message: BridgeMessage) {
        check(connection.send(message)) { "Could not send test message." }
    }

    fun nextMessage(): BridgeMessage = incoming.poll(2, TimeUnit.SECONDS)
        ?: error("Expected a protocol message")

    override fun close() {
        connection.close()
    }
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
