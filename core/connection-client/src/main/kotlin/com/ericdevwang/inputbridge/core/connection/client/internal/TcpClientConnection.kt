package com.ericdevwang.inputbridge.core.connection.client.internal

import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameReader
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.core.connection.client.ClientConnection
import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionListener
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.Ping
import com.ericdevwang.inputbridge.protocol.Pong
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class TcpClientConnection(
    private val socket: Socket,
    private val listener: ClientConnectionListener,
) : ClientConnection {
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val outgoing = LinkedBlockingQueue<ByteArray>()
    private val reader = LengthPrefixedFrameReader(socket.getInputStream())
    private val writer = LengthPrefixedFrameWriter(socket.getOutputStream())
    @Volatile
    private var writerThread: Thread? = null

    internal fun start() {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            writerThread = Thread(::writeLoop, "input-bridge-tcp-client-writer").apply {
                isDaemon = true
                start()
            }
        }
        Thread(::readLoop, "input-bridge-tcp-client-reader").apply {
            isDaemon = true
            start()
        }
    }

    override fun send(message: BridgeMessage): Boolean {
        if (closed.get()) return false

        val payload = ProtocolJson.default
            .encodeToString(BridgeMessage.serializer(), message)
            .toByteArray(StandardCharsets.UTF_8)
        return outgoing.offer(payload)
    }

    override fun close() {
        closeInternal(null)
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                writer.write(outgoing.take())
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (cause: Throwable) {
            if (!closed.get()) listener.onError(cause)
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
                    is Pong -> Unit
                    else -> listener.onMessage(message)
                }
            }
        } catch (cause: Throwable) {
            closeCause = cause
            if (!closed.get() && cause !is IOException) listener.onError(cause)
        } finally {
            closeInternal(closeCause)
        }
    }

    private fun closeInternal(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            writerThread?.interrupt()
        }
        runCatching { socket.close() }
        listener.onClosed(cause)
    }
}
