package com.ericdevwang.inputbridge.plugin.toolwindow

import com.ericdevwang.inputbridge.plugin.adb.AdbDevice
import com.ericdevwang.inputbridge.plugin.connection.BridgeConnectionController
import com.ericdevwang.inputbridge.plugin.connection.BridgeConnectionState
import com.ericdevwang.inputbridge.plugin.connection.BridgeState
import com.ericdevwang.inputbridge.plugin.notifications.InputBridgeNotifier
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

class InputBridgePanel(
    private val controller: BridgeConnectionController,
    private val notifier: InputBridgeNotifier = InputBridgeNotifier { },
) : JPanel(BorderLayout(0, 12)), Disposable {
    internal val connectionIndicator = JLabel("●")
    internal val statusTextLabel = JLabel()
    internal val deviceLabel = JLabel()
    internal val deviceSelector = JComboBox<AdbDevice>()
    internal val lastSyncLabel = JLabel()
    internal val textArea = JTextArea()
    internal val contentPlaceholderLabel = JLabel("", SwingConstants.CENTER)
    internal val feedbackLabel = JLabel()
    internal val lengthLabel = JLabel()
    internal val versionLabel = JLabel()
    internal val copyButton = JButton("Copy")
    internal val copyAndClearButton = JButton("Copy & Clear")
    internal val reconnectButton = JButton("Reconnect")

    private val contentCardPanel = JPanel(CardLayout())
    private val lastSyncFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var disposed = false
    private var updatingDeviceSelector = false
    private var lastNotifiedFeedback: String? = null
    private val listener: (BridgeState) -> Unit = ::dispatchRender

    init {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(createHeader(), BorderLayout.NORTH)
        add(createContent(), BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)

        copyButton.addActionListener { controller.copy() }
        copyAndClearButton.addActionListener { controller.copyAndClear() }
        reconnectButton.addActionListener { controller.reconnect() }
        deviceSelector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                (value as? AdbDevice)?.displayName ?: value,
                index,
                isSelected,
                cellHasFocus,
            )
        }
        deviceSelector.addActionListener { _ ->
            if (!updatingDeviceSelector) {
                (deviceSelector.selectedItem as? AdbDevice)?.let { device ->
                    controller.selectDevice(device.serial)
                }
            }
        }
        controller.addListener(listener)
    }

    override fun dispose() {
        disposed = true
        controller.removeListener(listener)
    }

    private fun createHeader(): JPanel = JPanel(BorderLayout(0, 6)).apply {
        add(JLabel("Input Bridge").apply {
            font = font.deriveFont(Font.BOLD, 16f)
        }, BorderLayout.NORTH)
        add(JPanel(WrapLayout(FlowLayout.LEFT, 8, 4)).apply {
            connectionIndicator.toolTipText = "Connection state"
            add(connectionIndicator)
            add(statusTextLabel)
            add(deviceLabel)
            add(lastSyncLabel)
            add(deviceSelector)
        }, BorderLayout.CENTER)
    }

    private fun createContent(): JPanel {
        contentCardPanel.add(createPlaceholderContent(), PLACEHOLDER_CARD)
        contentCardPanel.add(createTextContent(), TEXT_CARD)
        return contentCardPanel
    }

    private fun createPlaceholderContent(): JPanel = JPanel(GridBagLayout()).apply {
        add(contentPlaceholderLabel, GridBagConstraints().apply {
            anchor = GridBagConstraints.CENTER
        })
    }

    private fun createTextContent(): JScrollPane {
        textArea.isEditable = false
        textArea.lineWrap = true
        textArea.wrapStyleWord = false
        textArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        textArea.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        return JScrollPane(textArea)
    }

    private fun createFooter(): JPanel = JPanel(BorderLayout(0, 8)).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(lengthLabel)
            add(Box.createHorizontalStrut(16))
            add(versionLabel)
        }, BorderLayout.NORTH)
        add(feedbackLabel, BorderLayout.CENTER)
        add(JPanel(WrapLayout(FlowLayout.LEFT, 8, 8)).apply {
            add(copyButton)
            add(copyAndClearButton)
            add(reconnectButton)
        }, BorderLayout.SOUTH)
    }

    private fun dispatchRender(state: BridgeState) {
        if (SwingUtilities.isEventDispatchThread()) {
            render(state)
        } else {
            SwingUtilities.invokeLater {
                if (!disposed) render(state)
            }
        }
    }

    private fun render(state: BridgeState) {
        val presentation = state.presentation()
        connectionIndicator.foreground = presentation.color
        connectionIndicator.toolTipText = presentation.label
        statusTextLabel.text = presentation.label
        deviceLabel.text = "Device: ${state.selectedDeviceName()}"
        lastSyncLabel.text = "Last synced: ${state.lastRefresh.formatForDisplay()}"
        lengthLabel.text = "Length: ${state.text.length}"
        versionLabel.text = "Version: ${state.version ?: "—"}"
        feedbackLabel.text = (state.feedbackMessage ?: state.errorMessage).orEmpty()
        feedbackLabel.foreground = if (state.errorMessage != null && state.feedbackMessage == null) {
            RECOVERY_FOREGROUND
        } else {
            currentUiColor("Label.foreground", feedbackLabel.foreground)
        }

        renderContent(state)
        renderDeviceSelector(state)
        renderActions(state)

        if (state.feedbackMessage != null && state.feedbackMessage != lastNotifiedFeedback) {
            notifier.notify(state.feedbackMessage)
        }
        lastNotifiedFeedback = state.feedbackMessage
    }

    private fun renderContent(state: BridgeState) {
        val cardLayout = contentCardPanel.layout as CardLayout
        if (state.text.isEmpty()) {
            textArea.text = ""
            contentPlaceholderLabel.text = if (state.lastRefresh == null) {
                "Waiting for the first sync…"
            } else {
                "No text available"
            }
            cardLayout.show(contentCardPanel, PLACEHOLDER_CARD)
        } else {
            textArea.text = state.text
            cardLayout.show(contentCardPanel, TEXT_CARD)
        }
    }

    private fun renderDeviceSelector(state: BridgeState) {
        updatingDeviceSelector = true
        try {
            val selectedSerial = state.selectedSerial
            deviceSelector.removeAllItems()
            state.devices.forEach(deviceSelector::addItem)
            deviceSelector.selectedItem = state.devices.firstOrNull { it.serial == selectedSerial }
        } finally {
            updatingDeviceSelector = false
        }
    }

    private fun renderActions(state: BridgeState) {
        val connected = state.connectionState == BridgeConnectionState.CONNECTED
        val hasText = state.text.isNotEmpty()
        copyButton.isEnabled = connected && hasText && !state.isBusy
        copyAndClearButton.isEnabled = connected && hasText && state.version != null && !state.isBusy
        reconnectButton.isEnabled = !state.isBusy
        deviceSelector.isEnabled = state.devices.isNotEmpty() && !state.isBusy

        val needsRecovery = state.connectionState in RECOVERY_STATES
        reconnectButton.putClientProperty(RECONNECT_HIGHLIGHT_PROPERTY, needsRecovery)
        reconnectButton.foreground = if (needsRecovery) {
            RECOVERY_FOREGROUND
        } else {
            currentUiColor("Button.foreground", reconnectButton.foreground)
        }
        reconnectButton.background = if (needsRecovery) {
            RECOVERY_BACKGROUND
        } else {
            currentUiColor("Button.background", reconnectButton.background)
        }
    }

    private fun currentUiColor(key: String, fallback: Color): Color = UIManager.getColor(key) ?: fallback

    private fun BridgeState.selectedDeviceName(): String =
        devices.firstOrNull { it.serial == selectedSerial }?.displayName ?: "No device selected"

    private fun java.time.Instant?.formatForDisplay(): String =
        this?.atZone(ZoneId.systemDefault())?.format(lastSyncFormatter) ?: "Never"

    private data class StatusPresentation(
        val label: String,
        val color: Color,
    )

    private fun BridgeState.presentation(): StatusPresentation = when (connectionState) {
        BridgeConnectionState.IDLE,
        BridgeConnectionState.FORWARDING,
        -> StatusPresentation("Connecting…", CONNECTING_FOREGROUND)

        BridgeConnectionState.CONNECTED -> StatusPresentation("Connected", CONNECTED_FOREGROUND)

        BridgeConnectionState.ADB_UNAVAILABLE,
        BridgeConnectionState.NO_DEVICE,
        BridgeConnectionState.SERVER_OFFLINE,
        -> StatusPresentation("Offline", RECOVERY_FOREGROUND)

        BridgeConnectionState.ERROR -> StatusPresentation("Error", RECOVERY_FOREGROUND)
    }

    private companion object {
        const val PLACEHOLDER_CARD = "placeholder"
        const val TEXT_CARD = "text"
        const val RECONNECT_HIGHLIGHT_PROPERTY = "inputBridge.reconnectHighlight"
        val RECOVERY_STATES = setOf(
            BridgeConnectionState.ADB_UNAVAILABLE,
            BridgeConnectionState.NO_DEVICE,
            BridgeConnectionState.SERVER_OFFLINE,
            BridgeConnectionState.ERROR,
        )
        val CONNECTING_FOREGROUND = JBColor(Color(0x8A5A00), Color(0xE6C16A))
        val CONNECTED_FOREGROUND = JBColor(Color(0x1B6E3B), Color(0x7EE2A8))
        val RECOVERY_FOREGROUND = JBColor(Color(0xB3261E), Color(0xFFB4AB))
        val RECOVERY_BACKGROUND = JBColor(Color(0xF9DEDC), Color(0x601410))
    }
}
