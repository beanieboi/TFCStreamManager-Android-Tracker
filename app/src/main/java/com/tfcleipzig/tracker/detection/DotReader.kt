package com.tfcleipzig.tracker.detection

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads the counters by their dots: every stone carries one round marker (white on the black
 * stones, pink on the green ones), renewed on 2026-11-22 so each one is clear. One dot is one
 * stone, so counting needs no run lengths:
 *
 * 1. Dots: small round blobs that are bright and colourless (white) or clearly red (pink).
 * 2. The counter row: the line most dots of one size sit on (the camera may be rolled a little).
 * 3. Bars: the row splits at its widest gap into the set bar (left, 5 stones) and the goal bar
 *    (right, 7 stones). Any other number of dots, and nothing is read: a hand, glare or something
 *    lying on the bars.
 * 4. Free rod: the stretches of a bar without stones show the rod, a thin bright line with dark
 *    above and below. Where it is says how the stones are split, including when all of a bar's
 *    stones sit together at one end (goals 0 or 7).
 * 5. Counts as [TableEndReader] defines them: goals = stones right of the goal bar's free rod;
 *    sets = stones left of the set bar's first free rod; timeouts = 2 - stones right of its last.
 *
 * Gives the same [TableEndReader.Reading] as [TableEndReader], so calibration, sync and the debug
 * page work with either. Pure logic, no Android types.
 */
class DotReader : CounterReader {
    /** A dot: centre and size in pixels. */
    data class Dot(
        val x: Float,
        val y: Float,
        val size: Float,
    )

    private val endReader = TableEndReader()

    override fun read(frame: Frame): TableEndReader.Reading {
        val dots = dots(frame)
        val row = row(dots) ?: return fail("no row of dots (${dots.size} dots)")
        val (line, found) = row
        val pitch = pitch(found) ?: return fail("no pitch", line)
        val onRow = withoutEndCaps(found, pitch)
        if (onRow.size != SET_STONES + GOAL_STONES) return fail("${onRow.size} dots in the row", line)

        // The widest gap separates the bars; the set bar is on the left.
        val split = (1 until onRow.size).maxBy { onRow[it].x - onRow[it - 1].x }
        if (split != SET_STONES) return fail("bars of $split and ${onRow.size - split} dots", line)
        val setDots = onRow.subList(0, split)
        val goalDots = onRow.subList(split, onRow.size)
        val between = (setDots.last().x + goalDots.first().x) / 2

        val problems = mutableListOf<String>()
        val reach = (FREE_REACH * pitch).toInt()
        val setFrom = (setDots.first().x - reach).toInt()
        val goalTo = (goalDots.last().x + reach).toInt()
        if (setFrom < 0 || goalTo >= frame.width) problems += "bars at the frame edge"
        val rods = rods(frame, line, onRow, pitch, max(0, setFrom), min(frame.width - 1, goalTo))
        val setRods = rods.filter { it.end <= between }
        val goalRods = rods.filter { it.start >= between }

        val setBar = bar(setDots, setRods, pitch, SET_STONES)
        val goalBar = bar(goalDots, goalRods, pitch, GOAL_STONES)

        val goals =
            when {
                problems.isNotEmpty() -> null
                goalRods.size != 1 -> null.also { problems += "goals: ${goalRods.size} free stretches" }
                !regular(goalDots, goalRods, pitch) -> null.also { problems += "goals: stones not evenly spaced" }
                else -> goalDots.count { it.x > goalRods[0].center }
            }
        var sets: Int? = null
        var timeouts: Int? = null
        when {
            problems.any { it.startsWith("bars") } -> Unit
            setRods.size !in 1..2 -> problems += "sets: ${setRods.size} free stretches"
            !regular(setDots, setRods, pitch, stopAfter = SET_SECTION) -> problems += "sets: stones not evenly spaced"
            else -> {
                val left = setDots.count { it.x < setRods.first().center }
                val right = setDots.count { it.x > setRods.last().center }
                if (left <= SET_SECTION) sets = left else problems += "sets: $left stones left of the rod"
                if (right <= TIMEOUT_SECTION) timeouts = TIMEOUT_SECTION - right else problems += "timeouts: $right stones right of the rod"
            }
        }

        val (end, endScore) = endReader.end(frame, line, setBar, goalBar)
        val read = listOf(goals, sets, timeouts).count { it != null } / 3f
        val spacing = 1f - (spread(onRow, rods, pitch) / MAX_SPREAD).coerceIn(0f, 1f)
        val confidence = read * (0.5f + 0.5f * spacing)
        val luma = endReader.stoneLuma(frame, line, setBar, goalBar)
        return TableEndReader.Reading(goals, sets, timeouts, line, setBar, goalBar, problems, end, endScore, confidence, luma)
    }

