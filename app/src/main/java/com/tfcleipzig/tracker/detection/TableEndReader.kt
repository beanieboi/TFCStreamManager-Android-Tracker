package com.tfcleipzig.tracker.detection

import kotlin.math.abs

/**
 * Reads the counters at one end of the table from one frame: goals, sets and timeouts.
 *
 * Seen from the phone at that end, both bars sit side by side on the bottom edge of the end
 * panel. The right bar counts goals: 7 stones, score = stones right of the free stretch of rod.
 * The left bar has a fixed stop splitting it in two: 3 stones on the left (sets = stones pushed to
 * the left end) and 2 on the right (timeouts = stones pushed away from the right end, i.e.
 * 2 - stones right of the last free stretch).
 *
 * 1. [StoneLine] finds the line both bars sit on from their green stones.
 * 2. [BarScan] labels every column along it (green face, white dot, white face, rod, nothing)
 *    and groups the labels into bars.
 * 3. The two longest bars are the counters. Each bar's pitch comes from its length, which is a
 *    fixed number of stone pitches; the counts come from where the free rod is.
 *
 * A wrong count is worse than none, so every count is checked against what the bar physically
 * is: two bars of the same pitch, free stretches as long as they really are, no blank gap where a
 * stone should be. A count that fails is null, and [Reading.problems] says why.
 */
class TableEndReader : CounterReader {
    data class Reading(
        val goals: Int?,
        val sets: Int?,
        val timeouts: Int?,
        val line: StoneLine.Line?,
        val setBar: BarScan.Bar?,
        val goalBar: BarScan.Bar?,
        /** Why a count is null, for logs and the HUD. */
        val problems: List<String> = emptyList(),
        /** Which end of the table this is, from the bar colours; null when unclear. */
        val end: TableEnd? = null,
        /** Set bar minus goal bar greenness, relative: near +1 at end A, near -1 at end B. */
        val endScore: Float = 0f,
        /**
         * 0..1, how sure this frame is: counters read, how well the stones fit the bars, how
         * clearly the bar colours differ. For comparing camera settings, not a probability.
         */
        val confidence: Float = 0f,
        /**
         * How bright the stones are: the 90th percentile of luma over both bars' stone runs (the
         * faces and dots; 255 when they clip). For keeping the exposure right; null without bars.
         */
        val stoneLuma: Float? = null,
    )

    override fun read(frame: Frame): Reading {
        val line = StoneLine.find(frame) ?: return Reading(null, null, null, null, null, null, listOf("no stone line"))
        val kinds = BarScan.classify(frame, line)
        // First pass with a rough pitch to find the bars.
        val rough = line.stoneSize * BarScan.PITCH_PER_BLOB_HEIGHT
        val bars =
            BarScan
                .bars(BarScan.runs(kinds, (rough * 0.4f).toInt(), (rough * 1.2f).toInt(), 2), (rough * BAR_GAP).toInt(), rough, 0)
                .filter { it.right - it.left >= rough * 5 }
                .sortedByDescending { it.right - it.left }
                .take(2)
                .sortedBy { it.left }
        if (bars.size < 2) return Reading(null, null, null, line, null, bars.firstOrNull(), listOf("${bars.size} bars"))

        val setBar = refine(kinds, bars[0], SET_LENGTH, 5)
        val goalBar = refine(kinds, bars[1], GOAL_LENGTH, 7)
        val problems = mutableListOf<String>()
        val (end, endScore) = end(frame, line, setBar, goalBar)

        // Both bars are the same stones at the same distance. A bar measured too long (merged
        // with something) or too short (cut off, partly hidden) shows up as a pitch mismatch.
        val ratio = setBar.pitch / goalBar.pitch
        if (ratio !in MIN_PITCH_RATIO..MAX_PITCH_RATIO) {
            problems += "pitch set/goal %.2f".format(ratio)
            return Reading(
                null,
                null,
                null,
                line,
                setBar,
                goalBar,
                problems,
                end,
                endScore,
                stoneLuma = stoneLuma(frame, line, setBar, goalBar),
            )
        }

        val goals =
            check(goalBar, frame, problems, "goals") {
                val rods = goalBar.rods
                when {
                    rods.size != 1 -> "${rods.size} free stretches"
                    rods[0].length / goalBar.pitch !in GOAL_FREE -> "free stretch %.1f pitches".format(rods[0].length / goalBar.pitch)
                    else -> null
                }
            }?.stonesRightOfRod()

        val set =
            check(setBar, frame, problems, "sets") {
                val rods = setBar.rods.map { it.length / setBar.pitch }
                when {
                    rods.size == 1 && rods[0] in SET_FREE_MERGED -> null
                    rods.size == 2 && rods[0] in SET_FREE_LEFT && rods[1] in SET_FREE_RIGHT -> null
                    else -> "free stretches " + rods.joinToString { "%.1f".format(it) }
                }
            }
        val sets = set?.stonesLeftOfRod()?.takeIf { it <= 3 }
        val timeouts = set?.stonesRightOfRod()?.takeIf { it <= 2 }?.let { 2 - it }
        val read = listOf(goals, sets, timeouts).count { it != null } / 3f
        val costs = listOfNotNull(goalBar.cost.takeIf { goals != null }, setBar.cost.takeIf { set != null })
        val fit = if (costs.isEmpty()) 0f else 1f - costs.average().toFloat() / BarScan.MAX_ALLOCATION_COST
        val colour = (abs(endScore) / CLEAR_END_SCORE).coerceAtMost(1f)
        val confidence = read * (0.5f + 0.5f * fit) * (0.5f + 0.5f * colour)
        return Reading(
            goals,
            sets,
            timeouts,
            line,
            setBar,
            goalBar,
            problems,
            end,
            endScore,
            confidence,
            stoneLuma(frame, line, setBar, goalBar),
        )
    }

