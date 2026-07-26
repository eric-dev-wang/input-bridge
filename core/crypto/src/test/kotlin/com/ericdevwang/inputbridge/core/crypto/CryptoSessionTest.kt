package com.ericdevwang.inputbridge.core.crypto

import com.ericdevwang.inputbridge.core.crypto.internal.AuthenticationException
import com.ericdevwang.inputbridge.core.crypto.internal.ReplayException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class CryptoSessionTest {
    @Test
    fun handshakeCreatesBidirectionalAuthenticatedSessions() {
        val clientHandshake = ClientHandshake(TEST_SECRET)
        val serverHandshake = ServerHandshake(TEST_SECRET)

        val clientHello = clientHandshake.createHello()
        val serverHello = serverHandshake.acceptClientHello(clientHello)
        val clientFinish = clientHandshake.acceptServerHello(serverHello)
        val clientSession = clientHandshake.createSession()
        val serverSession = serverHandshake.acceptClientFinish(clientFinish)

        val clientPayload = "client payload".toByteArray()
        val serverPayload = "server payload".toByteArray()
        assertArrayEquals(clientPayload, serverSession.decrypt(clientSession.encrypt(clientPayload)))
        assertArrayEquals(serverPayload, clientSession.decrypt(serverSession.encrypt(serverPayload)))
    }

    @Test
    fun wrongSecretCannotCompleteHandshake() {
        val clientHandshake = ClientHandshake(TEST_SECRET)
        val serverHandshake = ServerHandshake("wrong-secret")

        val clientHello = clientHandshake.createHello()
        val serverHello = serverHandshake.acceptClientHello(clientHello)

        assertThrows<AuthenticationException> {
            clientHandshake.acceptServerHello(serverHello)
        }
    }

    @Test
    fun tamperedRecordCannotBeDecrypted() {
        val (clientSession, serverSession) = connectedSessions()
        val record = clientSession.encrypt("payload".toByteArray()).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertThrows<AuthenticationException> {
            serverSession.decrypt(record)
        }
    }

    @Test
    fun replayedRecordIsRejected() {
        val (clientSession, serverSession) = connectedSessions()
        val record = clientSession.encrypt("payload".toByteArray())
        serverSession.decrypt(record)

        assertThrows<ReplayException> {
            serverSession.decrypt(record)
        }
    }

    private fun connectedSessions(): Pair<CryptoSession, CryptoSession> {
        val clientHandshake = ClientHandshake(TEST_SECRET)
        val serverHandshake = ServerHandshake(TEST_SECRET)
        val clientHello = clientHandshake.createHello()
        val serverHello = serverHandshake.acceptClientHello(clientHello)
        val clientFinish = clientHandshake.acceptServerHello(serverHello)
        return clientHandshake.createSession() to serverHandshake.acceptClientFinish(clientFinish)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName ?: "unknown"}")
        } catch (cause: Throwable) {
            if (cause !is T) throw cause
        }
    }

    private companion object {
        const val TEST_SECRET = "test-shared-secret"
    }
}
