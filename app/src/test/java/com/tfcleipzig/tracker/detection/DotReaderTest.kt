package com.tfcleipzig.tracker.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * [DotReader] on drawn counters: the end A layout (set bar left, goal bar right), stones as dark
 * squares with a white dot, green ones with a pink dot, the rod a thin light line in the free
 * stretches. Sizes as at zoom 2.75 in a 640×480 frame: about 15 px per stone.
 */
class DotReaderTest {
    @Test
    fun readsEveryCount() {
        val reader = DotReader()
        val wrong = mutableListOf<String>()
        for (goals in 0..7) {
            for (sets in 0..3) {
                for (timeouts in 0..2) {
                    val r = reader.read(Drawing(goals, sets, timeouts).frame())
                    if (Triple(r.goals, r.sets, r.timeouts) != Triple(goals, sets, timeouts)) {
                        wrong += "$goals/$sets/$timeouts -> ${r.goals}/${r.sets}/${r.timeouts} ${r.problems}"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun copesWithNoiseAndARolledCamera() {
        val reader = DotReader()
        val random = Random(1)
        val wrong = mutableListOf<String>()
        for (goals in 0..7) {
            val (sets, timeouts) = random.nextInt(4) to random.nextInt(3)
            val r = reader.read(Drawing(goals, sets, timeouts, slope = 0.03f, noise = 12, seed = goals).frame())
            if (Triple(r.goals, r.sets, r.timeouts) != Triple(goals, sets, timeouts)) {
                wrong += "$goals/$sets/$timeouts -> ${r.goals}/${r.sets}/${r.timeouts} ${r.problems}"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /** A hand over the goal bar: dots missing, so no count rather than a wrong one. */
    @Test
    fun aCoveredStoneReadsNothing() {
        val r = DotReader().read(Drawing(3, 1, 0, coverGoalStone = 2).frame())
        assertNull("goals ${r.goals}", r.goals)
    }

    /** End A counters as the camera sees them, upright. */
    private class Drawing(
        val goals: Int,
        val sets: Int,
        val timeouts: Int,
        val slope: Float = 0f,
        val noise: Int = 0,
        val seed: Int = 0,
        /** Leave out this goal stone's dot (counted from the left), as if covered. */
        val coverGoalStone: Int = -1,
    ) {
        private val w = 640
        private val h = 480
        private val p = 15f
        private val cy = 230f
        private val px = IntArray(w * h) { rgb(30, 30, 30) }

        fun frame(): Frame {
            // Set bar: 9.3 pitches from x 120. Left section (3 stones, 2.0 free), stop 0.5, right section (2 stones, 1.8 free).
            val set0 = 120f
            val setStones = mutableListOf<Float>()
            for (i in 0 until sets) setStones += i.toFloat()
            for (i in 0 until 3 - sets) setStones += sets + 2.0f + i
            val stop = 5.0f
            for (i in 0 until timeouts) setStones += stop + 0.5f + i
            for (i in 0 until 2 - timeouts) setStones += stop + 0.5f + timeouts + 1.8f + i
            slot(set0, 9.3f)
            rod(set0, 9.3f)
            setStones.forEachIndexed { i, at -> stone(set0 + at * p, green = i != 2, dot = true) }

            // Goal bar: 8.94 pitches from x 360. 7 - goals stones left, 1.94 free, goals stones right.
            val goal0 = 360f
            slot(goal0, 8.94f)
            rod(goal0, 8.94f)
            val goalStones = (0 until 7 - goals).map { it.toFloat() } + (0 until goals).map { 7 - goals + 1.94f + it }
            // End A: black goal stones, the 5th from the right green.
            goalStones.forEachIndexed { i, at -> stone(goal0 + at * p, green = i == 2, dot = i != coverGoalStone) }

            if (noise > 0) {
                val random = Random(seed)
                for (i in px.indices) {
                    val n = random.nextInt(-noise, noise + 1)
                    val c = px[i]
                    px[i] = rgb(((c shr 16) and 0xFF) + n, ((c shr 8) and 0xFF) + n, (c and 0xFF) + n)
                }
            }
            return Frame.fromArgb(px, w, h)
        }

        private fun y(x: Float) = cy + slope * (x - w / 2)

        /** The dark slot the stones slide in. */
        private fun slot(
            x0: Float,
            length: Float,
        ) = box(x0, y(x0) - p * 0.6f, x0 + length * p, y(x0) + p * 0.6f, rgb(12, 12, 12))

        private fun rod(
            x0: Float,
            length: Float,
        ) {
            for (x in x0.toInt() until (x0 + length * p).toInt()) {
                val y = y(x.toFloat()).roundToInt()
                set(x, y, rgb(190, 190, 190))
                set(x, y + 1, rgb(150, 150, 150))
            }
        }

        private fun stone(
            left: Float,
            green: Boolean,
            dot: Boolean,
        ) {
            val c = y(left + p / 2)
            box(left + 1, c - p / 2 + 1, left + p - 1, c + p / 2 - 1, if (green) rgb(70, 190, 80) else rgb(20, 20, 20))
            if (!dot) return
            val r = p * 0.22f
            val dotColour = if (green) rgb(235, 120, 150) else rgb(235, 235, 235)
            for (y in (c - r).toInt()..(c + r).toInt()) {
                for (x in (left + p / 2 - r).toInt()..(left + p / 2 + r).toInt()) {
                    val dx = x + 0.5f - (left + p / 2)
                    val dy = y + 0.5f - c
                    if (dx * dx + dy * dy <= r * r) set(x, y, dotColour)
                }
            }
        }

        private fun box(
            l: Float,
            t: Float,
            r: Float,
            b: Float,
            c: Int,
        ) {
            for (y in t.toInt()..b.toInt()) for (x in l.toInt()..r.toInt()) set(x, y, c)
        }

        private fun set(
            x: Int,
            y: Int,
            c: Int,
        ) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = c
        }

        private fun rgb(
            r: Int,
            g: Int,
            b: Int,
        ) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
