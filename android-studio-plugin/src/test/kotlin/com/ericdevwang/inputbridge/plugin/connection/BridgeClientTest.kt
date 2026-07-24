package com.ericdevwang.inputbridge.plugin.connection

import com.ericdevwang.inputbridge.core.connection.client.ClientConnection
import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionFactory
import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionListener
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.ClearCommand
import com.ericdevwang.inputbridge.protocol.ClearSucceeded
import com.ericdevwang.inputbridge.protocol.HelloAck
import com.ericdevwang.inputbridge.protocol.HelloCommand
import com.ericdevwang.inputbridge.protocol.ProtocolConstants
import com.ericdevwang.inputbridge.protocol.TextChanged
import com.ericdevwang.inputbridge.protocol.TextSnapshot
import com.ericdevwang.inputbridge.protocol.VersionConflict
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeClientTest {
    @Test
    fun connectSendsHelloAndReturnsInitialSnapshot() {
        val transport = RecordingConnectionFactory()
        transport.onSend = { message, session ->
            if (message is HelloCommand) {
                session.emit(
                    HelloAck(
                        status = "ok",
                        appVersion = "1.0.1",
                        protocolVersion = ProtocolConstants.CURRENT_VERSION,
                        serverTime = 100L,
                        requestId = message.requestId,
                    ),
                )
                session.emit(TextSnapshot("你好\n😀", 7L, 99L))
            }
        }
        val client = JdkBridgeClient(
            connectionClient = transport,
            requestTimeout = Duration.ofMillis(100),
            requestIdFactory = RequestIds(),
        )

        val result = client.connect(object : BridgeClientEventListener {})

        assertEquals(
            BridgeClientResult.Success(TextSnapshot("你好\n😀", 7L, 99L)),
            result,
        )
        assertTrue(transport.sentMessages.first() is HelloCommand)
    }

    @Test
    fun pushedTextChangedIsDeliveredToListener() {
        val transport = RecordingConnectionFactory()
        val listener = RecordingClientEventListener()
        val client = connectedClient(transport, listener)

        transport.session.emit(TextChanged("updated", 8L, 101L))

        assertEquals(listOf(TextChanged("updated", 8L, 101L)), listener.textChanges)
        client.close()
    }

    @Test
    fun snapshotResponseMustMatchItsRequestId() {
        val transport = RecordingConnectionFactory()
        val client = connectedClient(transport, RecordingClientEventListener())
        transport.onSend = { message, session ->
            if (message is com.ericdevwang.inputbridge.protocol.GetSnapshotCommand) {
                session.emit(TextSnapshot("updated", 8L, 101L, requestId = message.requestId))
            }
        }

        assertEquals(
            BridgeClientResult.Success(TextSnapshot("updated", 8L, 101L, requestId = "request-1")),
            client.getSnapshot(),
        )
    }

    @Test
    fun mismatchedSnapshotResponseFailsWithInvalidResponse() {
        val transport = RecordingConnectionFactory()
        val client = connectedClient(transport, RecordingClientEventListener())
        transport.onSend = { message, session ->
            if (message is com.ericdevwang.inputbridge.protocol.GetSnapshotCommand) {
                session.emit(TextSnapshot("updated", 8L, 101L, requestId = "wrong-request"))
            }
        }

        assertEquals("INVALID_RESPONSE", (client.getSnapshot() as BridgeClientResult.Failure).code)
    }

    @Test
    fun connectionLifecycleEventsAreForwardedToListener() {
        val transport = RecordingConnectionFactory()
        val listener = RecordingClientEventListener()
        val client = connectedClient(transport, listener)
        val closeCause = IllegalStateException("closed")
        val errorCause = IllegalStateException("failed")

        transport.session.emitClosed(closeCause)
        transport.session.emitError(errorCause)

        assertEquals(listOf(closeCause), listener.closedCauses)
        assertEquals(listOf(errorCause), listener.errors)
        client.close()
    }

    @Test
    fun closeClosesConnection() {
        val transport = RecordingConnectionFactory()
        val client = connectedClient(transport, RecordingClientEventListener())

        client.close()

        assertEquals(1, transport.session.closeCalls)
    }

    @Test
    fun clearReturnsVersionConflictWithoutTreatingItAsTransportFailure() {
        val transport = RecordingConnectionFactory()
        transport.onSend = { message, session ->
            if (message is ClearCommand) {
                session.emit(VersionConflict(currentVersion = 8L, requestId = message.requestId))
            }
        }
        val client = connectedClient(transport, RecordingClientEventListener())

        val result = client.clearText(expectedVersion = 7L)

        assertEquals(
            BridgeClientResult.Success(BridgeClearResult.VersionConflict(currentVersion = 8L)),
            result,
        )
    }

    @Test
    fun clearSuccessPreservesResponseVersions() {
        val transport = RecordingConnectionFactory()
        transport.onSend = { message, session ->
            if (message is ClearCommand) {
                session.emit(
                    ClearSucceeded(
                        clearedVersion = message.expectedVersion,
                        newVersion = message.expectedVersion + 1L,
                        requestId = message.requestId,
                    ),
                )
            }
        }
        val client = connectedClient(transport, RecordingClientEventListener())

        assertEquals(
            BridgeClientResult.Success(
                BridgeClearResult.Cleared(clearedVersion = 7L, newVersion = 8L),
            ),
            client.clearText(expectedVersion = 7L),
        )
    }

    @Test
    fun connectRejectsUnsupportedProtocolVersion() {
        val transport = RecordingConnectionFactory()
        transport.onSend = { message, session ->
            if (message is HelloCommand) {
                session.emit(
                    HelloAck(
                        status = "ok",
                        appVersion = "1.0.1",
                        protocolVersion = ProtocolConstants.CURRENT_VERSION + 1,
                        serverTime = 100L,
                        requestId = message.requestId,
                    ),
                )
            }
        }
        val result = JdkBridgeClient(
            connectionClient = transport,
            requestTimeout = Duration.ofMillis(100),
            requestIdFactory = RequestIds(),
        ).connect(object : BridgeClientEventListener {})

        assertEquals("UNSUPPORTED_PROTOCOL_VERSION", (result as BridgeClientResult.Failure).code)
    }

    @Test
    fun requestTimeoutReturnsBoundedFailure() {
        val transport = RecordingConnectionFactory()
        val result = JdkBridgeClient(
            connectionClient = transport,
            requestTimeout = Duration.ofMillis(20),
            requestIdFactory = RequestIds(),
        ).connect(object : BridgeClientEventListener {})

        assertEquals("REQUEST_TIMEOUT", (result as BridgeClientResult.Failure).code)
    }

    @Test
    fun clearRejectsNegativeExpectedVersionBeforeSending() {
        val transport = RecordingConnectionFactory()
        val client = JdkBridgeClient(connectionClient = transport)

        val result = client.clearText(expectedVersion = -1L)

        assertEquals("INVALID_EXPECTED_VERSION", (result as BridgeClientResult.Failure).code)
        assertTrue(transport.sentMessages.isEmpty())
    }

    private fun connectedClient(
        transport: RecordingConnectionFactory,
        listener: RecordingClientEventListener,
    ): JdkBridgeClient {
        val existingOnSend = transport.onSend
        transport.onSend = { message, session ->
            if (message is HelloCommand) {
                session.emit(
                    HelloAck(
                        status = "ok",
                        appVersion = "1.0.1",
                        protocolVersion = ProtocolConstants.CURRENT_VERSION,
                        serverTime = 100L,
                        requestId = message.requestId,
                    ),
                )
                session.emit(TextSnapshot("initial", 7L, 99L))
            } else {
                existingOnSend?.invoke(message, session)
            }
        }
        val client = JdkBridgeClient(
            connectionClient = transport,
            requestTimeout = Duration.ofMillis(100),
            requestIdFactory = RequestIds(),
        )
        assertEquals(
            BridgeClientResult.Success(TextSnapshot("initial", 7L, 99L)),
            client.connect(listener),
        )
        return client
    }
}

