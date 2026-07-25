package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.protocol.BridgeMessage

interface ServerConnectionListener {
    fun onMessage(message: BridgeMessage) = Unit

    fun onClosed(cause: Throwable?) = Unit

    fun onError(cause: Throwable) = Unit
}
