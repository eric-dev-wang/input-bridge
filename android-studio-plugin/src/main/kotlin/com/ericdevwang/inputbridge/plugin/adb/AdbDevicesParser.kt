package com.ericdevwang.inputbridge.plugin.adb

object AdbDevicesParser {
    private val WHITESPACE = Regex("\\s+")

    fun parse(output: String): List<AdbDevice> = output.lineSequence()
        .mapNotNull(::parseLine)
        .toList()

    private fun parseLine(line: String): AdbDevice? {
        val columns = line.trim().split(WHITESPACE)
        if (columns.size < 2 || columns[1] != "device") return null

        val model = columns.drop(2)
            .firstOrNull { it.startsWith("model:") }
            ?.removePrefix("model:")
            ?.replace('_', ' ')

        return AdbDevice(serial = columns[0], model = model)
    }
}
