package com.ericdevwang.inputbridge.core.crypto

import java.io.IOException

open class CryptoException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class HandshakeException(message: String) : CryptoException(message)

class AuthenticationException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

class ReplayException(message: String) : CryptoException(message)
