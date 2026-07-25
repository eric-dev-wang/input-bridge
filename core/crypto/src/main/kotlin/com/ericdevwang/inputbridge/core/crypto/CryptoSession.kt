package com.ericdevwang.inputbridge.core.crypto

import com.ericdevwang.inputbridge.core.crypto.internal.SessionMaterial
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoSession private constructor(
    private val sendKey: SecretKeySpec,
    private val receiveKey: SecretKeySpec,
    private val sendNoncePrefix: ByteArray,
    private val receiveNoncePrefix: ByteArray,
) {
    private var sendSequence = 0L
    private var receiveSequence = 0L

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        check(sendSequence != Long.MAX_VALUE) { "Encryption sequence is exhausted" }
        val nonce = nonce(sendNoncePrefix, sendSequence)
        val ciphertext = try {
            createCipher(Cipher.ENCRYPT_MODE, sendKey, nonce).doFinal(plaintext)
        } catch (cause: GeneralSecurityException) {
            throw AuthenticationException("Could not encrypt the transport record.", cause)
        }
        sendSequence += 1
        return nonce + ciphertext
    }

    @Synchronized
    fun decrypt(record: ByteArray): ByteArray {
        if (record.size < ENCRYPTED_RECORD_OVERHEAD_BYTES) {
            throw AuthenticationException("Encrypted transport record is truncated.")
        }

        val nonce = record.copyOfRange(0, NONCE_BYTES)
        val prefix = nonce.copyOf(NONCE_PREFIX_BYTES)
        if (!prefix.contentEquals(receiveNoncePrefix)) {
            throw ReplayException("Encrypted transport record has an unexpected direction.")
        }

        val sequence = ByteBuffer.wrap(nonce, NONCE_PREFIX_BYTES, SEQUENCE_BYTES).long
        if (sequence != receiveSequence) {
            throw ReplayException("Encrypted transport record sequence is not the next expected value.")
        }

        val ciphertext = record.copyOfRange(NONCE_BYTES, record.size)
        val plaintext = try {
            createCipher(Cipher.DECRYPT_MODE, receiveKey, nonce).doFinal(ciphertext)
        } catch (cause: GeneralSecurityException) {
            throw AuthenticationException("Encrypted transport record authentication failed.", cause)
        }
        receiveSequence += 1
        return plaintext
    }

    internal companion object {
        private const val NONCE_PREFIX_BYTES = 4
        private const val SEQUENCE_BYTES = 8
        private const val NONCE_BYTES = 12

        fun forClient(material: SessionMaterial): CryptoSession = CryptoSession(
            sendKey = aesKey(material.clientToServerKey),
            receiveKey = aesKey(material.serverToClientKey),
            sendNoncePrefix = material.clientToServerNoncePrefix,
            receiveNoncePrefix = material.serverToClientNoncePrefix,
        )

        fun forServer(material: SessionMaterial): CryptoSession = CryptoSession(
            sendKey = aesKey(material.serverToClientKey),
            receiveKey = aesKey(material.clientToServerKey),
            sendNoncePrefix = material.serverToClientNoncePrefix,
            receiveNoncePrefix = material.clientToServerNoncePrefix,
        )

        private fun aesKey(key: ByteArray) = SecretKeySpec(key, "AES")

        private fun nonce(prefix: ByteArray, sequence: Long): ByteArray =
            ByteBuffer.allocate(NONCE_BYTES)
                .put(prefix)
                .putLong(sequence)
                .array()

        private fun createCipher(mode: Int, key: SecretKeySpec, nonce: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, key, GCMParameterSpec(128, nonce))
            }
    }
}
