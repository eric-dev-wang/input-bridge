package com.ericdevwang.inputbridge.server

import com.ericdevwang.inputbridge.BuildConfig
import com.ericdevwang.inputbridge.core.connection.server.ConnectionServerListener
import com.ericdevwang.inputbridge.core.connection.server.ServerConnection
import com.ericdevwang.inputbridge.core.connection.server.ServerConnectionListener
import com.ericdevwang.inputbridge.core.connection.server.TcpConnectionServer
import com.ericdevwang.inputbridge.core.connection.server.TcpConnectionServerConfig
import com.ericdevwang.inputbridge.core.data.model.TextState
import com.ericdevwang.inputbridge.core.data.repository.ClearResult
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.protocol.BridgeError
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.ClearCommand
import com.ericdevwang.inputbridge.protocol.ClearSucceeded
import com.ericdevwang.inputbridge.protocol.GetSnapshotCommand
import com.ericdevwang.inputbridge.protocol.HelloAck
import com.ericdevwang.inputbridge.protocol.HelloCommand
import com.ericdevwang.inputbridge.protocol.ProtocolConstants
import com.ericdevwang.inputbridge.protocol.TextChanged
import com.ericdevwang.inputbridge.protocol.TextSnapshot
import com.ericdevwang.inputbridge.protocol.VersionConflict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

const val DEFAULT_SERVER_HOST = "127.0.0.1"
const val DEFAULT_SERVER_PORT = 18080
private const val INITIAL_SNAPSHOT_TIMEOUT_MILLIS = 2_000L

data class InputBridgeServerConfig(
    val host: String = DEFAULT_SERVER_HOST,
    val port: Int = DEFAULT_SERVER_PORT,
    val sharedSecret: String = BuildConfig.INPUT_BRIDGE_SHARED_SECRET,
)

class InputBridgeServer(
    private val repository: TextRepository,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: InputBridgeServerConfig = InputBridgeServerConfig(),
    private val initialSnapshotTimeoutMillis: Long = INITIAL_SNAPSHOT_TIMEOUT_MILLIS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: TcpConnectionServer? = null

    @Synchronized
    fun start() {
        if (server != null) return

        server = TcpConnectionServer(
            config = TcpConnectionServerConfig(
                sharedSecret = config.sharedSecret,
                host = config.host,
                port = config.port,
            ),
            listener = object : ConnectionServerListener {
                override fun onClientConnected(connection: ServerConnection) {
                    val events = Channel<SessionEvent>(Channel.UNLIMITED)
                    connection.setListener(object : ServerConnectionListener {
                        override fun onMessage(message: BridgeMessage) {
                            events.trySend(SessionEvent.Message(message))
                        }

                        override fun onClosed(cause: Throwable?) {
                            events.trySend(SessionEvent.Closed(cause))
                        }

                        override fun onError(cause: Throwable) {
                            events.trySend(SessionEvent.Error(cause))
                        }
                    })
                    scope.launch { handleSession(connection, events) }
                }
            },
        ).also { it.start() }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private suspend fun handleSession(
        connection: ServerConnection,
        events: Channel<SessionEvent>,
    ) {
        try {
            val hello = when (val event = withTimeout(initialSnapshotTimeoutMillis) { events.receive() }) {
                is SessionEvent.Message -> event.message as? HelloCommand
                    ?: return reject(connection, "INVALID_HANDSHAKE", "The first TCP message must be hello.")
                is SessionEvent.Closed -> return
                is SessionEvent.Error -> return reject(connection, "MALFORMED_MESSAGE", "TCP message could not be decoded.")
            }

            if (hello.protocolVersion != ProtocolConstants.CURRENT_VERSION) {
                return reject(
                    connection,
                    "UNSUPPORTED_PROTOCOL_VERSION",
                    "Unsupported protocol version.",
                    hello.requestId,
                )
            }

            connection.send(
                HelloAck(
                    status = "ok",
                    appVersion = appVersion,
                    protocolVersion = ProtocolConstants.CURRENT_VERSION,
                    serverTime = clock(),
                    requestId = hello.requestId,
                ),
            )

            val updates = Channel<TextState>(Channel.CONFLATED)
            val observationJob = scope.launch {
                repository.state.collect { updates.trySend(it) }
            }
            try {
                val initialState = withTimeout(initialSnapshotTimeoutMillis) { updates.receive() }
                connection.send(initialState.toSnapshot())
                val pushJob = scope.launch {
                    for (state in updates) connection.send(state.toChanged())
                }
                try {
                    processMessages(connection, events)
                } finally {
                    pushJob.cancel()
                }
            } finally {
                observationJob.cancel()
                updates.close()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            reject(connection, "INITIAL_SNAPSHOT_TIMEOUT", "Current text snapshot was not available in time.")
        } catch (_: CancellationException) {
            throw CancellationException()
        } finally {
            events.close()
            connection.close()
        }
    }

    private suspend fun processMessages(
        connection: ServerConnection,
        events: Channel<SessionEvent>,
    ) {
        while (true) {
            when (val event = events.receive()) {
                is SessionEvent.Message -> when (val message = event.message) {
                    is GetSnapshotCommand -> connection.send(repository.state.first().toSnapshot(message.requestId))
                    is ClearCommand -> handleClear(connection, message)
                    else -> {
                        reject(
                            connection,
                            "UNEXPECTED_MESSAGE",
                            "This message is not valid after the handshake.",
                            message.requestIdOrNull(),
                        )
                        return
                    }
                }
                is SessionEvent.Closed -> return
                is SessionEvent.Error -> {
                    reject(connection, "MALFORMED_MESSAGE", "TCP message could not be decoded.")
                    return
                }
            }
        }
    }

    private suspend fun handleClear(connection: ServerConnection, command: ClearCommand) {
        if (command.expectedVersion < 0L) {
            connection.send(
                BridgeError(
                    code = "INVALID_EXPECTED_VERSION",
                    message = "Expected version must be a non-negative integer.",
                    requestId = command.requestId,
                ),
            )
            return
        }

        try {
            when (val result = repository.clear(command.expectedVersion)) {
                is ClearResult.Cleared -> connection.send(
                    ClearSucceeded(result.clearedVersion, result.newVersion, command.requestId),
                )
                is ClearResult.VersionConflict -> connection.send(
                    VersionConflict(result.currentVersion, command.requestId),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            connection.send(
                BridgeError(
                    code = "TEXT_CLEAR_FAILED",
                    message = "Current text could not be cleared.",
                    requestId = command.requestId,
                ),
            )
        }
    }

    private fun reject(
        connection: ServerConnection,
        code: String,
        message: String,
        requestId: String? = null,
    ) {
        connection.sendAndClose(BridgeError(code = code, message = message, requestId = requestId))
    }
}

private sealed interface SessionEvent {
    data class Message(val message: BridgeMessage) : SessionEvent

    data class Closed(val cause: Throwable?) : SessionEvent

    data class Error(val cause: Throwable) : SessionEvent
}

private fun TextState.toSnapshot(requestId: String? = null): TextSnapshot =
    TextSnapshot(text = text, version = version, updatedAt = updatedAt, requestId = requestId)

private fun TextState.toChanged(): TextChanged =
    TextChanged(text = text, version = version, updatedAt = updatedAt)

private fun BridgeMessage.requestIdOrNull(): String? = when (this) {
    is HelloCommand -> requestId
    is GetSnapshotCommand -> requestId
    is ClearCommand -> requestId
    is HelloAck -> requestId
    is TextSnapshot -> requestId
    is ClearSucceeded -> requestId
    is VersionConflict -> requestId
    is BridgeError -> requestId
    else -> null
}
