package com.tfcleipzig.tracker.detection

/** BT.601 luma of packed ARGB pixels, i.e. what the camera's Y plane holds. */
fun lumaFromArgb(pixels: IntArray): ByteArray =
    ByteArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
    }
