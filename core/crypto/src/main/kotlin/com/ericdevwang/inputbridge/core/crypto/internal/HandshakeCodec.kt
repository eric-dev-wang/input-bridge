package com.ericdevwang.inputbridge.core.crypto.internal

import java.nio.ByteBuffer

private val MAGIC = byteArrayOf(0x49, 0x42, 0x54, 0x50)
private const val CLIENT_HELLO = 1
private const val SERVER_HELLO = 2
private const val CLIENT_FINISH = 3
private const val NONCE_BYTES = 32
private const val PROOF_BYTES = 32

internal object HandshakeCodec {
    fun clientHello(clientNonce: ByteArray): ByteArray =
        encode(CLIENT_HELLO, clientNonce)

    fun parseClientHello(bytes: ByteArray): ByteArray =
        parse(bytes, CLIENT_HELLO, NONCE_BYTES)

    fun serverHello(serverNonce: ByteArray, proof: ByteArray): ByteArray =
        encode(SERVER_HELLO, serverNonce, proof)

    fun parseServerHello(bytes: ByteArray): ServerHello {
        val body = parse(bytes, SERVER_HELLO, NONCE_BYTES + PROOF_BYTES)
        return ServerHello(
            serverNonce = body.copyOfRange(0, NONCE_BYTES),
            proof = body.copyOfRange(NONCE_BYTES, body.size),
        )
    }

    fun clientFinish(proof: ByteArray): ByteArray =
        encode(CLIENT_FINISH, proof)

    fun parseClientFinish(bytes: ByteArray): ByteArray =
        parse(bytes, CLIENT_FINISH, PROOF_BYTES)

    private fun encode(type: Int, vararg parts: ByteArray): ByteArray {
        val bodySize = parts.sumOf { it.size }
        return ByteBuffer.allocate(MAGIC.size + 2 + bodySize).apply {
            put(MAGIC)
            put(TRANSPORT_PROTOCOL_VERSION.toByte())
            put(type.toByte())
            parts.forEach(::put)
        }.array()
    }

    private fun parse(bytes: ByteArray, expectedType: Int, bodySize: Int): ByteArray {
        if (bytes.size != MAGIC.size + 2 + bodySize) {
            throw HandshakeException("Invalid transport handshake frame size.")
        }
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw HandshakeException("Invalid transport handshake magic.")
        if (buffer.get().toInt() != TRANSPORT_PROTOCOL_VERSION) {
            throw HandshakeException("Unsupported transport protocol version.")
        }
        if (buffer.get().toInt() != expectedType) throw HandshakeException("Unexpected transport handshake message.")
        return ByteArray(bodySize).also(buffer::get)
    }
}

internal data class ServerHello(
    val serverNonce: ByteArray,
    val proof: ByteArray,
)
