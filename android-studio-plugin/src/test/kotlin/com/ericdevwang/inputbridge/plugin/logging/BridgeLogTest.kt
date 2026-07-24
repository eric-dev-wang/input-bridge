package com.ericdevwang.inputbridge.plugin.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogTest {
    @Test
    fun textMessageContainsLengthAndVersionButNotText() {
        val message = BridgeLog.textFetchedMessage(version = 17, length = 123)

        assertTrue(message.contains("version=17"))
        assertTrue(message.contains("length=123"))
        assertFalse(message.contains("secret text"))
    }

    @Test
    fun failureMessageContainsExceptionTypeButNotExceptionMessage() {
        val message = BridgeLog.failureMessage("TCP request", IllegalStateException("secret text"))

        assertTrue(message.contains("IllegalStateException"))
        assertFalse(message.contains("secret text"))
    }
}
