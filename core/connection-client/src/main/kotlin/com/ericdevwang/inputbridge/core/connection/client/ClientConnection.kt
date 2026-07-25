package com.ericdevwang.inputbridge.core.connection.client

import com.ericdevwang.inputbridge.protocol.BridgeMessage

interface ClientConnection {
    fun send(message: BridgeMessage): Boolean

    fun close()
}
