package com.ericdevwang.inputbridge.core.connection.server.internal

internal data class OutgoingFrame(
    val payload: ByteArray,
    val closeAfterWrite: Boolean,
)
