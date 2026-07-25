package com.ericdevwang.inputbridge.core.framing

import java.io.DataInputStream
import java.io.EOFException

class LengthPrefixedFrameReader(
    input: java.io.InputStream,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    private val dataInput = DataInputStream(input)

    /** Returns null when the peer has cleanly closed the stream. */
    fun read(): ByteArray? {
        val length = try {
            dataInput.readInt()
        } catch (_: EOFException) {
            return null
        }

        if (length < 0 || length > maxFrameBytes) {
            throw InvalidFrameException("Frame length $length exceeds limit $maxFrameBytes")
        }

        return ByteArray(length).also(dataInput::readFully)
    }
}
