package com.ericdevwang.inputbridge.core.connection.client

import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameReader
import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.Ping
import com.ericdevwang.inputbridge.protocol.Pong
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpConnectionClientTest {
    @Test
    fun automaticallyRepliesToProtocolPingWithMatchingPong() {
        ServerSocket(0).use { serverSocket ->
            val failure = AtomicReference<Throwable?>()
            val completed = CountDownLatch(1)
            val acceptThread = thread {
                try {
                    serverSocket.accept().use { socket ->
                        val reader = LengthPrefixedFrameReader(socket.getInputStream())
                        val writer = LengthPrefixedFrameWriter(socket.getOutputStream())
                        writer.writeMessage(Ping("ping-1"))
                        assertEquals(Pong("ping-1"), reader.readMessage())
                    }
                } catch (cause: Throwable) {
                    failure.set(cause)
                } finally {
                    completed.countDown()
                }
            }
            val client = TcpConnectionClient(
                TcpConnectionClientConfig(port = serverSocket.localPort),
            ).connect(object : ClientConnectionListener {})

            assertEquals(true, completed.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
            client.close()
            acceptThread.join(1_000)
        }
    }

    @Test
    fun closingConnectionStopsIdleWriterThread() {
        val writerThreadName = "input-bridge-tcp-client-writer"
        val baseline = liveThreadCount(writerThreadName)
        ServerSocket(0).use { serverSocket ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val acceptThread = thread {
                serverSocket.accept().use {
                    accepted.countDown()
                    release.await()
                }
            }
            val client = TcpConnectionClient(
                TcpConnectionClientConfig(port = serverSocket.localPort),
            ).connect(object : ClientConnectionListener {})

            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            client.close()
            awaitThreadCountAtMost(writerThreadName, baseline)

            release.countDown()
            serverSocket.close()
            acceptThread.join(1_000)
        }
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

private fun LengthPrefixedFrameWriter.writeMessage(message: BridgeMessage) {
    write(
        ProtocolJson.default
            .encodeToString(BridgeMessage.serializer(), message)
            .toByteArray(StandardCharsets.UTF_8),
    )
}

private fun LengthPrefixedFrameReader.readMessage(): BridgeMessage {
    val frame = read() ?: error("Expected a protocol message")
    return ProtocolJson.default.decodeFromString(frame.toString(StandardCharsets.UTF_8))
}
