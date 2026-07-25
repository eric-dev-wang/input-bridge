package com.ericdevwang.inputbridge.core.crypto

import com.ericdevwang.inputbridge.core.crypto.internal.AuthenticationException
import com.ericdevwang.inputbridge.core.crypto.internal.CryptoSessionFactory
import com.ericdevwang.inputbridge.core.crypto.internal.HandshakeCodec
import com.ericdevwang.inputbridge.core.crypto.internal.HandshakeException
import com.ericdevwang.inputbridge.core.crypto.internal.KeyDerivation
import com.ericdevwang.inputbridge.core.crypto.internal.SessionMaterial
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class ClientHandshake(sharedSecret: String) {
    private val sharedSecretBytes = sharedSecret.requireSecret().toByteArray(StandardCharsets.UTF_8)
    private val clientNonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
    private var material: SessionMaterial? = null

    fun createHello(): ByteArray = HandshakeCodec.clientHello(clientNonce)

    fun acceptServerHello(serverHello: ByteArray): ByteArray {
        check(material == null) { "Transport handshake has already completed" }
        val parsed = HandshakeCodec.parseServerHello(serverHello)
        val expectedProof = KeyDerivation.proof(sharedSecretBytes, "server", clientNonce, parsed.serverNonce)
        if (!KeyDerivation.constantTimeEquals(expectedProof, parsed.proof)) {
            throw AuthenticationException("Transport server authentication failed.")
        }
        material = KeyDerivation.sessionMaterial(sharedSecretBytes, clientNonce, parsed.serverNonce)
        val clientProof = KeyDerivation.proof(sharedSecretBytes, "client", clientNonce, parsed.serverNonce)
        return HandshakeCodec.clientFinish(clientProof)
    }

    fun createSession(): CryptoSession =
        CryptoSessionFactory.forClient(checkNotNull(material) { "Transport handshake is incomplete" })
}

private const val NONCE_BYTES = 32

private fun String.requireSecret(): String {
    if (isEmpty()) throw HandshakeException("Shared secret must not be empty.")
    return this
}