    /** 90th percentile luma over the stone runs of both bars, around the stone line. */
    internal fun stoneLuma(
        frame: Frame,
        line: StoneLine.Line,
        vararg bars: BarScan.Bar,
    ): Float? {
        val histogram = IntArray(256)
        var n = 0
        val half = line.stoneSize / 3
        for (bar in bars) {
            for (run in bar.runs) {
                if (!run.isStone) continue
                for (x in run.start until run.end) {
                    if (x !in 0 until frame.width) continue
                    val c = line.centerAt(x).toInt()
                    for (y in (c - half).coerceAtLeast(0)..(c + half).coerceAtMost(frame.height - 1)) {
                        histogram[frame.luma(x, y)]++
                        n++
                    }
                }
            }
        }
        if (n == 0) return null
        var below = 0
        for (v in 0..255) {
            below += histogram[v]
            if (below >= n * STONE_LUMA_PERCENTILE) return v.toFloat()
        }
        return 255f
    }

    /**
     * End A has mostly green stones on the set bar and mostly black ones on the goal bar, end B the
     * reverse. Compares how green the stones on the two bars are in the same frame (mean green
     * chroma, so glare that turns a green face white to [BarScan.classify] only weakens it).
     * Independent of the counts, so it works while a count is unreadable.
     */
    internal fun end(
        frame: Frame,
        line: StoneLine.Line,
        setBar: BarScan.Bar,
        goalBar: BarScan.Bar,
    ): Pair<TableEnd?, Float> {
        val set = greenness(frame, line, setBar)
        val goal = greenness(frame, line, goalBar)
        val top = maxOf(set, goal)
        if (top < MIN_GREENNESS) return null to 0f
        val score = (set - goal) / top
        val end =
            when {
                score >= MIN_GREEN_CONTRAST -> TableEnd.A
                score <= -MIN_GREEN_CONTRAST -> TableEnd.B
                else -> null
            }
        return end to score
    }

