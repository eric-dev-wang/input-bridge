package com.ericdevwang.inputbridge.plugin.adb

import com.ericdevwang.inputbridge.plugin.connection.BridgeNetworkConfig
import com.ericdevwang.inputbridge.plugin.logging.BridgeLog
import java.nio.file.Path

data class PortForward(
    val serial: String,
    val localPort: Int,
    val remotePort: Int,
)

interface AdbClient {
    fun devices(): AdbResult<List<AdbDevice>>

    fun listForwards(): AdbResult<List<PortForward>>

    fun createForward(serial: String): AdbResult<Unit>

    fun removeForward(serial: String): AdbResult<Unit>
}

class ProcessAdbClient(
    private val adbPath: Path,
    private val commandRunner: AdbCommandRunner = ProcessAdbCommandRunner(adbPath),
) : AdbClient {
    private val deviceNameResolver = AdbDeviceNameResolver(commandRunner)

    override fun devices(): AdbResult<List<AdbDevice>> =
        runCommand(listOf("devices", "-l")) {
            AdbDevicesParser.parse(it.stdout).map(deviceNameResolver::resolve)
        }

    override fun listForwards(): AdbResult<List<PortForward>> =
        runCommand(listOf("forward", "--list")) { parseForwards(it.stdout) }

    override fun createForward(serial: String): AdbResult<Unit> =
        runCommand(listOf("-s", serial, "forward", "tcp:${BridgeNetworkConfig.PORT}", "tcp:${BridgeNetworkConfig.PORT}")) { }

    override fun removeForward(serial: String): AdbResult<Unit> =
        runCommand(listOf("-s", serial, "forward", "--remove", "tcp:${BridgeNetworkConfig.PORT}")) { }

    private fun <T> runCommand(
        command: List<String>,
        transform: (AdbCommandResult) -> T,
    ): AdbResult<T> {
        val result = commandRunner.run(command)
        BridgeLog.adbCommand(command.firstOrNull().orEmpty(), result.exitCode, result.timedOut)
        if (result.timedOut || result.exitCode != 0) {
            return AdbResult.Failure(
                AdbError(
                    message = if (result.timedOut) {
                        "ADB command timed out after ${BridgeNetworkConfig.ADB_TIMEOUT_SECONDS} seconds."
                    } else {
                        "ADB command failed with exit code ${result.exitCode ?: "unknown"}."
                    },
                    command = command,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    timedOut = result.timedOut,
                ),
            )
        }
        return AdbResult.Success(transform(result))
    }

    private fun parseForwards(output: String): List<PortForward> = output.lineSequence()
        .mapNotNull { line ->
            val columns = line.trim().split(WHITESPACE)
            if (columns.size < 3) return@mapNotNull null
            val localPort = columns[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
            val remotePort = columns[2].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
            PortForward(columns[0], localPort, remotePort)
        }
        .toList()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
