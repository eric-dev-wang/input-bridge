package com.ericdevwang.inputbridge.core.connection.client

import java.io.IOException

interface ClientConnectionFactory {
    @Throws(IOException::class)
    fun connect(listener: ClientConnectionListener): ClientConnection
}
