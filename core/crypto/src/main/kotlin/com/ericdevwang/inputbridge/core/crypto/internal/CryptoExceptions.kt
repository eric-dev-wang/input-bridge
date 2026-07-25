package com.ericdevwang.inputbridge.core.crypto.internal

import java.io.IOException

internal open class CryptoException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class HandshakeException(message: String) : CryptoException(message)

internal class AuthenticationException(message: String, cause: Throwable? = null) :
    CryptoException(message, cause)

internal class ReplayException(message: String) : CryptoException(message)