private class RequestIds : () -> String {
    private var next = 0

    override fun invoke(): String = "request-${next++}"
}

private class RecordingClientEventListener : BridgeClientEventListener {
    val textChanges = mutableListOf<TextChanged>()
    val closedCauses = mutableListOf<Throwable?>()
    val errors = mutableListOf<Throwable>()

    override fun onTextChanged(message: TextChanged) {
        textChanges += message
    }

    override fun onClosed(cause: Throwable?) {
        closedCauses += cause
    }

    override fun onError(cause: Throwable) {
        errors += cause
    }
}

private class RecordingConnectionFactory : ClientConnectionFactory {
    val session = RecordingConnection()
    var onSend: ((BridgeMessage, RecordingConnection) -> Unit)? = null

    val sentMessages: List<BridgeMessage>
        get() = session.sentMessages

    override fun connect(listener: ClientConnectionListener): ClientConnection {
        session.listener = listener
        session.onSend = { message, currentSession -> onSend?.invoke(message, currentSession) }
        return session
    }
}

private class RecordingConnection : ClientConnection {
    var listener: ClientConnectionListener? = null
    val sentMessages = mutableListOf<BridgeMessage>()
    var closeCalls = 0
    var onSend: ((BridgeMessage, RecordingConnection) -> Unit)? = null

    override fun send(message: BridgeMessage): Boolean {
        sentMessages += message
        onSend?.invoke(message, this)
        return true
    }

    fun emit(message: BridgeMessage) {
        listener?.onMessage(message)
    }

    fun emitClosed(cause: Throwable?) {
        listener?.onClosed(cause)
    }

    fun emitError(cause: Throwable) {
        listener?.onError(cause)
    }

    override fun close() {
        closeCalls++
    }
}
