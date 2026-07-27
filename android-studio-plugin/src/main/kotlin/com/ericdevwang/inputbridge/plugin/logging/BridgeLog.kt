package com.ericdevwang.inputbridge.plugin.logging

import com.intellij.openapi.diagnostic.Logger

internal object BridgeLog {
    private val logger = Logger.getInstance("com.ericdevwang.inputbridge.plugin")

    fun textFetched(version: Long, length: Int) {
        logger.info(textFetchedMessage(version, length))
    }

    fun adbCommand(command: String, exitCode: Int?, timedOut: Boolean) {
        logger.info(
            "ADB command completed: command=$command, exitCode=${exitCode ?: "unknown"}, timedOut=$timedOut",
        )
    }

    fun clipboardWrite(success: Boolean, exception: Throwable? = null) {
        val message = "Clipboard write completed: success=$success"
        if (success) {
            logger.info(message)
        } else {
            logger.warn(failureMessage("Clipboard write", exception ?: IllegalStateException("unknown")))
        }
    }

    fun failure(operation: String, exception: Throwable) {
        logger.warn(failureMessage(operation, exception))
    }

    internal fun textFetchedMessage(version: Long, length: Int): String =
        "Fetched text metadata: version=$version, length=$length"

    internal fun failureMessage(operation: String, exception: Throwable): String =
        "$operation failed: exceptionType=${exception::class.java.name}"
}
