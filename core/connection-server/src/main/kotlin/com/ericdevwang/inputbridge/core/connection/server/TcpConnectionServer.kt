package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.core.connection.server.internal.TcpServerConnection
import com.ericdevwang.inputbridge.core.connection.server.internal.sendBusyAndClose
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
