package com.ericdevwang.inputbridge.core.connection.server.internal

import com.ericdevwang.inputbridge.core.crypto.ENCRYPTED_RECORD_OVERHEAD_BYTES
import com.ericdevwang.inputbridge.core.crypto.ServerHandshake
import com.ericdevwang.inputbridge.core.crypto.CryptoSession
import com.ericdevwang.inputbridge.core.framing.DEFAULT_MAX_FRAME_BYTES
import com.ericdevwang.inputbridge.core.connection.server.ServerConnection
import com.ericdevwang.inputbridge.core.connection.server.ServerConnectionListener
import com.ericdevwang.inputbridge.core.connection.server.TcpConnectionServerConfig
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameReader
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.Ping
import com.ericdevwang.inputbridge.protocol.Pong
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class TcpServerConnection(
    private val socket: Socket,
    private val config: TcpConnectionServerConfig,
    private val onReady: (TcpServerConnection) -> Unit,
    private val onClosed: (TcpServerConnection, Throwable?) -> Unit,
) : ServerConnection {
    private val closed = AtomicBoolean(false)
    private val closeAfterWriteQueued = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val outgoing = LinkedBlockingQueue<OutgoingFrame>()
    private val reader = LengthPrefixedFrameReader(
        socket.getInputStream(),
        DEFAULT_MAX_FRAME_BYTES + ENCRYPTED_RECORD_OVERHEAD_BYTES,
    )
    private val writer = LengthPrefixedFrameWriter(
        socket.getOutputStream(),
        DEFAULT_MAX_FRAME_BYTES + ENCRYPTED_RECORD_OVERHEAD_BYTES,
    )
    private lateinit var session: CryptoSession
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "input-bridge-tcp-server-heartbeat").apply { isDaemon = true }
    }
    @Volatile
    private var lastPongAt = System.currentTimeMillis()
    @Volatile
    private var latestPingId: String? = null
    @Volatile
    private var writerThread: Thread? = null
    @Volatile
    private var applicationListener: ServerConnectionListener? = null

    internal fun start() {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            Thread(::runConnection, "input-bridge-tcp-server-handshake").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runConnection() {
        try {
            performHandshake()
            synchronized(lifecycleLock) {
                if (closed.get()) return
                writerThread = Thread(::writeLoop, "input-bridge-tcp-server-writer").apply {
                    isDaemon = true
                    start()
                }
            }
            // Register the application listener before reading application frames. The client
            // can send its first message immediately after the transport handshake completes.
            onReady(this)
            if (closed.get()) return
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
        } catch (cause: Throwable) {
            closeInternal(cause)
        }
    }

    private fun performHandshake() {
        socket.soTimeout = config.handshakeTimeoutMillis
        val handshake = ServerHandshake(config.sharedSecret)
        val clientHello = reader.read()
            ?: throw IOException("Transport handshake closed before client hello.")
        writer.write(handshake.acceptClientHello(clientHello))
        val clientFinish = reader.read()
            ?: throw IOException("Transport handshake closed before client finish.")
        session = handshake.acceptClientFinish(clientFinish)
        socket.soTimeout = 0
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
                writer.write(session.encrypt(frame.payload))
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
                val payload = session.decrypt(frame)
                val message = ProtocolJson.default.decodeFromString(
                    BridgeMessage.serializer(),
                    payload.toString(StandardCharsets.UTF_8),
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
        synchronized(lifecycleLock) {
            writerThread?.interrupt()
        }
        heartbeatExecutor.shutdownNow()
        runCatching { socket.close() }
        applicationListener?.onClosed(cause)
        onClosed(this, cause)
    }
}
