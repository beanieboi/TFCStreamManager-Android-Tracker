package com.tfcleipzig.tracker.detection

import kotlin.math.abs

/**
 * Finds the line the counter bars sit on, using their green stones as landmarks.
 *
 * Both bars at each table end hold at least one bright green stone (the odd stone on one bar, all
 * but one stone on the other). Green blobs that are stone-sized, wide rather than tall and have
 * the dark table edge right below them are stone candidates: players stand on the bright field,
 * the goal box and the legs are far too big. The bars sit side by side, so the candidates that
 * share a row are the counter line.
 */
object StoneLine {
    /** A run of adjacent green stones, in full-resolution pixels. */
    data class Blob(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width get() = right - left
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2f
    }

    /**
     * The counter line: stone centres at `y = centerAt(x)`. The bars are level on the table but
     * the camera may be rolled a little, so the line can slope.
     */
    data class Line(
        val y0: Float,
        val slope: Float,
        /** Stone height, roughly the stone pitch too (stones are square). */
        val stoneSize: Int,
        val blobs: List<Blob>,
    ) {
        fun centerAt(x: Int): Float = y0 + slope * x
    }

    /** Minimum (128 - U) and (128 - V) for a chroma sample to count as green. */
    private const val GREEN = 12

    fun find(frame: Frame): Line? = line(greenBlobs(frame).filter { isStone(frame, it) })

    /** 4-connected green components, at chroma resolution, scaled to full-resolution boxes. */
    fun greenBlobs(frame: Frame): List<Blob> {
        val cw = frame.width / 2
        val ch = frame.height / 2
        val green = BooleanArray(cw * ch) { i -> 128 - frame.u(i % cw, i / cw) > GREEN && 128 - frame.v(i % cw, i / cw) > GREEN }
        val seen = BooleanArray(cw * ch)
        val stack = IntArray(cw * ch)
        val blobs = mutableListOf<Blob>()
        for (start in green.indices) {
            if (!green[start] || seen[start]) continue
            var l = cw
            var t = ch
            var r = -1
            var b = -1
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % cw
                val y = i / cw
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (x > 0 && green[i - 1] && !seen[i - 1]) push(stack, sp++, i - 1, seen)
                if (x < cw - 1 && green[i + 1] && !seen[i + 1]) push(stack, sp++, i + 1, seen)
                if (y > 0 && green[i - cw] && !seen[i - cw]) push(stack, sp++, i - cw, seen)
                if (y < ch - 1 && green[i + cw] && !seen[i + cw]) push(stack, sp++, i + cw, seen)
            }
            blobs += Blob(2 * l, 2 * t, 2 * r + 2, 2 * b + 2)
        }
        return blobs
    }

    private fun push(
        stack: IntArray,
        at: Int,
        i: Int,
        seen: BooleanArray,
    ) {
        stack[at] = i
        seen[i] = true
    }

    /** Stone-sized, not taller than wide, and dark right below (the table edge, not the field). */
    fun isStone(
        frame: Frame,
        b: Blob,
    ): Boolean {
        if (b.height !in 4..20 || b.width < b.height * 2 / 3) return false
        val y0 = b.bottom + 1
        val y1 = minOf(frame.height, b.bottom + 1 + b.height / 2)
        if (y0 >= y1) return false
        var sum = 0
        var n = 0
        for (y in y0 until y1) {
            for (x in b.left until b.right) {
                sum += frame.luma(x, y)
                n++
            }
        }
        return sum / n < DARK
    }

    private const val DARK = 60

    /** Largest slope accepted, about 8 degrees of camera roll. */
    private const val MAX_SLOPE = 0.15f

    /**
     * The line through stone blobs that collects the most stone width. Every pair of blobs (and
     * every single blob, level) proposes a line; blobs whose centre lies within a third of a stone
     * of it count for it. The winner is refined by a width-weighted least-squares fit.
     */
    private fun line(stones: List<Blob>): Line? {
        if (stones.isEmpty()) return null
        val size = stones.map { it.height }.sorted()[stones.size / 2]
        val tolerance = size / 3f
        var best: List<Blob> = emptyList()
        var bestWidth = -1
        for (i in stones.indices) {
            for (j in i until stones.size) {
                val a = stones[i]
                val b = stones[j]
                val dx = (b.left + b.right - a.left - a.right) / 2f
                val slope = if (i == j || abs(dx) < size) 0f else (b.centerY - a.centerY) / dx
                if (abs(slope) > MAX_SLOPE) continue
                val ax = (a.left + a.right) / 2f
                val inliers = stones.filter { abs(it.centerY - (a.centerY + slope * ((it.left + it.right) / 2f - ax))) <= tolerance }
                val width = inliers.sumOf { it.width }
                if (width > bestWidth) {
                    bestWidth = width
                    best = inliers
                }
            }
        }
        // Width-weighted least squares through the inlier centres.
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (b in best) {
            val w = b.width.toDouble()
            val x = (b.left + b.right) / 2.0
            val y = b.centerY.toDouble()
            sw += w
            sx += w * x
            sy += w * y
            sxx += w * x * x
            sxy += w * x * y
        }
        val det = sw * sxx - sx * sx
        val slope =
            if (det > 1e-6 &&
                best.size > 1
            ) {
                ((sw * sxy - sx * sy) / det).coerceIn(-MAX_SLOPE.toDouble(), MAX_SLOPE.toDouble())
            } else {
                0.0
            }
        val y0 = (sy - slope * sx) / sw
        return Line(
            y0 = y0.toFloat(),
            slope = slope.toFloat(),
            stoneSize = best.map { it.height }.sorted()[best.size / 2],
            blobs = best.sortedBy { it.left },
        )
    }
}
