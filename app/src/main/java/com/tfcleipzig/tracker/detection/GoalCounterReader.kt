package com.tfcleipzig.tracker.detection

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the score off a sliding goal counter (Torzähler) in a luma frame.
 *
 * The counter is a horizontal bar of [Calibration.stones] stones on a rod. Scored stones sit at
 * the right end, the rest at the left end, and the free stretch of rod between them is the
 * spacer. In the frame the spacer is a dark slot with one thin bright line (the rod), while every
 * stone has a bright face or dot that also covers the rows above and below the rod. So per column
 * we take the brightness of the rows away from the rod, find the darkest run that is as wide as
 * the spacer, and count the stone pitches between its right edge and the right end of the bar.
 *
 * Pure logic, no Android types, so it runs in JVM tests.
 */
class GoalCounterReader(
    private val calibration: Calibration,
) {
    /**
     * Where the bar is, in normalized frame coordinates (0..1, origin top-left).
     * [left]/[right] are the outer edges of the outermost stones when packed against the ends;
     * [top]/[bottom] enclose the stones. [spacer] is the free rod length as a fraction of
     * right - left.
     */
    data class Calibration(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val stones: Int = 7,
        val spacer: Float = 0.25f,
        /** Spacer must be at most this fraction as bright as the stones, or the read is rejected. */
        val maxSpacerContrast: Float = 0.5f,
    )

    data class Reading(
        val score: Int,
        /** 0..1; 1 means the spacer is black and the stones bright. */
        val confidence: Float,
    )

    /**
     * [luma] is the Y plane: [width] x [height] pixels, [rowStride] bytes per row.
     * Returns null when no spacer stands out, e.g. a hand is covering the bar.
     */
    fun read(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): Reading? {
        val c = calibration
        val x0 = (c.left * width).roundToInt().coerceIn(0, width - 1)
        val x1 = (c.right * width).roundToInt().coerceIn(x0 + 1, width)
        val y0 = (c.top * height).roundToInt().coerceIn(0, height - 1)
        val y1 = (c.bottom * height).roundToInt().coerceIn(y0 + 1, height)
        val barWidth = x1 - x0
        val rows = y1 - y0
        if (barWidth < 2 * c.stones || rows < 3) return null

        // Rows in the outer thirds of the band: the rod is in the middle third.
        val outerRows = (y0 until y1).filter { abs((it - y0 + 0.5f) / rows - 0.5f) > 1f / 6 }
        val profile =
            FloatArray(barWidth) { i ->
                outerRows.sumOf { (luma[it * rowStride + x0 + i].toInt() and 0xFF) }.toFloat() / outerRows.size
            }

        // Darkest window of spacer width, via prefix sums.
        val window = (c.spacer * barWidth).roundToInt().coerceIn(1, barWidth - 1)
        val prefix = FloatArray(barWidth + 1)
        for (i in 0 until barWidth) prefix[i + 1] = prefix[i] + profile[i]
        var bestStart = 0
        var bestSum = Float.MAX_VALUE
        for (start in 0..barWidth - window) {
            val sum = prefix[start + window] - prefix[start]
            if (sum < bestSum) {
                bestSum = sum
                bestStart = start
            }
        }
        val spacerMean = bestSum / window
        val stoneMean = (prefix[barWidth] - bestSum) / (barWidth - window)
        if (stoneMean <= 0f) return null
        val contrast = spacerMean / stoneMean
        if (contrast > c.maxSpacerContrast) return null

        val pitch = (barWidth - window).toFloat() / c.stones
        val score = ((barWidth - bestStart - window) / pitch).roundToInt().coerceIn(0, c.stones)
        return Reading(score, 1f - contrast)
    }
}
