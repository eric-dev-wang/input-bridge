package com.ericdevwang.inputbridge.plugin.connection

import com.ericdevwang.inputbridge.plugin.adb.AdbClient
import com.ericdevwang.inputbridge.plugin.adb.AdbDevice
import com.ericdevwang.inputbridge.plugin.adb.AdbLocator
import com.ericdevwang.inputbridge.plugin.adb.AdbResult
import com.ericdevwang.inputbridge.plugin.adb.DeviceSelector
import com.ericdevwang.inputbridge.plugin.adb.PortForward
import com.ericdevwang.inputbridge.plugin.clipboard.ClipboardWriteResult
import com.ericdevwang.inputbridge.plugin.clipboard.ClipboardWriter
import com.ericdevwang.inputbridge.protocol.TextChanged
import com.ericdevwang.inputbridge.protocol.TextSnapshot
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConnectionCoordinatorTest {
    @Test
    fun reconnectDiscoversDeviceForwardsAndPublishesConnectedState() {
        val device = AdbDevice("serial", "Pixel 8")
        val adb = FakeAdbClient(deviceLists = ArrayDeque(listOf(listOf(device))))
        val client = RecordingBridgeClient()
        val coordinator = newCoordinator(adb = adb, client = client)

        coordinator.reconnect()

        assertEquals(BridgeConnectionState.CONNECTED, coordinator.state.connectionState)
        assertEquals("serial", coordinator.state.selectedSerial)
        assertEquals("你好", coordinator.state.text)
        assertTrue(adb.actions.contains("create:serial"))
        assertEquals(1, client.connectCalls)
        assertFalse(coordinator.state.isBusy)
    }

    @Test
    fun reconnectRebuildsForwardOnceAfterTCPConnectionFailure() {
        val device = AdbDevice("serial", "Pixel 8")
        val adb = FakeAdbClient(deviceLists = ArrayDeque(listOf(listOf(device))))
        val clients = ArrayDeque(
            listOf(
                RecordingBridgeClient(
                    connectResult = BridgeClientResult.Failure(
                        message = "connection refused",
                        code = "TCP_CONNECTION_FAILED",
                    ),
                ),
                RecordingBridgeClient(),
            ),
        )
        val coordinator = newCoordinator(adb = adb, clientFactory = { clients.removeFirst() })

        coordinator.reconnect()

        assertEquals(BridgeConnectionState.CONNECTED, coordinator.state.connectionState)
        assertEquals(listOf("create:serial", "remove:serial", "create:serial"), adb.actions)
    }

    @Test
    fun pushedTextChangedUpdatesDisplayedStateWithoutPolling() {
        val client = RecordingBridgeClient()
        val coordinator = newCoordinator(client = client)
        coordinator.reconnect()

        client.emit(TextChanged("live update", 18L, 101L))

        assertEquals("live update", coordinator.state.text)
        assertEquals(18L, coordinator.state.version)
        assertEquals(Instant.parse("2026-07-13T12:00:00Z"), coordinator.state.lastRefresh)
    }

    @Test
    fun tcpErrorTransitionsToErrorState() {
        val client = RecordingBridgeClient()
        val coordinator = newCoordinator(client = client)
        coordinator.reconnect()

        client.emitError(IllegalStateException("socket failed"))

        assertEquals(BridgeConnectionState.ERROR, coordinator.state.connectionState)
        assertEquals("Offline", coordinator.state.serverStatus)
        assertEquals("TCP connection failed.", coordinator.state.errorMessage)
    }

    @Test
    fun lateTextChangedFromPreviousTCPIsIgnoredAfterReconnect() {
        val firstClient = RecordingBridgeClient()
        val secondClient = RecordingBridgeClient()
        val clients = ArrayDeque(listOf(firstClient, secondClient))
        val coordinator = newCoordinator(clientFactory = { clients.removeFirst() })

        coordinator.reconnect()
        coordinator.reconnect()

        firstClient.emit(TextChanged("stale", 99L, 999L))
        assertEquals("你好", coordinator.state.text)

        secondClient.emit(TextChanged("current", 18L, 101L))
        assertEquals("current", coordinator.state.text)
        assertEquals(18L, coordinator.state.version)
    }

    @Test
    fun repeatedTextChangedDoesNotClearCopyAndClearFeedback() {
        val client = RecordingBridgeClient()
        val coordinator = newCoordinator(client = client)
        coordinator.reconnect()

        coordinator.copyAndClear()
        client.emit(TextChanged("", 18L, 101L))

        assertEquals("Copied and cleared", coordinator.state.feedbackMessage)
    }

    @Test
    fun reconnectUsesSelectorWhenCurrentDeviceIsUnavailable() {
        val first = AdbDevice("first", "First")
        val second = AdbDevice("second", "Second")
        val adb = FakeAdbClient(deviceLists = ArrayDeque(listOf(listOf(first), listOf(second))))
        val coordinator = newCoordinator(
            adb = adb,
            deviceSelector = DeviceSelector { it.last() },
        )

        coordinator.reconnect()
        coordinator.reconnect()

        assertEquals("second", coordinator.state.selectedSerial)
        assertEquals(listOf("create:first", "remove:first", "create:second"), adb.actions)
    }

    @Test
    fun allBridgeActionsAreIgnoredWhileCopyAndClearIsBusy() {
        val executor = QueueAfterFirstExecutor()
        val adb = FakeAdbClient(deviceLists = ArrayDeque(listOf(listOf(AdbDevice("serial", "Pixel 8")))))
        val client = RecordingBridgeClient()
        val coordinator = newCoordinator(adb = adb, client = client, executor = executor)
        coordinator.reconnect()
        val actionCount = adb.actions.size

        coordinator.copyAndClear()
        coordinator.reconnect()
        coordinator.selectDevice("serial")

        assertTrue(coordinator.state.isBusy)
        assertEquals(actionCount, adb.actions.size)
        assertEquals(1, executor.queuedTasks.size)
    }

    @Test
    fun disposedCoordinatorIgnoresLateCopyResult() {
        val executor = QueueAfterFirstExecutor()
        val clipboard = RecordingClipboardWriter()
        val coordinator = newCoordinator(
            client = RecordingBridgeClient(),
            clipboardWriter = clipboard,
            executor = executor,
        )
        coordinator.reconnect()
        coordinator.copy()
        coordinator.dispose()

        executor.runNext()

        assertTrue(clipboard.writes.isEmpty())
    }

    @Test
    fun copyUsesDisplayedSnapshotAndDoesNotSendTCPClear() {
        val client = RecordingBridgeClient()
        val clipboard = RecordingClipboardWriter()
        val coordinator = newCoordinator(client = client, clipboardWriter = clipboard)
        coordinator.reconnect()

        coordinator.copy()

        assertEquals(listOf("你好"), clipboard.writes)
        assertEquals(0, client.clearCalls)
        assertEquals("Copied", coordinator.state.feedbackMessage)
    }

    @Test
    fun copyFailurePreventsClear() {
        val client = RecordingBridgeClient()
        val clipboard = RecordingClipboardWriter(ClipboardWriteResult.Failure("Clipboard write failed."))
        val coordinator = newCoordinator(client = client, clipboardWriter = clipboard)
        coordinator.reconnect()

        coordinator.copyAndClear()

        assertEquals(0, client.clearCalls)
        assertEquals("你好", coordinator.state.text)
        assertEquals("Clipboard write failed.", coordinator.state.feedbackMessage)
    }

    @Test
    fun clearFailurePreservesCopiedText() {
        val client = RecordingBridgeClient(
            clearResult = BridgeClientResult.Failure("clear failed", "TEXT_CLEAR_FAILED"),
        )
        val coordinator = newCoordinator(client = client, clipboardWriter = RecordingClipboardWriter())

        coordinator.reconnect()
        coordinator.copyAndClear()

        assertEquals(1, client.clearCalls)
        assertEquals("你好", coordinator.state.text)
        assertEquals("Text was copied, but the phone content could not be cleared.", coordinator.state.feedbackMessage)
    }

    @Test
    fun copyAndClearWritesClipboardBeforeSendingClear() {
        val events = mutableListOf<String>()
        val client = RecordingBridgeClient(events = events)
        val coordinator = newCoordinator(
            client = client,
            clipboardWriter = RecordingClipboardWriter(events = events),
        )
        coordinator.reconnect()

        coordinator.copyAndClear()

        assertEquals(listOf("clipboard:你好", "clear:17"), events)
        assertEquals("", coordinator.state.text)
        assertEquals(18L, coordinator.state.version)
        assertEquals("Copied and cleared", coordinator.state.feedbackMessage)
    }

    @Test
    fun versionConflictRefreshesTextAndPreservesCopiedSnapshot() {
        val client = RecordingBridgeClient(
            clearResult = BridgeClientResult.Success(BridgeClearResult.VersionConflict(18L)),
            snapshotResult = BridgeClientResult.Success(TextSnapshot("new text", 18L, 102L)),
        )
        val coordinator = newCoordinator(client = client, clipboardWriter = RecordingClipboardWriter())
        coordinator.reconnect()

        coordinator.copyAndClear()

        assertEquals(1, client.clearCalls)
        assertEquals(1, client.snapshotCalls)
        assertEquals("new text", coordinator.state.text)
        assertEquals(18L, coordinator.state.version)
        assertEquals(
            "Text was copied, but the phone content changed and was not cleared.",
            coordinator.state.feedbackMessage,
        )
    }

    @Test
    fun closedTCPTransitionsToServerOffline() {
        val client = RecordingBridgeClient()
        val coordinator = newCoordinator(client = client)
        coordinator.reconnect()

        client.emitClosed()

        assertEquals(BridgeConnectionState.SERVER_OFFLINE, coordinator.state.connectionState)
        assertEquals("Offline", coordinator.state.serverStatus)
        assertFalse(coordinator.state.isBusy)
    }

    @Test
    fun disposeClosesTCPOffCallingThread() {
        val client = RecordingBridgeClient()
        val executor = SwitchingExecutor()
        val coordinator = newCoordinator(client = client, executor = executor)
        coordinator.reconnect()
        executor.runAsync = true
        val disposingThread = Thread.currentThread()

        coordinator.dispose()

        assertTrue(client.closeLatch.await(1, TimeUnit.SECONDS))
        assertTrue(client.closeThread !== disposingThread)
    }

    private fun newCoordinator(
        adb: FakeAdbClient = FakeAdbClient(
            deviceLists = ArrayDeque(listOf(listOf(AdbDevice("serial", "Pixel 8")))),
        ),
        client: RecordingBridgeClient = RecordingBridgeClient(),
        clientFactory: () -> BridgeClient = { client },
        executor: Executor = Executor { it.run() },
        clipboardWriter: ClipboardWriter = RecordingClipboardWriter(),
        deviceSelector: DeviceSelector = DeviceSelector { it.first() },
    ): BridgeConnectionCoordinator = BridgeConnectionCoordinator(
        adbLocator = AdbLocator(
            configuredAdbProvider = { Path.of("/adb") },
            isUsable = { true },
        ),
        adbClientFactory = { adb },
        bridgeClientFactory = clientFactory,
        deviceSelector = deviceSelector,
        executor = executor,
        clipboardWriter = clipboardWriter,
        clock = { Instant.parse("2026-07-13T12:00:00Z") },
    )

    private class FakeAdbClient(
        private val deviceLists: ArrayDeque<List<AdbDevice>>,
    ) : AdbClient {
        val actions = mutableListOf<String>()

        override fun devices(): AdbResult<List<AdbDevice>> =
            AdbResult.Success(if (deviceLists.size > 1) deviceLists.removeFirst() else deviceLists.first())

        override fun listForwards(): AdbResult<List<PortForward>> = AdbResult.Success(
            actions.lastOrNull()?.let { action ->
                if (action.startsWith("create:")) {
                    listOf(PortForward(action.removePrefix("create:"), BridgeNetworkConfig.PORT, BridgeNetworkConfig.PORT))
                } else {
                    emptyList()
                }
            }.orEmpty(),
        )

        override fun createForward(serial: String): AdbResult<Unit> {
            actions += "create:$serial"
            return AdbResult.Success(Unit)
        }

        override fun removeForward(serial: String): AdbResult<Unit> {
            actions += "remove:$serial"
            return AdbResult.Success(Unit)
        }
    }

    private class RecordingClipboardWriter(
        private val result: ClipboardWriteResult = ClipboardWriteResult.Success,
        private val events: MutableList<String>? = null,
    ) : ClipboardWriter {
        val writes = mutableListOf<String>()

        override fun write(text: String): ClipboardWriteResult {
            writes += text
            events?.add("clipboard:$text")
            return result
        }
    }

    private class RecordingBridgeClient(
        private val initialSnapshot: TextSnapshot = TextSnapshot("你好", 17L, 100L),
        private val clearResult: BridgeClientResult<BridgeClearResult> =
            BridgeClientResult.Success(BridgeClearResult.Cleared(17L, 18L)),
        private val snapshotResult: BridgeClientResult<TextSnapshot> =
            BridgeClientResult.Success(initialSnapshot),
        private val connectResult: BridgeClientResult<TextSnapshot> =
            BridgeClientResult.Success(initialSnapshot),
        private val events: MutableList<String>? = null,
    ) : BridgeClient {
        var connectCalls = 0
        var snapshotCalls = 0
        var clearCalls = 0
        var closeThread: Thread? = null
        val closeLatch = CountDownLatch(1)
        private var listener: BridgeClientEventListener? = null

        override fun connect(listener: BridgeClientEventListener): BridgeClientResult<TextSnapshot> {
            connectCalls++
            this.listener = listener
            return connectResult
        }

        override fun getSnapshot(): BridgeClientResult<TextSnapshot> {
            snapshotCalls++
            return snapshotResult
        }

        override fun clearText(expectedVersion: Long): BridgeClientResult<BridgeClearResult> {
            clearCalls++
            events?.add("clear:$expectedVersion")
            return clearResult
        }

        fun emit(message: TextChanged) {
            listener?.onTextChanged(message)
        }

        fun emitClosed() {
            listener?.onClosed(null)
        }

        fun emitError(cause: Throwable) {
            listener?.onError(cause)
        }

        override fun close() {
            closeThread = Thread.currentThread()
            closeLatch.countDown()
        }
    }

    private class SwitchingExecutor : Executor {
        var runAsync = false

        override fun execute(command: Runnable) {
            if (runAsync) {
                Thread(command, "bridge-test-executor").start()
            } else {
                command.run()
            }
        }
    }

    private class QueueAfterFirstExecutor : Executor {
        private var executions = 0
        val queuedTasks = ArrayDeque<() -> Unit>()

        override fun execute(command: Runnable) {
            if (executions++ == 0) {
                command.run()
            } else {
                queuedTasks += command::run
            }
        }

        fun runNext() {
            queuedTasks.removeFirst().invoke()
        }
    }
}
