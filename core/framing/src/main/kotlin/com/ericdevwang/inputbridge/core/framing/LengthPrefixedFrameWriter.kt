package com.ericdevwang.inputbridge.core.framing

import java.io.DataOutputStream

class LengthPrefixedFrameWriter(
    output: java.io.OutputStream,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    private val dataOutput = DataOutputStream(output)

    @Synchronized
    fun write(frame: ByteArray) {
        if (frame.size > maxFrameBytes) {
            throw InvalidFrameException("Frame length ${frame.size} exceeds limit $maxFrameBytes")
        }

        dataOutput.writeInt(frame.size)
        dataOutput.write(frame)
        dataOutput.flush()
    }
}
