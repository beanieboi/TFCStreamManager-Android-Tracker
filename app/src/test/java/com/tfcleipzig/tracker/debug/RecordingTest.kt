package com.tfcleipzig.tracker.debug

import com.tfcleipzig.tracker.detection.Frame
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTest {
    @Test
    fun packAndUnpackKeepEveryPixelOfAPaddedInterleavedFrame() {
        // Like CameraX can deliver: rows padded, U and V interleaved (pixel stride 2).
        val w = 8
        val h = 4
        val yStride = 12
        val uvStride = 10
        val y = ByteArray(yStride * h) { (it * 7).toByte() }
        val uv = ByteArray(uvStride * h / 2) { (it * 13 + 1).toByte() }
        val frame = Frame(w, h, y, yStride, uv, uv.copyOfRange(1, uv.size), uvStride, 2)

        val back = Recording.unpack(Recording.pack(frame), w, h)

        for (yy in 0 until h) for (x in 0 until w) assertEquals(frame.luma(x, yy), back.luma(x, yy))
        for (cy in 0 until h / 2) {
            for (cx in 0 until w / 2) {
                assertEquals(frame.u(cx, cy), back.u(cx, cy))
                assertEquals(frame.v(cx, cy), back.v(cx, cy))
            }
        }
    }
}
