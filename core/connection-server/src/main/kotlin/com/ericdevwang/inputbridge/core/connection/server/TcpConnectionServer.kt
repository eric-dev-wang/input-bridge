package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameReader
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.protocol.BridgeError
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.Ping
import com.ericdevwang.inputbridge.protocol.Pong
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class TcpConnectionServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 18080,
    val heartbeatIntervalMillis: Long = 15_000,
    val heartbeatTimeoutMillis: Long = 30_000,
)

interface ServerConnection {
    fun send(message: BridgeMessage): Boolean

    fun sendAndClose(message: BridgeMessage): Boolean

    fun setListener(listener: ServerConnectionListener)

    fun close()
}

interface ServerConnectionListener {
    fun onMessage(message: BridgeMessage) = Unit

    fun onClosed(cause: Throwable?) = Unit

    fun onError(cause: Throwable) = Unit
}

interface ConnectionServerListener {
    fun onClientConnected(connection: ServerConnection)

    fun onClientDisconnected(connection: ServerConnection, cause: Throwable?) = Unit

    fun onError(cause: Throwable) = Unit
}

class TcpConnectionServer(
    private val config: TcpConnectionServerConfig = TcpConnectionServerConfig(),
    private val listener: ConnectionServerListener,
) {
    private val running = AtomicBoolean(false)
    private val currentConnection = AtomicReference<TcpServerConnection?>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(config.host, config.port))
            }
        } catch (cause: Throwable) {
            running.set(false)
            throw cause
        }

        acceptThread = Thread(::acceptLoop, "input-bridge-tcp-server-acceptor").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        currentConnection.getAndSet(null)?.close()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (cause: IOException) {
                if (running.get()) listener.onError(cause)
                break
            }
            socket.tcpNoDelay = true
            accept(socket)
        }
    }

    private fun accept(socket: Socket) {
        val connection = TcpServerConnection(socket, config) { closedConnection, cause ->
            currentConnection.compareAndSet(closedConnection, null)
            listener.onClientDisconnected(closedConnection, cause)
        }

        if (!currentConnection.compareAndSet(null, connection)) {
            sendBusyAndClose(socket)
            return
        }

        listener.onClientConnected(connection)
        connection.start()
    }
}

private class TcpServerConnection(
    private val socket: Socket,
    private val config: TcpConnectionServerConfig,
    private val onClosed: (TcpServerConnection, Throwable?) -> Unit,
) : ServerConnection {
    private val closed = AtomicBoolean(false)
    private val closeAfterWriteQueued = AtomicBoolean(false)
    private data class OutgoingFrame(val payload: ByteArray, val closeAfterWrite: Boolean)

    private val outgoing = LinkedBlockingQueue<OutgoingFrame>()
    private val reader = LengthPrefixedFrameReader(socket.getInputStream())
    private val writer = LengthPrefixedFrameWriter(socket.getOutputStream())
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "input-bridge-tcp-server-heartbeat").apply { isDaemon = true }
    }
    @Volatile
    private var lastPongAt = System.currentTimeMillis()
    @Volatile
    private var latestPingId: String? = null

    fun start() {
        Thread(::writeLoop, "input-bridge-tcp-server-writer").apply {
            isDaemon = true
            start()
        }
        Thread(::readLoop, "input-bridge-tcp-server-reader").apply {
            isDaemon = true
            start()
        }
        heartbeatExecutor.scheduleAtFixedRate(
            ::heartbeat,
            config.heartbeatIntervalMillis,
            config.heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun send(message: BridgeMessage): Boolean {
        if (closed.get()) return false

        val payload = ProtocolJson.default
            .encodeToString(BridgeMessage.serializer(), message)
            .toByteArray(StandardCharsets.UTF_8)
        return outgoing.offer(OutgoingFrame(payload, closeAfterWrite = false))
    }

    override fun sendAndClose(message: BridgeMessage): Boolean {
        if (closed.get()) return false

        val payload = ProtocolJson.default
            .encodeToString(BridgeMessage.serializer(), message)
            .toByteArray(StandardCharsets.UTF_8)
        if (!closeAfterWriteQueued.compareAndSet(false, true)) return false
        return outgoing.offer(OutgoingFrame(payload, closeAfterWrite = true))
    }

    @Volatile
    private var applicationListener: ServerConnectionListener? = null

    override fun setListener(listener: ServerConnectionListener) {
        applicationListener = listener
    }

    override fun close() {
        if (closeAfterWriteQueued.get() && !closed.get()) return
        closeInternal(null)
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val frame = outgoing.take()
                writer.write(frame.payload)
                if (frame.closeAfterWrite) closeInternal(null)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (cause: Throwable) {
            closeInternal(cause)
        }
    }

    private fun readLoop() {
        var closeCause: Throwable? = null
        try {
            while (!closed.get()) {
                val frame = reader.read() ?: break
                val message = ProtocolJson.default.decodeFromString(
                    BridgeMessage.serializer(),
                    frame.toString(StandardCharsets.UTF_8),
                )
                when (message) {
                    is Ping -> send(Pong(message.requestId))
                    is Pong -> {
                        if (message.requestId == latestPingId) {
                            lastPongAt = System.currentTimeMillis()
                        }
                    }
                    else -> onApplicationMessage(message)
                }
            }
        } catch (cause: Throwable) {
            closeCause = cause
            if (!closed.get() && cause !is IOException) applicationListener?.onError(cause)
        } finally {
            closeInternal(closeCause)
        }
    }

    private fun onApplicationMessage(message: BridgeMessage) {
        applicationListener?.onMessage(message)
    }

    private fun heartbeat() {
        if (closed.get()) return
        if (System.currentTimeMillis() - lastPongAt > config.heartbeatTimeoutMillis) {
            closeInternal(IOException("TCP heartbeat timed out"))
            return
        }
        val requestId = UUID.randomUUID().toString()
        latestPingId = requestId
        send(Ping(requestId))
    }

    private fun closeInternal(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        heartbeatExecutor.shutdownNow()
        runCatching { socket.close() }
        applicationListener?.onClosed(cause)
        onClosed(this, cause)
    }
}

private fun sendBusyAndClose(socket: Socket) {
    try {
        val payload = ProtocolJson.default
            .encodeToString(
                BridgeMessage.serializer(),
                BridgeError(
                    code = "SERVER_BUSY",
                    message = "The server already has an active client.",
                ),
            )
            .toByteArray(StandardCharsets.UTF_8)
        LengthPrefixedFrameWriter(socket.getOutputStream()).write(payload)
    } finally {
        runCatching { socket.close() }
    }
}
