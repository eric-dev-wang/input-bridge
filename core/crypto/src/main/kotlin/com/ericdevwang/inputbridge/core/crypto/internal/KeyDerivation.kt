package com.ericdevwang.inputbridge.core.crypto.internal

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class SessionMaterial(
    val clientToServerKey: ByteArray,
    val serverToClientKey: ByteArray,
    val clientToServerNoncePrefix: ByteArray,
    val serverToClientNoncePrefix: ByteArray,
)

internal object KeyDerivation {
    private const val HASH_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 4

    fun sessionMaterial(
        sharedSecret: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): SessionMaterial {
        val salt = clientNonce + serverNonce
        val prk = extract(salt, sharedSecret)
        return SessionMaterial(
            clientToServerKey = expand(prk, "client-to-server key", HASH_BYTES),
            serverToClientKey = expand(prk, "server-to-client key", HASH_BYTES),
            clientToServerNoncePrefix = expand(prk, "client-to-server nonce prefix", NONCE_PREFIX_BYTES),
            serverToClientNoncePrefix = expand(prk, "server-to-client nonce prefix", NONCE_PREFIX_BYTES),
        )
    }

    fun proof(
        sharedSecret: ByteArray,
        role: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        val prk = extract(clientNonce + serverNonce, sharedSecret)
        return hmac(prk, "input-bridge transport $role".toByteArray(StandardCharsets.UTF_8) + clientNonce + serverNonce)
    }

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var result = 0
        for (index in left.indices) result = result or (left[index].toInt() xor right[index].toInt())
        return result == 0
    }

    private fun extract(salt: ByteArray, input: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, input)

    private fun expand(prk: ByteArray, info: String, length: Int): ByteArray {
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(
                prk,
                previous + info.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(counter.toByte()),
            )
            val copyLength = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, endIndex = copyLength)
            offset += copyLength
            counter += 1
        }
        return output
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(input)
        }
}
