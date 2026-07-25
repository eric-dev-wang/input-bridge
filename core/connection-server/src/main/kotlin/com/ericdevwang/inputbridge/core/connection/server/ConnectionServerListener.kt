package com.ericdevwang.inputbridge.core.connection.server

interface ConnectionServerListener {
    fun onClientConnected(connection: ServerConnection)

    fun onClientDisconnected(connection: ServerConnection, cause: Throwable?) = Unit

    fun onError(cause: Throwable) = Unit
}
