package com.ericdevwang.inputbridge.plugin.service

import com.ericdevwang.inputbridge.plugin.adb.AdbLocator
import com.ericdevwang.inputbridge.plugin.adb.ProcessAdbClient
import com.ericdevwang.inputbridge.plugin.adb.RandomDeviceSelector
import com.ericdevwang.inputbridge.plugin.clipboard.IntellijClipboardWriter
import com.ericdevwang.inputbridge.plugin.connection.BridgeConnectionController
import com.ericdevwang.inputbridge.plugin.connection.BridgeConnectionCoordinator
import com.ericdevwang.inputbridge.plugin.connection.JdkBridgeClient
import com.ericdevwang.inputbridge.plugin.notifications.InputBridgeNotifier
import com.ericdevwang.inputbridge.plugin.notifications.IntelliJInputBridgeNotifier
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

class InputBridgeProjectService(
    project: Project,
) : Disposable {
    val notifier: InputBridgeNotifier = IntelliJInputBridgeNotifier(project)

    val connectionController: BridgeConnectionController = BridgeConnectionCoordinator(
        adbLocator = AdbLocator.forProject(project),
        adbClientFactory = { adbPath -> ProcessAdbClient(adbPath) },
        bridgeClientFactory = { JdkBridgeClient.create() },
        deviceSelector = RandomDeviceSelector(),
        executor = AppExecutorUtil.getAppExecutorService(),
        clipboardWriter = IntellijClipboardWriter(),
    )

    override fun dispose() {
        connectionController.dispose()
    }
}
