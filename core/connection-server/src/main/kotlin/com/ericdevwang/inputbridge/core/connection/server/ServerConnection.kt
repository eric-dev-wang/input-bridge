package com.ericdevwang.inputbridge.core.connection.server

import com.ericdevwang.inputbridge.protocol.BridgeMessage

interface ServerConnection {
    fun send(message: BridgeMessage): Boolean

    fun sendAndClose(message: BridgeMessage): Boolean

    fun setListener(listener: ServerConnectionListener)

    fun close()
}
