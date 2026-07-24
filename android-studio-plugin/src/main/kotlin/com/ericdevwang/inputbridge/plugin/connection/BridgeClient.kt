package com.ericdevwang.inputbridge.plugin.connection

import com.ericdevwang.inputbridge.core.connection.client.ClientConnection
import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionFactory
import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionListener
import com.ericdevwang.inputbridge.core.connection.client.TcpConnectionClient
import com.ericdevwang.inputbridge.core.connection.client.TcpConnectionClientConfig
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

sealed interface BridgeClientResult<out T> {
    data class Success<T>(val value: T) : BridgeClientResult<T>

    data class Failure(
        val message: String,
        val code: String? = null,
        val cause: Throwable? = null,
    ) : BridgeClientResult<Nothing>
}

sealed interface BridgeClearResult {
    data class Cleared(val clearedVersion: Long, val newVersion: Long) : BridgeClearResult

    data class VersionConflict(val currentVersion: Long) : BridgeClearResult
}

interface BridgeClientEventListener {
    fun onTextChanged(message: TextChanged) = Unit

    fun onClosed(cause: Throwable?) = Unit

    fun onError(cause: Throwable) = Unit
}

interface BridgeClient : AutoCloseable {
    fun connect(listener: BridgeClientEventListener): BridgeClientResult<TextSnapshot>

    fun getSnapshot(): BridgeClientResult<TextSnapshot>

    fun clearText(expectedVersion: Long): BridgeClientResult<BridgeClearResult>

    override fun close()
}

