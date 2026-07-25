package com.ericdevwang.inputbridge.core.connection.client

import com.ericdevwang.inputbridge.core.connection.server.ConnectionServerListener
import com.ericdevwang.inputbridge.core.connection.server.ServerConnection
import com.ericdevwang.inputbridge.core.connection.server.TcpConnectionServer
import com.ericdevwang.inputbridge.core.connection.server.TcpConnectionServerConfig
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpConnectionClientTest {
    @Test
    fun automaticallyRepliesToProtocolPingWithMatchingPong() {
        val clientClosed = CountDownLatch(1)
        val port = freePort()
        val server = TcpConnectionServer(
            TcpConnectionServerConfig(
                sharedSecret = TEST_SECRET,
                port = port,
                heartbeatIntervalMillis = 20L,
                heartbeatTimeoutMillis = 200L,
            ),
            object : ConnectionServerListener {
                override fun onClientConnected(connection: ServerConnection) {
                    connection.setListener(object : com.ericdevwang.inputbridge.core.connection.server.ServerConnectionListener {})
                }
            }
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
            assertFalse(clientClosed.await(300, TimeUnit.MILLISECONDS))
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun closingConnectionStopsIdleWriterThread() {
        val writerThreadName = "input-bridge-tcp-client-writer"
        val baseline = liveThreadCount(writerThreadName)
        val accepted = CountDownLatch(1)
        val port = freePort()
        val server = TcpConnectionServer(
            TcpConnectionServerConfig(sharedSecret = TEST_SECRET, port = port),
            object : ConnectionServerListener {
                override fun onClientConnected(connection: ServerConnection) {
                    accepted.countDown()
                }
            }
        )
        server.start()
        val client = TcpConnectionClient(
            TcpConnectionClientConfig(sharedSecret = TEST_SECRET, port = port),
        ).connect(object : ClientConnectionListener {})

        try {
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            client.close()
            awaitThreadCountAtMost(writerThreadName, baseline)
        } finally {
            server.stop()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val TEST_SECRET = "test-shared-secret"
    }
}

private fun liveThreadCount(name: String): Int =
    Thread.getAllStackTraces().keys.count { it.name == name && it.isAlive }

private fun awaitThreadCountAtMost(name: String, expectedMaximum: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (System.nanoTime() < deadline && liveThreadCount(name) > expectedMaximum) {
        Thread.sleep(10)
    }
    assertTrue(liveThreadCount(name) <= expectedMaximum)
}
