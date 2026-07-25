package com.ericdevwang.inputbridge.core.crypto.internal

import com.ericdevwang.inputbridge.core.crypto.CryptoSession
import javax.crypto.spec.SecretKeySpec

internal object CryptoSessionFactory {
    fun forClient(material: SessionMaterial): CryptoSession = AesGcmCryptoSession(
        sendKey = aesKey(material.clientToServerKey),
        receiveKey = aesKey(material.serverToClientKey),
        sendNoncePrefix = material.clientToServerNoncePrefix,
        receiveNoncePrefix = material.serverToClientNoncePrefix,
    )

    fun forServer(material: SessionMaterial): CryptoSession = AesGcmCryptoSession(
        sendKey = aesKey(material.serverToClientKey),
        receiveKey = aesKey(material.clientToServerKey),
        sendNoncePrefix = material.serverToClientNoncePrefix,
        receiveNoncePrefix = material.clientToServerNoncePrefix,
    )

    private fun aesKey(key: ByteArray) = SecretKeySpec(key, "AES")
}
