package com.ericdevwang.inputbridge.core.connection.server.internal

import com.ericdevwang.inputbridge.core.framing.LengthPrefixedFrameWriter
import com.ericdevwang.inputbridge.protocol.BridgeError
import com.ericdevwang.inputbridge.protocol.BridgeMessage
import com.ericdevwang.inputbridge.protocol.ProtocolJson
import java.net.Socket
import java.nio.charset.StandardCharsets

internal fun sendBusyAndClose(socket: Socket) {
    try {
        val payload = ProtocolJson.default
            .encodeToString(
                BridgeMessage.serializer(),
                BridgeError(
                    code = "SERVER_BUSY",
                    message = "The server already has an active client.",
                ),
            )
            .toByteArray(StandardCharsets.UTF_8)
        LengthPrefixedFrameWriter(socket.getOutputStream()).write(payload)
    } finally {
        runCatching { socket.close() }
    }
}
