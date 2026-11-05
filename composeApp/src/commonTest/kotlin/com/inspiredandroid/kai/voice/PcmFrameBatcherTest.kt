package com.inspiredandroid.kai.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmFrameBatcherTest {
    @Test
    fun completeBatchesPreserveCaptureOrderAndDrainKeepsTheTail() {
        val batcher = PcmFrameBatcher(frameSamples = 2, framesPerBatch = 3)

        assertEquals(null, batcher.append(shortArrayOf(1, 2)))
        assertEquals(null, batcher.append(shortArrayOf(3, 4)))
        assertContentEquals(
            shortArrayOf(1, 2, 3, 4, 5, 6),
            batcher.append(shortArrayOf(5, 6)),
        )
        assertEquals(0, batcher.pendingSampleCount)

        assertEquals(null, batcher.append(shortArrayOf(7, 8)))
        assertEquals(2, batcher.pendingSampleCount)
        assertContentEquals(shortArrayOf(7, 8), batcher.drain())
        assertEquals(0, batcher.pendingSampleCount)
        assertEquals(null, batcher.drain())
    }

    @Test
    fun rejectsUnexpectedFrameSizesInsteadOfTruncatingOrPadding() {
        val batcher = PcmFrameBatcher(frameSamples = 320, framesPerBatch = 5)

        assertFailsWith<IllegalArgumentException> {
            batcher.append(shortArrayOf(1, 2, 3))
        }
        assertEquals(0, batcher.pendingSampleCount)
    }
}
