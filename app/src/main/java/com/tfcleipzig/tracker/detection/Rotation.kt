package com.tfcleipzig.tracker.detection

/**
 * How far to turn the camera frame, clockwise, so the counters look the way [TableEndReader]
 * expects: both bars side by side, set bar left, goal bar right. Depends on how the phone hangs,
 * so calibration finds it ([com.tfcleipzig.tracker.calibration.Calibrator]).
 */
enum class Rotation(
    val degrees: Int,
) {
    R0(0),
    R90(90),
    R180(180),
    R270(270),
    ;

    /**
     * A point in the turned frame, as fractions (0..1) of its width and height, back in the
     * camera frame (also 0..1): for camera controls like the focus region.
     */
    fun toCamera(
        u: Float,
        v: Float,
    ): Pair<Float, Float> =
        when (this) {
            R0 -> u to v
            R90 -> v to 1f - u
            R180 -> 1f - u to 1f - v
            R270 -> 1f - v to u
        }

    /** Width and height of a [width]×[height] frame after turning. */
    fun size(
        width: Int,
        height: Int,
    ): Pair<Int, Int> = if (this == R90 || this == R270) height to width else width to height
}

/**
 * Turns [Frame]s, reusing one set of buffers per rotation, so frames for different rotations of
 * the same camera frame can be held at once. A turned frame is valid until the next [rotate] to
 * the same rotation. Chroma comes out planar.
 */
class FrameRotator {
    private val buffers = mutableMapOf<Rotation, Array<ByteArray>>()

    fun rotate(
        frame: Frame,
        rotation: Rotation,
    ): Frame {
        if (rotation == Rotation.R0) return frame
        val (w, h) = rotation.size(frame.width, frame.height)
        val cw = frame.width / 2
        val ch = frame.height / 2
        val (rcw, rch) = rotation.size(cw, ch)
        val b = buffers.getOrPut(rotation) { arrayOf(ByteArray(0), ByteArray(0), ByteArray(0)) }
        if (b[0].size != w * h) b[0] = ByteArray(w * h)
        if (b[1].size != rcw * rch) {
            b[1] = ByteArray(rcw * rch)
            b[2] = ByteArray(rcw * rch)
        }
        turn(frame.y, frame.width, frame.height, frame.yRowStride, 1, b[0], rotation)
        turn(frame.u, cw, ch, frame.uvRowStride, frame.uvPixelStride, b[1], rotation)
        turn(frame.v, cw, ch, frame.uvRowStride, frame.uvPixelStride, b[2], rotation)
        return Frame(w, h, b[0], w, b[1], b[2], rcw, 1)
    }

    private companion object {
        /** Turns a [w]×[h] plane clockwise by [rotation] into the packed [dst]. */
        fun turn(
            src: ByteArray,
            w: Int,
            h: Int,
            rowStride: Int,
            pixelStride: Int,
            dst: ByteArray,
            rotation: Rotation,
        ) {
            when (rotation) {
                Rotation.R0 -> {
                    for (y in 0 until h) for (x in 0 until w) dst[y * w + x] = src[y * rowStride + x * pixelStride]
                }
                // Destination is h wide: its (dx, dy) shows the source's (dy, h - 1 - dx).
                Rotation.R90 -> {
                    for (sy in 0 until h) {
                        val dx = h - 1 - sy
                        for (sx in 0 until w) dst[sx * h + dx] = src[sy * rowStride + sx * pixelStride]
                    }
                }
                Rotation.R180 -> {
                    val last = w * h - 1
                    for (sy in 0 until h) {
                        for (sx in 0 until w) dst[last - (sy * w + sx)] = src[sy * rowStride + sx * pixelStride]
                    }
                }
                // Destination is h wide: its (dx, dy) shows the source's (w - 1 - dy, dx).
                Rotation.R270 -> {
                    for (sy in 0 until h) {
                        for (sx in 0 until w) dst[(w - 1 - sx) * h + sy] = src[sy * rowStride + sx * pixelStride]
                    }
                }
            }
        }
    }
}
