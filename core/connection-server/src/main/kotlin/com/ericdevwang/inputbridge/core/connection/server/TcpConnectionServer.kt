package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.core.connection.server.internal.TcpServerConnection
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

class TcpConnectionServer(
    private val config: TcpConnectionServerConfig,
    private val listener: ConnectionServerListener,
) {
    private val running = AtomicBoolean(false)
    private val currentConnection = AtomicReference<TcpServerConnection?>()
    private val connections = ConcurrentHashMap.newKeySet<TcpServerConnection>()
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
        connections.forEach(TcpServerConnection::close)
        connections.clear()
        currentConnection.set(null)
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
        lateinit var connection: TcpServerConnection
        connection = TcpServerConnection(
            socket = socket,
            config = config,
            onReady = { readyConnection ->
                if (currentConnection.compareAndSet(null, readyConnection)) {
                    listener.onClientConnected(readyConnection)
                } else {
                    readyConnection.sendAndCloseServerBusy()
                }
            },
            onClosed = { closedConnection, cause ->
                connections.remove(closedConnection)
                if (currentConnection.compareAndSet(closedConnection, null)) {
                    listener.onClientDisconnected(closedConnection, cause)
                }
            },
        )
        connections.add(connection)
        connection.start()
    }
}

private fun TcpServerConnection.sendAndCloseServerBusy(): Boolean = sendAndClose(
    com.ericdevwang.inputbridge.protocol.BridgeError(
        code = "SERVER_BUSY",
        message = "The server already has an active client.",
    ),
)