    /** Mean green chroma (how far both U and V are below neutral) over a bar's stone runs. */
    private fun greenness(
        frame: Frame,
        line: StoneLine.Line,
        bar: BarScan.Bar,
    ): Float {
        val half = line.stoneSize / 3
        var sum = 0L
        var n = 0
        for (run in bar.runs) {
            if (!run.isStone) continue
            for (x in run.start until run.end step 2) {
                val cx = (x / 2).coerceIn(0, frame.width / 2 - 1)
                val c = line.centerAt(x).toInt()
                for (y in c - half..c + half step 2) {
                    val cy = (y / 2).coerceIn(0, frame.height / 2 - 1)
                    sum += minOf(128 - frame.u(cx, cy), 128 - frame.v(cx, cy)).coerceAtLeast(0)
                    n++
                }
            }
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    /**
     * The bar if it passes the checks every bar shares and [specific], else null with the reason
     * added to [problems].
     */
    private fun check(
        bar: BarScan.Bar,
        frame: Frame,
        problems: MutableList<String>,
        name: String,
        specific: () -> String?,
    ): BarScan.Bar? {
        val problem =
            when {
                // A bar touching the frame edge may continue outside it.
                bar.left <= EDGE || bar.right >= frame.width - EDGE -> "at frame edge"
                bar.counts == null -> "stones don't add up to ${bar.stones}"
                largestGap(bar) > MAX_GAP -> "gap of %.1f pitches".format(largestGap(bar))
                else -> specific()
            }
        if (problem == null) return bar
        problems += "$name: $problem"
        return null
    }

    /** Largest blank stretch between runs, in pitches: a hand or shadow over stones. */
    private fun largestGap(bar: BarScan.Bar): Float = bar.runs.zipWithNext { a, b -> (b.start - a.end) / bar.pitch }.maxOrNull() ?: 0f

    /**
     * Re-labels a bar's runs with its own pitch: length / [lengthInPitches], where a dot at an
     * end only covers the middle of its stone, so the bar reaches a bit further.
     */
    private fun refine(
        kinds: Array<BarScan.Kind>,
        bar: BarScan.Bar,
        lengthInPitches: Float,
        stones: Int,
    ): BarScan.Bar {
        var left = bar.left.toFloat()
        var right = bar.right.toFloat()
        var pitch = (right - left) / lengthInPitches
        if (bar.runs.first().kind == BarScan.Kind.DOT) left -= DOT_TO_EDGE * pitch
        if (bar.runs.last().kind == BarScan.Kind.DOT) right += DOT_TO_EDGE * pitch
        pitch = (right - left) / lengthInPitches
        val runs =
            BarScan
                .runs(kinds, (pitch * 0.4f).toInt(), (pitch * 1.2f).toInt(), 2)
                .filter { it.end > bar.left && it.start < bar.right }
        return BarScan.Bar(bar.left, bar.right, runs, pitch, stones)
    }

    private companion object {
        /** [Reading.stoneLuma]: bright enough to be the faces and dots, not the black stone bodies. */

        const val STONE_LUMA_PERCENTILE = 0.9f

        /** Below this mean green chroma on both bars there's no colour to go by (dark, washed out). */
        const val MIN_GREENNESS = 2f

        /** [Reading.endScore] needed to call the end; tuned on the reference photos. */
        const val MIN_GREEN_CONTRAST = 0.4f

        /** [Reading.endScore] that counts as fully clear colours for [Reading.confidence]. */
        const val CLEAR_END_SCORE = 0.6f

        /** Bar interiors in stone pitches, measured on the 2026-11-16 reference photos. */
        const val GOAL_LENGTH = 8.94f
        const val SET_LENGTH = 9.3f

        /**
         * Free stretches of rod as seen (in pitches): physically about 1.9 on the goal bar and
         * 2.0 / 1.8 on the set bar, plus the dark gap next to a black stone where the rod shows.
         * The two set stretches merge into one when the stones on both sides of the stop are
         * pushed away from it.
         */
        val GOAL_FREE = 1.4f..3.4f
        val SET_FREE_LEFT = 1.3f..3.1f
        val SET_FREE_RIGHT = 1.2f..3.1f
        val SET_FREE_MERGED = 3.6f..5.6f

        /** Measured 0.86..1.08 on the reference photos. */
        const val MIN_PITCH_RATIO = 0.8f
        const val MAX_PITCH_RATIO = 1.15f

        /** Largest blank stretch inside a bar: the gap between two dots is about 0.6. */
        const val MAX_GAP = 1.2f

        /** Runs further apart than this (in rough pitches) belong to different bars. */
        const val BAR_GAP = 1.5f

        /** From a dot's edge to its stone's edge, in pitches. */
        const val DOT_TO_EDGE = 0.3f

        const val EDGE = 2
    }
}
