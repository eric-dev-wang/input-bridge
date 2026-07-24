package com.ericdevwang.inputbridge.core.framing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

const val DEFAULT_MAX_FRAME_BYTES = 512 * 1024

class InvalidFrameException(message: String) : IOException(message)

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
