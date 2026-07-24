package com.ericdevwang.inputbridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface BridgeMessage

@Serializable
@SerialName("hello")
data class HelloCommand(
    val protocolVersion: Int,
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("get_snapshot")
data class GetSnapshotCommand(
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("clear")
data class ClearCommand(
    val expectedVersion: Long,
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("ping")
data class Ping(
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("pong")
data class Pong(
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("hello_ack")
data class HelloAck(
    val status: String,
    val appVersion: String,
    val protocolVersion: Int,
    val serverTime: Long,
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("text_snapshot")
data class TextSnapshot(
    val text: String,
    val version: Long,
    val updatedAt: Long,
    val requestId: String? = null,
) : BridgeMessage

@Serializable
@SerialName("text_changed")
data class TextChanged(
    val text: String,
    val version: Long,
    val updatedAt: Long,
) : BridgeMessage

@Serializable
@SerialName("clear_succeeded")
data class ClearSucceeded(
    val clearedVersion: Long,
    val newVersion: Long,
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("version_conflict")
data class VersionConflict(
    val currentVersion: Long,
    val requestId: String,
) : BridgeMessage

@Serializable
@SerialName("error")
data class BridgeError(
    val code: String,
    val message: String,
    val details: JsonElement = JsonObject(emptyMap()),
    val requestId: String? = null,
) : BridgeMessage
