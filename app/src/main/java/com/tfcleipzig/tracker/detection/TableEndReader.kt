package com.tfcleipzig.tracker.detection

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
class TableEndReader {
    data class Reading(
        val goals: Int?,
        val sets: Int?,
        val timeouts: Int?,
        val line: StoneLine.Line?,
        val setBar: BarScan.Bar?,
        val goalBar: BarScan.Bar?,
        /** Why a count is null, for logs and the HUD. */
        val problems: List<String> = emptyList(),
    )

    fun read(frame: Frame): Reading {
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

        // Both bars are the same stones at the same distance. A bar measured too long (merged
        // with something) or too short (cut off, partly hidden) shows up as a pitch mismatch.
        val ratio = setBar.pitch / goalBar.pitch
        if (ratio !in MIN_PITCH_RATIO..MAX_PITCH_RATIO) {
            problems += "pitch set/goal %.2f".format(ratio)
            return Reading(null, null, null, line, setBar, goalBar, problems)
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
        return Reading(goals, sets, timeouts, line, setBar, goalBar, problems)
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
