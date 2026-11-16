package com.tfcleipzig.tracker.detection

import kotlin.math.roundToInt

/**
 * Reads the score off a sliding goal counter (Torzähler) in a luma frame.
 *
 * The counter is a horizontal bar of [Calibration.stones] stones on a rod. Scored stones sit at
 * the right end, the rest at the left end, and the free stretch of rod between them is the
 * spacer. In the frame the spacer is a dark slot with one thin bright line (the rod), while every
 * stone has a bright face or dot that is taller than the rod. So per column we take the brightest
 * vertical run of [Calibration.dotHeight] rows: the run has to fit inside a dot, which a thin rod
 * can't fill. Searching that run over a band a bit taller than the calibration means we never
 * need to know which row the rod is on, so a mount that has moved a pixel or two still reads.
 * Then we find the darkest stretch of columns as wide as the spacer and count the stone pitches
 * between its right edge and the right end of the bar.
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
        /** Height of a stone's bright dot as a fraction of bottom - top; must exceed the rod's. */
        val dotHeight: Float = 0.5f,
        /** Extra rows searched above and below the band, as a fraction of bottom - top. */
        val verticalSlack: Float = 0.34f,
        /** Spacer must be at most this fraction as bright as the stones, or the read is rejected. */
        val maxSpacerContrast: Float = 0.5f,
    ) {
        companion object {
            /** Measured on the 2026-11-15 test photo, scaled to the 640x480 analysis size. */
            val TISCH_1_BOTTOM_RIGHT =
                Calibration(
                    left = 337.5f / 640,
                    top = 369f / 480,
                    right = 390.5f / 640,
                    bottom = 375f / 480,
                )
        }
    }

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

        // Per column: brightest run of `run` rows, scored by its darkest pixel. A dot fills the
        // run, the rod (thinner than a dot) doesn't, wherever in the search band either one is.
        val slack = (c.verticalSlack * rows).roundToInt()
        val searchTop = (y0 - slack).coerceAtLeast(0)
        val searchBottom = (y1 + slack).coerceAtMost(height)
        val run = (c.dotHeight * rows).roundToInt().coerceIn(1, searchBottom - searchTop)
        val profile =
            FloatArray(barWidth) { i ->
                val x = x0 + i
                var best = 0
                for (top in searchTop..searchBottom - run) {
                    var darkest = 255
                    for (y in top until top + run) darkest = minOf(darkest, luma[y * rowStride + x].toInt() and 0xFF)
                    best = maxOf(best, darkest)
                }
                best.toFloat()
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