    private fun fail(
        problem: String,
        line: StoneLine.Line? = null,
    ) = TableEndReader.Reading(null, null, null, line, null, null, listOf(problem))

    /** Bright colourless or clearly red pixels, grouped into small round blobs. */
    fun dots(frame: Frame): List<Dot> {
        val w = frame.width
        val h = frame.height
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val luma = frame.luma(x, y)
                if (luma < MIN_DOT_LUMA) continue
                val u = frame.u(x / 2, y / 2) - 128
                val v = frame.v(x / 2, y / 2) - 128
                val white = luma >= MIN_WHITE_LUMA && abs(u) <= NEUTRAL && abs(v) <= NEUTRAL
                val pink = v >= MIN_RED && v - u >= MIN_RED
                mask[y * w + x] = white || pink
            }
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val dots = mutableListOf<Dot>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var l = w
            var t = h
            var r = -1
            var b = -1
            var n = 0
            var sx = 0L
            var sy = 0L
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                n++
                sx += x
                sy += y
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) push(stack, sp++, i - 1, seen)
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) push(stack, sp++, i + 1, seen)
                if (y > 0 && mask[i - w] && !seen[i - w]) push(stack, sp++, i - w, seen)
                if (y < h - 1 && mask[i + w] && !seen[i + w]) push(stack, sp++, i + w, seen)
            }
            val bw = r - l + 1
            val bh = b - t + 1
            val round = bw <= 2 * bh && bh <= 2 * bw && n >= bw * bh * MIN_FILL
            if (round && n in MIN_DOT_AREA..maxDotArea(w, h)) dots += Dot(sx.toFloat() / n, sy.toFloat() / n, sqrt(bw.toFloat() * bh))
        }
        return dots
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

    /**
     * The line most dots sit on: for each slope, votes for where it crosses x = 0, a dot's own
     * size wide. Then only the dots near the winning line and of the row's typical size, left to
     * right.
     */
    fun row(dots: List<Dot>): Pair<StoneLine.Line, List<Dot>>? {
        if (dots.size < 2) return null
        var best: Pair<Float, List<Dot>>? = null
        var slope = -MAX_SLOPE
        while (slope <= MAX_SLOPE + 1e-6f) {
            val s = slope
            val sorted = dots.sortedBy { it.y - s * it.x }
            var j = 0
            for (i in sorted.indices) {
                val y0 = sorted[i].y - s * sorted[i].x
                while (j < sorted.size && sorted[j].y - s * sorted[j].x - y0 <= sorted[i].size) j++
                val group = sorted.subList(i, j)
                if (best == null || group.size > best.second.size) best = s to group.toList()
            }
            slope += SLOPE_STEP
        }
        val (_, group) = best ?: return null
        val size = group.map { it.size }.sorted()[group.size / 2]
        val sameSize = group.filter { it.size in size * MIN_SIZE_RATIO..size * MAX_SIZE_RATIO }
        if (sameSize.size < 2) return null
        // The vote only roughly says where: several slopes collect nearly the same dots, the first
        // of them is off by a few pixels at the bar ends (where the rod is a pixel or two thick),
        // and on a rolled camera it can swap a stone's dot at one end for a glint at the other.
        // So fit a line through its dots, then take the row's dots afresh from all of them.
        var (y0, s) = fit(sameSize)
        var onRow = sameSize
        repeat(2) {
            onRow =
                dots.filter {
                    it.size in size * MIN_SIZE_RATIO..size * MAX_SIZE_RATIO &&
                        abs(
                            it.y - (y0 + s * it.x),
                        ) <= size * ROW_DISTANCE
                }
            if (onRow.size >= 2) {
                fit(onRow).let { (a, b) ->
                    y0 = a
                    s = b
                }
            }
        }
        onRow = onRow.sortedBy { it.x }
        val pitch = pitch(onRow) ?: size * PITCH_PER_DOT
        return StoneLine.Line(y0, s, pitch.roundToInt(), emptyList()) to onRow
    }

    /**
     * The bars' end caps glint like a dot, right next to the outermost stone: closer than any two
     * stones can be. Of such a pair, the one further from its bar's middle is the cap.
     */
    private fun withoutEndCaps(
        dots: List<Dot>,
        pitch: Float,
    ): List<Dot> {
        if (dots.size < 2) return dots
        val split = (1 until dots.size).maxBy { dots[it].x - dots[it - 1].x }
        return listOf(dots.subList(0, split), dots.subList(split, dots.size)).flatMap { bar ->
            val kept = bar.toMutableList()
            val middle = (bar.first().x + bar.last().x) / 2
            while (true) {
                val i = (1 until kept.size).firstOrNull { kept[it].x - kept[it - 1].x < CAP_GAP * pitch } ?: break
                val a = kept[i - 1]
                val b = kept[i]
                kept.remove(if (abs(a.x - middle) > abs(b.x - middle)) a else b)
            }
            kept
        }
    }

    /** Least-squares line through the dots' centres: (y at x = 0, slope). */
    private fun fit(dots: List<Dot>): Pair<Float, Float> {
        val mx = dots.map { it.x }.average()
        val my = dots.map { it.y }.average()
        val sxx = dots.sumOf { (it.x - mx) * (it.x - mx) }
        val slope = if (sxx < 1e-6) 0.0 else dots.sumOf { (it.x - mx) * (it.y - my) } / sxx
        return (my - slope * mx).toFloat() to slope.toFloat()
    }

    /** Stone pitch: the median of the gaps between neighbouring dots that touch (the smaller ones). */
    private fun pitch(dots: List<Dot>): Float? {
        if (dots.size < 3) return null
        val gaps = (1 until dots.size).map { dots[it].x - dots[it - 1].x }.sorted()
        val touching = gaps.take(max(1, gaps.size - LARGE_GAPS))
        return touching[touching.size / 2]
    }

    /** A free stretch of rod: [start] until [end] along the row. */
    data class Rod(
        val start: Int,
        val end: Int,
    ) {
        val center get() = (start + end) / 2f
        val length get() = end - start
    }

    /**
     * Columns from [from] to [to] where the row shows a thin bright line with dark above and
     * below, away from the stones, grouped into stretches at least [MIN_ROD_PITCHES] long.
     */
    private fun rods(
        frame: Frame,
        line: StoneLine.Line,
        dots: List<Dot>,
        pitch: Float,
        from: Int,
        to: Int,
    ): List<Rod> {
        val flank = max(2, (pitch * FLANK).roundToInt())
        val clear = pitch * CLEAR_OF_STONES
        val isRod =
            BooleanArray(to - from + 1) { i ->
                val x = from + i
                if (dots.any { abs(it.x - x) < clear }) return@BooleanArray false
                val c = line.centerAt(x).roundToInt()
                if (c - flank < 0 || c + flank >= frame.height) return@BooleanArray false
                val rod = (c - 1..c + 1).maxOf { frame.luma(x, it) }
                val dark = max(frame.luma(x, c - flank), frame.luma(x, c + flank))
                rod >= MIN_ROD_LUMA && rod - dark >= MIN_ROD_CONTRAST
            }
        val rods = mutableListOf<Rod>()
        var start = -1
        var lastOn = -1
        for (i in isRod.indices) {
            if (!isRod[i]) continue
            if (start >= 0 && i - lastOn > ROD_HOLE) {
                rods += Rod(from + start, from + lastOn + 1)
                start = -1
            }
            if (start < 0) start = i
            lastOn = i
        }
        if (start >= 0) rods += Rod(from + start, from + lastOn + 1)
        // A rod stretch runs from stone to stone; the clear zone around each dot cuts its ends.
        return rods.filter { it.length + 2 * (clear - pitch / 2) >= MIN_ROD_PITCHES * pitch }
    }

    /**
     * The stones between and beyond the free stretches sit side by side: neighbouring dots not
     * separated by a rod are about a pitch apart. On the set bar a fixed stop sits after stone
     * [stopAfter]: the stones either side of it are at least [STOP_GAP] apart instead.
     */
    private fun regular(
        dots: List<Dot>,
        rods: List<Rod>,
        pitch: Float,
        stopAfter: Int = -1,
    ): Boolean =
        (1 until dots.size).all { i ->
            val a = dots[i - 1].x
            val b = dots[i].x
            val gap = (b - a) / pitch
            when {
                rods.any { it.center in a..b } -> true
                i == stopAfter -> gap >= STOP_GAP
                else -> gap in TOUCHING
            }
        }

    /** How unevenly neighbouring stones sit, in pitches (0: perfectly even). */
    private fun spread(
        dots: List<Dot>,
        rods: List<Rod>,
        pitch: Float,
    ): Float {
        // Only stones that touch: not across a free rod, the fixed stop or the gap between the bars.
        val gaps =
            (1 until dots.size)
                .filter { i -> rods.none { it.center in dots[i - 1].x..dots[i].x } }
                .map { (dots[it].x - dots[it - 1].x) / pitch }
                .filter { it in TOUCHING }
        if (gaps.size < 2) return 0f
        val mean = gaps.average()
        return sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size).toFloat()
    }

    /** The bar as [TableEndReader] describes one: stones as dot runs, free stretches as rod runs. */
    private fun bar(
        dots: List<Dot>,
        rods: List<Rod>,
        pitch: Float,
        stones: Int,
    ): BarScan.Bar {
        val half = (pitch / 2).roundToInt()
        val runs =
            (
                dots.map { BarScan.Run(BarScan.Kind.DOT, it.x.roundToInt() - half, it.x.roundToInt() + half) } +
                    rods.map { BarScan.Run(BarScan.Kind.ROD, it.start, it.end) }
            ).sortedBy { it.start }
        return BarScan.Bar(runs.first().start, runs.last().end, runs, pitch, stones)
    }

    companion object {
        const val SET_STONES = 5
        const val GOAL_STONES = 7

        /** The set bar: 3 stones for sets left of the fixed stop, 2 for timeouts right of it. */
        const val SET_SECTION = 3
        const val TIMEOUT_SECTION = 2

        /** Dot pixels: at least this bright; white ones brighter still and without colour. */
        const val MIN_DOT_LUMA = 70
        const val MIN_WHITE_LUMA = 140
        const val NEUTRAL = 22

        /** Pink: V this far above neutral and above U. */
        const val MIN_RED = 14

        /** Blob shape: area in pixels, share of its box filled. */
        const val MIN_DOT_AREA = 4
        const val MIN_FILL = 0.45f

        fun maxDotArea(
            width: Int,
            height: Int,
        ) = width * height / 1500

        /** Row search: slopes up to ±0.15 (about ±8°) in steps of 0.005. */
        const val MAX_SLOPE = 0.15f
        const val SLOPE_STEP = 0.005f

        /** Dots on the row are within these multiples of the row's typical dot size. */
        const val MIN_SIZE_RATIO = 0.65f
        const val MAX_SIZE_RATIO = 1.6f

        /** Dots on the row are at most this many dot sizes off the fitted line. */
        const val ROW_DISTANCE = 0.6f

        /** Pitch when it can't be measured: a dot is about half a stone wide. */
        const val PITCH_PER_DOT = 2.2f

        /** Gaps that aren't stone to stone: up to two free stretches and the one between the bars, plus one spare. */
        const val LARGE_GAPS = 4

        /** Free stretches are looked for this many pitches beyond the outer dots. */
        const val FREE_REACH = 3.5f

        /** Rod test: the dark flanks this many pitches above and below the row. */
        const val FLANK = 0.42f

        /** Columns this close to a dot (in pitches) are stone, not rod. */
        const val CLEAR_OF_STONES = 0.6f

        /** A rod line: at least this bright, and this much brighter than its flanks. */
        const val MIN_ROD_LUMA = 70
        const val MIN_ROD_CONTRAST = 35

        /** Rod columns may miss this many pixels in a row (glints, noise) and still be one stretch. */
        const val ROD_HOLE = 2

        /** Shortest free stretch, in pitches: physically about 1.8, less where it meets a stone. */
        const val MIN_ROD_PITCHES = 0.9f

        /** Closer than this (in pitches) to its neighbour, a blob is an end cap, not a stone: caps sit about 0.85 from the last stone, stones 0.95 or more apart. */
        const val CAP_GAP = 0.9f

        /** Set bar stones either side of the fixed stop: physically 1.5 pitches apart. */
        const val STOP_GAP = 1.3f

        /** Neighbouring stones without rod between them are this many pitches apart. */
        val TOUCHING = 0.75f..1.3f

        /** [spread] at which confidence bottoms out. */
        const val MAX_SPREAD = 0.2f
    }
}
