package com.tfcleipzig.tracker.detection

import com.tfcleipzig.tracker.reference.TestFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class RotationTest {
    /**
     * 4×2 luma, 2×1 chroma, interleaved (pixel stride 2) with padded rows like the camera's:
     * ```
     * 1 2 3 4
     * 5 6 7 8
     * ```
     */
    private fun small(): Frame {
        val y = byteArrayOf(1, 2, 3, 4, 0, 5, 6, 7, 8, 0)
        val u = byteArrayOf(10, 0, 20, 0)
        val v = byteArrayOf(30, 0, 40, 0)
        return Frame(4, 2, y, 5, u, v, 4, 2)
    }

    private fun Frame.lumaRows() = (0 until height).map { r -> (0 until width).map { luma(it, r) } }

    private fun Frame.uRow() = (0 until height / 2).flatMap { r -> (0 until width / 2).map { u(it, r) } }

    @Test
    fun turnsClockwise() {
        val rotator = FrameRotator()
        val f = small()
        assertEquals(listOf(listOf(5, 1), listOf(6, 2), listOf(7, 3), listOf(8, 4)), rotator.rotate(f, Rotation.R90).lumaRows())
        assertEquals(listOf(listOf(8, 7, 6, 5), listOf(4, 3, 2, 1)), rotator.rotate(f, Rotation.R180).lumaRows())
        assertEquals(listOf(listOf(4, 8), listOf(3, 7), listOf(2, 6), listOf(1, 5)), rotator.rotate(f, Rotation.R270).lumaRows())
        assertEquals(listOf(20, 10), rotator.rotate(f, Rotation.R180).uRow())
        assertEquals(40, rotator.rotate(f, Rotation.R180).v(0, 0))
        assertEquals(listOf(10, 20), rotator.rotate(f, Rotation.R90).uRow()) // 1×2 chroma: column becomes rows
    }

    @Test
    fun turningBackGivesTheSameFrame() {
        val f = small()
        for (r in Rotation.entries) {
            val back = Rotation.entries[(4 - r.ordinal) % 4]
            val there = FrameRotator().rotate(f, r)
            val again = FrameRotator().rotate(there, back)
            assertEquals(f.lumaRows(), again.lumaRows())
            assertEquals(f.uRow(), again.uRow())
        }
    }

    /** A phone hung upside down: unreadable as is, and reads as the upright photo once turned. */
    @Test
    fun readsAnUpsideDownPhoto() {
        val photos = TestFrames.referencePhotos()
        assumeTrue("no reference photos", photos.isNotEmpty())
        val reader = TableEndReader()
        // A photo the reader gets right upright.
        val (upright, expected) =
            photos
                .asSequence()
                .map { (photo, file) -> Triple(photo, TestFrames.frame(TestFrames.scaled(file)), photo.expected.score) }
                .map { (_, frame, score) -> Triple(frame, reader.read(frame), score) }
                .first { (_, reading, score) -> reading.goals == score }
                .let { (frame, reading, _) -> frame to reading }
        val upsideDown = FrameRotator().rotate(upright, Rotation.R180)

        val asIs = reader.read(upsideDown)
        assertNull("upside down must not read: $asIs", asIs.goals)
        val turned = FrameRotator().rotate(upsideDown, Rotation.R180)
        assertArrayEquals(upright.y, turned.y)
        assertEquals(expected, reader.read(turned))
    }
}

class RotationToCameraTest {
    /** Mark one pixel, turn the frame, and map the marked pixel's turned position back. */
    @Test
    fun mapsTurnedPointsBack() {
        val w = 8
        val h = 4
        val y = ByteArray(w * h).also { it[1 * w + 6] = 99 } // camera (6, 1)
        val frame = Frame(w, h, y, w, ByteArray(w * h / 4), ByteArray(w * h / 4), w / 2, 1)
        for (r in Rotation.entries) {
            val t = FrameRotator().rotate(frame, r)
            val i = t.y.indexOfFirst { it.toInt() == 99 }
            val (u, v) = r.toCamera((i % t.width + 0.5f) / t.width, (i / t.width + 0.5f) / t.height)
            assertEquals("$r x", 6.5f / w, u, 1e-6f)
            assertEquals("$r y", 1.5f / h, v, 1e-6f)
        }
    }
}
