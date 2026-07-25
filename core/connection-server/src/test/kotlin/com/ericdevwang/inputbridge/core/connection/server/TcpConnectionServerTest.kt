package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.core.connection.client.ClientConnectionListener
import com.ericdevwang.inputbridge.core.connection.client.TcpConnectionClient
import com.ericdevwang.inputbridge.core.connection.client.TcpConnectionClientConfig
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpConnectionServerTest {
    @Test
    fun sendsPingAndAcceptsMatchingPong() {
        val port = ServerSocket(0).use { it.localPort }
        val connected = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val server = TcpConnectionServer(
            TcpConnectionServerConfig(
                sharedSecret = TEST_SECRET,
                port = port,
                heartbeatIntervalMillis = 20L,
                heartbeatTimeoutMillis = 200L,
            ),
            object : ConnectionServerListener {
                override fun onClientConnected(connection: ServerConnection) {
                    connected.countDown()
                }
            },
        )
        server.start()
        val client = TcpConnectionClient(
            TcpConnectionClientConfig(sharedSecret = TEST_SECRET, port = port),
        ).connect(object : ClientConnectionListener {
            override fun onClosed(cause: Throwable?) {
                clientClosed.countDown()
            }
        })

        try {
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            assertFalse(clientClosed.await(300, TimeUnit.MILLISECONDS))
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun closingServerStopsPendingHandshake() {
        val port = ServerSocket(0).use { it.localPort }
        val server = TcpConnectionServer(
            TcpConnectionServerConfig(
                sharedSecret = TEST_SECRET,
                port = port,
                handshakeTimeoutMillis = 5_000,
            ),
            object : ConnectionServerListener {
                override fun onClientConnected(connection: ServerConnection) = Unit
            },
        )
        server.start()
        val connected = CountDownLatch(1)
        val socketThread = thread(isDaemon = true) {
            runCatching {
                java.net.Socket("127.0.0.1", port).use {
                    connected.countDown()
                    Thread.sleep(2_000)
                }
            }
        }

        try {
            assertTrue(connected.await(2, TimeUnit.SECONDS))
        } finally {
            server.stop()
            socketThread.join(1_000)
        }
    }

    private companion object {
        const val TEST_SECRET = "test-shared-secret"
    }
}
