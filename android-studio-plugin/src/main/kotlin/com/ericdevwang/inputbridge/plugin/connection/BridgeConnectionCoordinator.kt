package com.ericdevwang.inputbridge.plugin.connection

import com.ericdevwang.inputbridge.plugin.adb.AdbClient
import com.ericdevwang.inputbridge.plugin.adb.AdbLocator
import com.ericdevwang.inputbridge.plugin.adb.AdbResult
import com.ericdevwang.inputbridge.plugin.adb.DeviceSelector
import com.ericdevwang.inputbridge.plugin.adb.PortForwardManager
import com.ericdevwang.inputbridge.plugin.clipboard.ClipboardWriteResult
import com.ericdevwang.inputbridge.plugin.clipboard.ClipboardWriter
import com.ericdevwang.inputbridge.plugin.logging.BridgeLog
import com.ericdevwang.inputbridge.protocol.TextChanged
import com.ericdevwang.inputbridge.protocol.TextSnapshot
import com.intellij.openapi.Disposable
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executor

class BridgeConnectionCoordinator(
    private val adbLocator: AdbLocator,
    private val adbClientFactory: (Path) -> AdbClient,
    private val bridgeClientFactory: () -> BridgeClient,
    private val deviceSelector: DeviceSelector,
    private val executor: Executor,
    private val clipboardWriter: ClipboardWriter,
    private val clock: () -> Instant = { Instant.now() },
) : BridgeConnectionController {
    private val lock = Any()
    private val listeners = mutableListOf<(BridgeState) -> Unit>()

    @Volatile
    private var disposed = false

    @Volatile
    private var activeAdbClient: AdbClient? = null

    @Volatile
    private var activeBridgeClient: BridgeClient? = null

    @Volatile
    private var selectedSerial: String? = null

    @Volatile
    private var busy = false

    private data class StateNotification(
        val state: BridgeState,
        val listeners: List<(BridgeState) -> Unit>,
    )

    private data class DisplayedTextSnapshot(
        val text: String,
        val version: Long?,
    )

    private data class OperationStart(
        val snapshot: DisplayedTextSnapshot,
        val notification: StateNotification,
    )

    override var state: BridgeState = BridgeState()
        private set

    override fun addListener(listener: (BridgeState) -> Unit) {
        val current = synchronized(lock) {
            if (disposed) return
            listeners += listener
            state
        }
        listener(current)
    }

    override fun removeListener(listener: (BridgeState) -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    override fun reconnect() {
        val notification = synchronized(lock) {
            if (disposed || busy) return
            busy = true
            val newState = state.copy(isBusy = true, errorMessage = null)
            StateNotification(newState, publishLocked(newState))
        }
        notifyListeners(notification)
        submit { runReconnect() }
    }

    override fun selectDevice(serial: String) {
        val notification = synchronized(lock) {
            if (disposed || busy || state.devices.none { it.serial == serial }) return
            selectedSerial = serial
            val newState = state.copy(selectedSerial = serial, errorMessage = null)
            StateNotification(newState, publishLocked(newState))
        }
        notifyListeners(notification)
        reconnect()
    }

    override fun copy() {
        val operation = beginTextOperation(requireVersion = false) ?: return
        notifyListeners(operation.notification)
        submit { runCopy(operation.snapshot) }
    }

    override fun copyAndClear() {
        val operation = beginTextOperation(requireVersion = true) ?: return
        notifyListeners(operation.notification)
        submit { runCopyAndClear(operation.snapshot) }
    }

    override fun dispose() {
        val clientToClose = synchronized(lock) {
            if (disposed) return
            disposed = true
            busy = false
            activeAdbClient = null
            val client = activeBridgeClient
            activeBridgeClient = null
            listeners.clear()
            client
        }
        clientToClose?.let(::closeBridgeAsync)
    }

    private fun runReconnect() {
        closeActiveBridge()
        try {
            if (disposed) return
            val adbPath = adbLocator.locate()
            if (adbPath == null) {
                finish(
                    state.copy(
                        connectionState = BridgeConnectionState.ADB_UNAVAILABLE,
                        adbStatus = "Unavailable",
                        forwardStatus = "Not established",
                        serverStatus = "Offline",
                        errorMessage = "ADB executable was not found.",
                    ),
                )
                return
            }

            val adb = adbClientFactory(adbPath)
            activeAdbClient = adb
            val devices = when (val result = adb.devices()) {
                is AdbResult.Failure -> {
                    finishError("ADB device discovery failed: ${result.error.message}")
                    return
                }
                is AdbResult.Success -> result.value
            }
            if (devices.isEmpty()) {
                finish(
                    state.copy(
                        connectionState = BridgeConnectionState.NO_DEVICE,
                        devices = emptyList(),
                        selectedSerial = null,
                        adbStatus = "Available",
                        forwardStatus = "Not established",
                        serverStatus = "Offline",
                        errorMessage = "No Android device connected.",
                    ),
                )
                selectedSerial = null
                return
            }

            val selected = devices.firstOrNull { it.serial == selectedSerial }
                ?: deviceSelector.select(devices)
            selectedSerial = selected.serial
            publish(
                state.copy(
                    connectionState = BridgeConnectionState.FORWARDING,
                    devices = devices,
                    selectedSerial = selected.serial,
                    adbStatus = "Available",
                    forwardStatus = "Forwarding ${BridgeNetworkConfig.HOST}:${BridgeNetworkConfig.PORT} → device:${BridgeNetworkConfig.PORT}",
                    serverStatus = "Checking",
                    errorMessage = null,
                ),
            )

            val forwardManager = PortForwardManager(adb)
            when (val forward = forwardManager.ensureForward(selected)) {
                is AdbResult.Failure -> {
                    finishError("ADB port forwarding failed: ${forward.error.message}")
                    return
                }
                is AdbResult.Success -> Unit
            }

            var result = connectBridge()
            if (result is BridgeClientResult.Failure && result.code == "TCP_CONNECTION_FAILED") {
                result = when (val rebuilt = forwardManager.rebuildForward(selected)) {
                    is AdbResult.Failure -> {
                        finishError("ADB port forwarding failed: ${rebuilt.error.message}")
                        return
                    }
                    is AdbResult.Success -> connectBridge()
                }
            }
            when (result) {
                is BridgeClientResult.Success -> finishConnected(result.value)
                is BridgeClientResult.Failure -> finishConnectionFailure(result)
            }
        } catch (exception: Exception) {
            finishError(exception.message ?: "Unexpected connection error.")
        }
    }

    private fun runCopy(snapshot: DisplayedTextSnapshot) {
        if (disposed) return
        val result = runCatching { clipboardWriter.write(snapshot.text) }
            .getOrElse { ClipboardWriteResult.Failure(CLIPBOARD_FAILURE_MESSAGE, it) }
        when (result) {
            ClipboardWriteResult.Success -> finish(
                state.copy(errorMessage = null, feedbackMessage = COPIED_MESSAGE),
            )
            is ClipboardWriteResult.Failure -> finish(
                state.copy(errorMessage = null, feedbackMessage = result.message),
            )
        }
    }

    private fun runCopyAndClear(snapshot: DisplayedTextSnapshot) {
        if (disposed) return
        val clipboardResult = runCatching { clipboardWriter.write(snapshot.text) }
            .getOrElse { ClipboardWriteResult.Failure(CLIPBOARD_FAILURE_MESSAGE, it) }
        when (clipboardResult) {
            is ClipboardWriteResult.Failure -> {
                finish(state.copy(errorMessage = null, feedbackMessage = clipboardResult.message))
                return
            }
            ClipboardWriteResult.Success -> Unit
        }

        if (disposed) return
        val client = activeBridgeClient
        val expectedVersion = snapshot.version
        if (client == null || expectedVersion == null) {
            finishClearFailure()
            return
        }

        when (val result = runCatching { client.clearText(expectedVersion) }
            .getOrElse { BridgeClientResult.Failure("Clear request failed.", cause = it) }) {
            is BridgeClientResult.Success -> when (val clear = result.value) {
                is BridgeClearResult.Cleared -> finish(
                    state.copy(
                        text = "",
                        version = clear.newVersion,
                        lastRefresh = clock(),
                        errorMessage = null,
                        feedbackMessage = COPIED_AND_CLEARED_MESSAGE,
                    ),
                )
                is BridgeClearResult.VersionConflict -> refreshAfterConflict(client)
            }
            is BridgeClientResult.Failure -> finishClearFailure()
        }
    }

    private fun refreshAfterConflict(client: BridgeClient) {
        if (disposed) return
        when (val result = client.getSnapshot()) {
            is BridgeClientResult.Success -> finish(
                state.copy(
                    text = result.value.text,
                    version = result.value.version,
                    lastRefresh = clock(),
                    errorMessage = null,
                    feedbackMessage = VERSION_CONFLICT_MESSAGE,
                ),
            )
            is BridgeClientResult.Failure -> finishClearFailure()
        }
    }

    private fun beginTextOperation(requireVersion: Boolean): OperationStart? = synchronized(lock) {
        if (disposed || busy || state.text.isEmpty()) return@synchronized null
        if (requireVersion && state.version == null) return@synchronized null
        busy = true
        val snapshot = DisplayedTextSnapshot(text = state.text, version = state.version)
        val newState = state.copy(isBusy = true, errorMessage = null, feedbackMessage = null)
        OperationStart(snapshot, StateNotification(newState, publishLocked(newState)))
    }

    private fun connectBridge(): BridgeClientResult<TextSnapshot> {
        val client = bridgeClientFactory()
        val installed = synchronized(lock) {
            if (disposed) false else {
                activeBridgeClient = client
                true
            }
        }
        if (!installed) {
            client.close()
            return BridgeClientResult.Failure("Project has been disposed.", "DISPOSED")
        }

        val result = client.connect(object : BridgeClientEventListener {
            override fun onTextChanged(message: TextChanged) {
                submit { handleTextChanged(client, message) }
            }

            override fun onClosed(cause: Throwable?) {
                submit { handleBridgeClosed(client, cause) }
            }

            override fun onError(cause: Throwable) {
                submit { handleBridgeError(client, cause) }
            }
        })
        if (result is BridgeClientResult.Failure) {
            detachBridge(client)
            closeBridgeAsync(client)
        }
        return result
    }

    private fun handleTextChanged(client: BridgeClient, message: TextChanged) {
        val notification = synchronized(lock) {
            if (disposed || activeBridgeClient !== client) return
            if (state.text == message.text && state.version == message.version) return
            val newState = state.copy(
                connectionState = BridgeConnectionState.CONNECTED,
                serverStatus = "Online",
                text = message.text,
                version = message.version,
                lastRefresh = clock(),
                errorMessage = null,
                feedbackMessage = null,
            )
            StateNotification(newState, publishLocked(newState))
        }
        BridgeLog.textFetched(version = message.version, length = message.text.length)
        notifyListeners(notification)
    }

    private fun handleBridgeClosed(client: BridgeClient, cause: Throwable?) {
        if (!detachBridge(client)) return
        finish(
            state.copy(
                connectionState = BridgeConnectionState.SERVER_OFFLINE,
                serverStatus = "Offline",
                errorMessage = cause?.message ?: "TCP connection closed.",
                feedbackMessage = null,
            ),
        )
        closeBridgeAsync(client)
    }

    private fun handleBridgeError(client: BridgeClient, cause: Throwable) {
        if (!detachBridge(client)) return
        BridgeLog.failure("TCP connection", cause)
        finish(
            state.copy(
                connectionState = BridgeConnectionState.ERROR,
                serverStatus = "Offline",
                errorMessage = "TCP connection failed.",
                feedbackMessage = null,
            ),
        )
        closeBridgeAsync(client)
    }

    private fun finishConnected(snapshot: TextSnapshot) {
        BridgeLog.textFetched(version = snapshot.version, length = snapshot.text.length)
        finishSnapshot(snapshot)
    }

    private fun finishSnapshot(snapshot: TextSnapshot) {
        finish(
            state.copy(
                connectionState = BridgeConnectionState.CONNECTED,
                serverStatus = "Online",
                text = snapshot.text,
                version = snapshot.version,
                lastRefresh = clock(),
                errorMessage = null,
                feedbackMessage = null,
            ),
        )
    }

    private fun finishConnectionFailure(result: BridgeClientResult.Failure) {
        if (result.code == "TCP_CONNECTION_FAILED" || result.code == "TCP_CLOSED") {
            finish(
                state.copy(
                    connectionState = BridgeConnectionState.SERVER_OFFLINE,
                    serverStatus = "Offline",
                    errorMessage = result.message,
                    feedbackMessage = null,
                ),
            )
        } else {
            finishError(result.message)
        }
    }

    private fun finishClearFailure() {
        finish(
            state.copy(
                errorMessage = null,
                feedbackMessage = CLEAR_FAILURE_MESSAGE,
            ),
        )
    }

    private fun finishError(message: String) {
        finish(state.copy(connectionState = BridgeConnectionState.ERROR, errorMessage = message, feedbackMessage = null))
    }

    private fun finish(newState: BridgeState) {
        val notification = synchronized(lock) {
            if (disposed) return
            busy = false
            val finishedState = newState.copy(isBusy = false)
            StateNotification(finishedState, publishLocked(finishedState))
        }
        notifyListeners(notification)
    }

    private fun detachBridge(client: BridgeClient): Boolean = synchronized(lock) {
        if (activeBridgeClient !== client) return@synchronized false
        activeBridgeClient = null
        true
    }

    private fun closeActiveBridge() {
        val client = synchronized(lock) {
            val current = activeBridgeClient
            activeBridgeClient = null
            current
        }
        client?.let(::closeBridgeAsync)
    }

    private fun publish(newState: BridgeState) {
        val notification = synchronized(lock) {
            if (disposed) return
            StateNotification(newState, publishLocked(newState))
        }
        notifyListeners(notification)
    }

    private fun publishLocked(newState: BridgeState): List<(BridgeState) -> Unit> {
        state = newState
        return listeners.toList()
    }

    private fun notifyListeners(notification: StateNotification) {
        notification.listeners.forEach { it(notification.state) }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (exception: RuntimeException) {
            finishError("Background task could not be scheduled.")
        }
    }

    private fun closeBridgeAsync(client: BridgeClient) {
        val closeTask = Runnable {
            runCatching { client.close() }
                .onFailure { BridgeLog.failure("TCP client close", it) }
        }
        try {
            executor.execute(closeTask)
        } catch (exception: RuntimeException) {
            BridgeLog.failure("TCP client close scheduling", exception)
            Thread(closeTask, "input-bridge-tcp-close").apply {
                isDaemon = true
                start()
            }
        }
    }

    private companion object {
        const val CLIPBOARD_FAILURE_MESSAGE = "Clipboard write failed."
        const val CLEAR_FAILURE_MESSAGE = "Text was copied, but the phone content could not be cleared."
        const val VERSION_CONFLICT_MESSAGE =
            "Text was copied, but the phone content changed and was not cleared."
        const val COPIED_MESSAGE = "Copied"
        const val COPIED_AND_CLEARED_MESSAGE = "Copied and cleared"
    }
}
