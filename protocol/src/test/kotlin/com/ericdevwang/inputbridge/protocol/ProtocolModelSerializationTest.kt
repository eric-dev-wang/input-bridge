package com.ericdevwang.inputbridge.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolModelSerializationTest {
    @Test
    fun tcpMessagesUseTypedDiscriminatorAndRequestIds() {
        assertEquals(
            "{\"type\":\"hello\",\"protocolVersion\":3,\"requestId\":\"hello-1\"}",
            ProtocolJson.default.encodeToString<BridgeMessage>(HelloCommand(3, "hello-1")),
        )
        assertEquals(
            "{\"type\":\"text_snapshot\",\"text\":\"中文\\n😀\",\"version\":7,\"updatedAt\":123,\"requestId\":null}",
            ProtocolJson.default.encodeToString<BridgeMessage>(TextSnapshot("中文\n😀", 7L, 123L)),
        )
        assertEquals(
            TextChanged("live", 8L, 124L),
            ProtocolJson.default.decodeFromString<BridgeMessage>(
                "{\"type\":\"text_changed\",\"text\":\"live\",\"version\":8,\"updatedAt\":124}",
            ),
        )
    }

    @Test
    fun allTcpMessagesRoundTripThroughSharedJson() {
        val messages = listOf<BridgeMessage>(
            HelloCommand(3, "hello-1"),
            GetSnapshotCommand("snapshot-1"),
            ClearCommand(expectedVersion = 7L, requestId = "clear-1"),
            HelloAck("ok", "1.0.0", 3, 123L, "hello-1"),
            Ping("ping-1"),
            Pong("ping-1"),
            TextSnapshot("", 7L, 123L, requestId = "snapshot-1"),
            TextChanged("中文\n😀", 8L, 124L),
            ClearSucceeded(clearedVersion = 8L, newVersion = 9L, requestId = "clear-1"),
            VersionConflict(currentVersion = 10L, requestId = "clear-1"),
            BridgeError(
                code = "INVALID_MESSAGE",
                message = "Invalid message",
                details = JsonObject(mapOf("currentVersion" to JsonPrimitive(10L))),
                requestId = "clear-1",
            ),
        )

        messages.forEach { message ->
            val encoded = ProtocolJson.default.encodeToString<BridgeMessage>(message)
            assertEquals(message, ProtocolJson.default.decodeFromString<BridgeMessage>(encoded))
        }
    }

    @Test(expected = SerializationException::class)
    fun unknownTcpMessageTypeIsRejected() {
        ProtocolJson.default.decodeFromString<BridgeMessage>("{\"type\":\"unknown\"}")
    }

    @Test
    fun knownTcpMessageIgnoresUnknownFields() {
        assertEquals(
            HelloCommand(protocolVersion = 3, requestId = "hello-1"),
            ProtocolJson.default.decodeFromString<BridgeMessage>(
                "{\"type\":\"hello\",\"protocolVersion\":3," +
                    "\"requestId\":\"hello-1\",\"futureField\":true}",
            ),
        )
    }

    @Test
    fun protocolVersionIsSharedByClientsAndServer() {
        assertEquals(3, ProtocolConstants.CURRENT_VERSION)
    }
}
