package com.ericdevwang.inputbridge.core.framing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LengthPrefixedFramingTest {
    @Test
    fun readsCombinedFramesAndUtf8Payload() {
        val output = ByteArrayOutputStream()
        val writer = LengthPrefixedFrameWriter(output)
        writer.write("中文\n😀".toByteArray())
        writer.write("second".toByteArray())

        val reader = LengthPrefixedFrameReader(ByteArrayInputStream(output.toByteArray()))
        assertEquals("中文\n😀", reader.read()!!.decodeToString())
        assertEquals("second", reader.read()!!.decodeToString())
        assertNull(reader.read())
    }

    @Test
    fun readsFrameWhenInputArrivesInSmallChunks() {
        val encoded = ByteArrayOutputStream().also { LengthPrefixedFrameWriter(it).write(byteArrayOf(1, 2, 3, 4)) }
            .toByteArray()
        val input = ChunkedInputStream(encoded, chunkSize = 1)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), LengthPrefixedFrameReader(input).read())
    }

    @Test(expected = InvalidFrameException::class)
    fun rejectsFrameLargerThanConfiguredLimitBeforeAllocation() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).writeInt(9)

        LengthPrefixedFrameReader(ByteArrayInputStream(output.toByteArray()), maxFrameBytes = 8).read()
    }

    @Test(expected = InvalidFrameException::class)
    fun rejectsWriterPayloadLargerThanConfiguredLimit() {
        LengthPrefixedFrameWriter(ByteArrayOutputStream(), maxFrameBytes = 8).write(ByteArray(9))
    }

    @Test
    fun serializesConcurrentWritesAsCompleteFrames() {
        val output = ByteArrayOutputStream()
        val writer = LengthPrefixedFrameWriter(output)
        val done = CountDownLatch(20)
        repeat(20) { index ->
            thread {
                writer.write("frame-$index".toByteArray())
                done.countDown()
            }
        }
        done.await()

        val reader = LengthPrefixedFrameReader(ByteArrayInputStream(output.toByteArray()))
        val frames = buildSet {
            repeat(20) { add(reader.read()!!.decodeToString()) }
        }
        assertEquals((0 until 20).map { "frame-$it" }.toSet(), frames)
    }
}

private class ChunkedInputStream(
    private val bytes: ByteArray,
    private val chunkSize: Int,
) : ByteArrayInputStream(bytes) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, minOf(length, chunkSize))
}
