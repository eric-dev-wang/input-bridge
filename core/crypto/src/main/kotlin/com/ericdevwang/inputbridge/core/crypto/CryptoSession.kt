package com.ericdevwang.inputbridge.core.crypto

interface CryptoSession {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(record: ByteArray): ByteArray
}
