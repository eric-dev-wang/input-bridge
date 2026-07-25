package com.ericdevwang.inputbridge.core.connection.client

import com.ericdevwang.inputbridge.protocol.BridgeMessage

interface ClientConnectionListener {
    fun onMessage(message: BridgeMessage) = Unit

    fun onClosed(cause: Throwable?) = Unit

    fun onError(cause: Throwable) = Unit
}
