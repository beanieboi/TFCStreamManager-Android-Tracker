package com.tfcleipzig.tracker.detection

/**
 * A YUV 4:2:0 frame as CameraX delivers it (`YUV_420_888`): full-resolution luma, chroma at half
 * resolution in each direction. Chroma bytes are unsigned, 128 = neutral. Pure Kotlin so the
 * detectors run in JVM tests.
 */
class Frame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    /** 1 for planar chroma, 2 when U and V are interleaved (NV12/NV21 layouts). */
    val uvPixelStride: Int,
) {
    fun luma(
        x: Int,
        y: Int,
    ): Int = this.y[y * yRowStride + x].toInt() and 0xFF

    /** Chroma at chroma resolution: [cx] in 0 until width / 2, [cy] in 0 until height / 2. */
    fun u(
        cx: Int,
        cy: Int,
    ): Int = u[cy * uvRowStride + cx * uvPixelStride].toInt() and 0xFF

    fun v(
        cx: Int,
        cy: Int,
    ): Int = v[cy * uvRowStride + cx * uvPixelStride].toInt() and 0xFF

    companion object {
        /** Packed ARGB to a planar frame, BT.601 full range like the camera's JPEG/YUV output. */
        fun fromArgb(
            pixels: IntArray,
            width: Int,
            height: Int,
        ): Frame {
            val y = lumaFromArgb(pixels)
            val cw = width / 2
            val ch = height / 2
            val u = ByteArray(cw * ch)
            val v = ByteArray(cw * ch)
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    var r = 0
                    var g = 0
                    var b = 0
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val p = pixels[(2 * cy + dy) * width + 2 * cx + dx]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                        }
                    }
                    val rf = r / 4.0
                    val gf = g / 4.0
                    val bf = b / 4.0
                    u[cy * cw + cx] = (128 - 0.168736 * rf - 0.331264 * gf + 0.5 * bf).toInt().coerceIn(0, 255).toByte()
                    v[cy * cw + cx] = (128 + 0.5 * rf - 0.418688 * gf - 0.081312 * bf).toInt().coerceIn(0, 255).toByte()
                }
            }
            return Frame(width, height, y, width, u, v, cw, 1)
        }
    }
}
