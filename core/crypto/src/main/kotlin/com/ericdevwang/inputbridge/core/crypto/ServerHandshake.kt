package com.ericdevwang.inputbridge.core.crypto

import com.ericdevwang.inputbridge.core.crypto.internal.HandshakeCodec
import com.ericdevwang.inputbridge.core.crypto.internal.KeyDerivation
import com.ericdevwang.inputbridge.core.crypto.internal.SessionMaterial
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class ServerHandshake(
    sharedSecret: String,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val sharedSecretBytes = sharedSecret.requireSecret().toByteArray(StandardCharsets.UTF_8)
    private var clientNonce: ByteArray? = null
    private var serverNonce: ByteArray? = null
    private var material: SessionMaterial? = null

    fun acceptClientHello(clientHello: ByteArray): ByteArray {
        check(clientNonce == null) { "Transport handshake has already started" }
        val parsedClientNonce = HandshakeCodec.parseClientHello(clientHello)
        val generatedServerNonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        clientNonce = parsedClientNonce
        serverNonce = generatedServerNonce
        material = KeyDerivation.sessionMaterial(sharedSecretBytes, parsedClientNonce, generatedServerNonce)
        val proof = KeyDerivation.proof(sharedSecretBytes, "server", parsedClientNonce, generatedServerNonce)
        return HandshakeCodec.serverHello(generatedServerNonce, proof)
    }

    fun acceptClientFinish(clientFinish: ByteArray): CryptoSession {
        val currentClientNonce = checkNotNull(clientNonce) { "Client hello is missing" }
        val currentServerNonce = checkNotNull(serverNonce) { "Server hello is missing" }
        val expectedProof = KeyDerivation.proof(sharedSecretBytes, "client", currentClientNonce, currentServerNonce)
        if (!KeyDerivation.constantTimeEquals(expectedProof, HandshakeCodec.parseClientFinish(clientFinish))) {
            throw AuthenticationException("Transport client authentication failed.")
        }
        return CryptoSession.forServer(checkNotNull(material))
    }
}

private const val NONCE_BYTES = 32

private fun String.requireSecret(): String {
    if (isEmpty()) throw HandshakeException("Shared secret must not be empty.")
    return this
}