class JdkBridgeClient(
    private val connectionClient: ClientConnectionFactory,
    private val requestTimeout: Duration = BridgeNetworkConfig.requestTimeout,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : BridgeClient {
    private sealed interface IncomingEvent {
        data class Message(val message: BridgeMessage) : IncomingEvent
        data class Closed(val cause: Throwable?) : IncomingEvent
        data class Failed(val cause: Throwable) : IncomingEvent
    }

    private val incoming = LinkedBlockingQueue<IncomingEvent>()
    @Volatile
    private var session: ClientConnection? = null
    @Volatile
    private var eventListener: BridgeClientEventListener? = null

    override fun connect(listener: BridgeClientEventListener): BridgeClientResult<TextSnapshot> {
        closeSession()
        incoming.clear()
        eventListener = listener

        val connection = try {
            connectionClient.connect(object : ClientConnectionListener {
                override fun onMessage(message: BridgeMessage) {
                    if (message is TextChanged) listener.onTextChanged(message)
                    else incoming.offer(IncomingEvent.Message(message))
                }

                override fun onClosed(cause: Throwable?) {
                    session = null
                    incoming.offer(IncomingEvent.Closed(cause))
                    eventListener?.onClosed(cause)
                }

                override fun onError(cause: Throwable) {
                    incoming.offer(IncomingEvent.Failed(cause))
                    eventListener?.onError(cause)
                }
            })
        } catch (cause: Exception) {
            return failure("TCP connection failed: ${cause.message ?: "unknown error"}", "TCP_CONNECTION_FAILED", cause)
        }
        session = connection

        val helloRequestId = requestIdFactory()
        if (!send(HelloCommand(ProtocolConstants.CURRENT_VERSION, helloRequestId))) {
            return failure("TCP hello could not be sent.", "HANDSHAKE_SEND_FAILED")
        }

        val hello = when (val result = awaitMessage()) {
            is BridgeClientResult.Failure -> return result
            is BridgeClientResult.Success -> result.value as? HelloAck
                ?: return failure("TCP handshake returned an unexpected message.", "INVALID_HANDSHAKE")
        }
        if (hello.requestId != helloRequestId || hello.status != "ok") {
            return failure("TCP handshake was rejected.", "INVALID_HANDSHAKE")
        }
        if (hello.protocolVersion != ProtocolConstants.CURRENT_VERSION) {
            return failure("Unsupported TCP protocol version.", "UNSUPPORTED_PROTOCOL_VERSION")
        }

        return when (val result = awaitMessage()) {
            is BridgeClientResult.Failure -> result
            is BridgeClientResult.Success -> {
                val snapshot = result.value as? TextSnapshot
                    ?: return failure("TCP handshake returned an invalid snapshot.", "INVALID_HANDSHAKE")
                if (snapshot.requestId != null) {
                    failure("Initial TCP snapshot must not have a request ID.", "INVALID_HANDSHAKE")
                } else {
                    BridgeClientResult.Success(snapshot)
                }
            }
        }
    }

    override fun getSnapshot(): BridgeClientResult<TextSnapshot> {
        val requestId = requestIdFactory()
        if (!send(GetSnapshotCommand(requestId))) {
            return failure("TCP snapshot request could not be sent.", "REQUEST_SEND_FAILED")
        }
        return when (val result = awaitMessage()) {
            is BridgeClientResult.Failure -> result
            is BridgeClientResult.Success -> when (val message = result.value) {
                is TextSnapshot -> if (message.requestId == requestId) {
                    BridgeClientResult.Success(message)
                } else failure("TCP snapshot response request ID did not match.", "INVALID_RESPONSE")
                is BridgeError -> failure(message.message, message.code)
                else -> failure("TCP snapshot response was unexpected.", "INVALID_RESPONSE")
            }
        }
    }

    override fun clearText(expectedVersion: Long): BridgeClientResult<BridgeClearResult> {
        if (expectedVersion < 0L) return failure(
            "Expected version must be a non-negative integer.",
            "INVALID_EXPECTED_VERSION",
        )
        val requestId = requestIdFactory()
        if (!send(ClearCommand(expectedVersion, requestId))) {
            return failure("TCP clear request could not be sent.", "REQUEST_SEND_FAILED")
        }
        return when (val result = awaitMessage()) {
            is BridgeClientResult.Failure -> result
            is BridgeClientResult.Success -> when (val message = result.value) {
                is ClearSucceeded -> if (message.requestId == requestId) {
                    BridgeClientResult.Success(BridgeClearResult.Cleared(message.clearedVersion, message.newVersion))
                } else failure("TCP clear response request ID did not match.", "INVALID_RESPONSE")
                is VersionConflict -> if (message.requestId == requestId) {
                    BridgeClientResult.Success(BridgeClearResult.VersionConflict(message.currentVersion))
                } else failure("TCP clear response request ID did not match.", "INVALID_RESPONSE")
                is BridgeError -> failure(message.message, message.code)
                else -> failure("TCP clear response was unexpected.", "INVALID_RESPONSE")
            }
        }
    }

    override fun close() {
        closeSession()
        eventListener = null
    }

    private fun send(message: BridgeMessage): Boolean = session?.send(message) == true

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun awaitMessage(): BridgeClientResult<BridgeMessage> {
        val event = try {
            incoming.poll(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure("TCP request was interrupted.", "REQUEST_INTERRUPTED", interrupted)
        } ?: return failure("TCP request timed out.", "REQUEST_TIMEOUT")
        return when (event) {
            is IncomingEvent.Message -> BridgeClientResult.Success(event.message)
            is IncomingEvent.Closed -> failure("TCP connection closed.", "TCP_CLOSED", event.cause)
            is IncomingEvent.Failed -> failure("TCP connection failed.", "TCP_FAILED", event.cause)
        }
    }

    private fun failure(message: String, code: String, cause: Throwable? = null) =
        BridgeClientResult.Failure(message = message, code = code, cause = cause)

    companion object {
        fun create(): JdkBridgeClient = JdkBridgeClient(
            connectionClient = TcpConnectionClient(
                TcpConnectionClientConfig(
                    host = BridgeNetworkConfig.HOST,
                    port = BridgeNetworkConfig.PORT,
                    connectTimeoutMillis = BridgeNetworkConfig.TCP_CONNECT_TIMEOUT_MILLIS.toInt(),
                ),
            ),
        )
    }
}
