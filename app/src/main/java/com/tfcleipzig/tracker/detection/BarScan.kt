package com.tfcleipzig.tracker.detection

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Classifies every column along the counter line and splits the columns into bars.
 *
 * Per column, around the line's stone centre:
 * - [Kind.GREEN]: a green stone face (pink dot or not).
 * - [Kind.DOT]: the white dot on a black stone: bright for about a third of the stone height,
 *   with the black stone body right above and below. Taller than the rod, which is how the two
 *   are told apart.
 * - [Kind.FACE]: a bright stone face filling most of the stone height, with the dark slot
 *   outside it (white stones whose dot has worn into the whole face).
 * - [Kind.ROD]: the free stretch of a bar: a thin bright line with the dark slot right above
 *   and below it.
 * - [Kind.NONE]: anything else.
 *
 * Pure logic, no Android types.
 */
object BarScan {
    enum class Kind { NONE, GREEN, DOT, FACE, ROD }

    data class Run(
        val kind: Kind,
        val start: Int,
        val end: Int,
    ) {
        val length get() = end - start
        val isStone get() = kind != Kind.ROD && kind != Kind.NONE
    }

    data class Bar(
        val left: Int,
        val right: Int,
        /** Stone and rod runs, left to right, noise already dropped. */
        val runs: List<Run>,
        val pitch: Float,
        /** Stones the bar holds: 7 on the goal bar, 5 on the set/timeout bar. */
        val stones: Int,
    ) {
        val rods get() = runs.filter { it.kind == Kind.ROD }

        /**
         * Stones per run, adding up to [stones]: a dot is one stone, a face or green run one per
         * pitch of length, rounded either way. Of all splits that add up, the one closest to the
         * measured lengths wins; null when even that is too far off (something hides the bar, or
         * the runs are wrong). End caps and reflections come out as zero-stone runs this way.
         */
        val counts: IntArray? get() = allocation?.first

        /** How far [counts] is from the measured run lengths: 0 a perfect fit, up to [MAX_ALLOCATION_COST]. */
        val cost: Float? get() = allocation?.second

        private val allocation: Pair<IntArray, Float>? by lazy { allocate() }

        private fun allocate(): Pair<IntArray, Float>? {
            // options[i] = (count, cost) pairs for run i.
            val options =
                runs.mapIndexed { i, r ->
                    // The bright metal end caps look like a short stone at either end of a bar.
                    // A real stone can sit there too, so an outermost run is cheap to drop and a
                    // little dearer to count.
                    val atEnd = i == 0 || i == runs.lastIndex
                    val end = if (atEnd) END_COST else 0f
                    val drop = if (atEnd) END_DROP_COST else NOT_A_STONE_COST
                    when (r.kind) {
                        Kind.ROD, Kind.NONE -> listOf(0 to 0f)
                        Kind.DOT -> listOf(1 to end, 0 to drop)
                        Kind.GREEN, Kind.FACE -> {
                            val f = r.length / pitch
                            if (f < SHORT_RUN) {
                                // Shorter than a stone: a large dot or a worn face seen in part.
                                listOf(1 to SHORT_RUN_COST + end, 0 to drop)
                            } else {
                                val lo = floor(f).toInt()
                                listOf(lo to f - lo, lo + 1 to lo + 1 - f)
                            }
                        }
                    }
                }
            // cost[i][t]: cheapest way for runs 0 until i to hold t stones.
            val inf = Float.MAX_VALUE
            val cost = Array(runs.size + 1) { FloatArray(stones + 1) { inf } }
            val choice = Array(runs.size + 1) { IntArray(stones + 1) }
            cost[0][0] = 0f
            for (i in runs.indices) {
                for (t in 0..stones) {
                    if (cost[i][t] == inf) continue
                    for ((k, c) in options[i]) {
                        val nt = t + k
                        if (nt > stones || cost[i][t] + c >= cost[i + 1][nt]) continue
                        cost[i + 1][nt] = cost[i][t] + c
                        choice[i + 1][nt] = k
                    }
                }
            }
            if (cost[runs.size][stones] > MAX_ALLOCATION_COST) return null
            val counts = IntArray(runs.size)
            var t = stones
            for (i in runs.indices.reversed()) {
                counts[i] = choice[i + 1][t]
                t -= counts[i]
            }
            return counts to cost[runs.size][stones]
        }

        /** Stones left of the first free stretch of rod, or null without one or without counts. */
        fun stonesLeftOfRod(): Int? {
            val c = counts ?: return null
            val first = runs.indexOfFirst { it.kind == Kind.ROD }
            if (first < 0) return null
            return (0 until first).sumOf { c[it] }
        }

        /** Stones right of the last free stretch of rod, or null without one or without counts. */
        fun stonesRightOfRod(): Int? {
            val c = counts ?: return null
            val last = runs.indexOfLast { it.kind == Kind.ROD }
            if (last < 0) return null
            return (last + 1 until runs.size).sumOf { c[it] }
        }

        /** Every run with its kind initial and length in pitches, for tuning. */
        fun describe(): String =
            "pitch %.1f: ".format(pitch) + runs.joinToString(" ") { "${it.kind.name[0]}${it.start}+%.2f".format(it.length / pitch) }

        /** Compact picture for logs: `-` rod, `G` green, `o` dot, `W` face, `.` zero-stone run. */
        override fun toString(): String {
            val c = counts
            return "[$left-$right " +
                runs.withIndex().joinToString("") { (i, r) ->
                    val n = c?.get(i) ?: if (r.kind == Kind.ROD) 0 else 1
                    when {
                        r.kind == Kind.ROD -> "-"
                        n == 0 -> "."
                        r.kind == Kind.GREEN -> "G".repeat(n)
                        r.kind == Kind.DOT -> "o"
                        else -> "W".repeat(n)
                    }
                } + (if (c == null) " ?" else "") + "]"
        }
    }

    /**
     * The stone height from chroma blobs runs large (chroma is half resolution and blurred);
     * this fraction of it is close to the stone pitch.
     */
    const val PITCH_PER_BLOB_HEIGHT = 0.8f

    fun classify(
        frame: Frame,
        line: StoneLine.Line,
    ): Array<Kind> {
        val p = line.stoneSize * PITCH_PER_BLOB_HEIGHT
        val half = (p / 2).roundToInt()
        val dotRows = maxOf(2, (p * 0.3f).roundToInt())
        val faceRows = maxOf(dotRows + 1, (p * 0.6f).roundToInt())
        val rim = maxOf(2, (p * 0.45f).roundToInt())
        val beyond = maxOf(rim + 1, (p * 0.65f).roundToInt())
        val search = maxOf(2, (p / 4).roundToInt())

        fun l(
            x: Int,
            y: Int,
        ) = frame.luma(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))

        // Thresholds scale with the light: the brightest stone pixels on the line (white dots,
        // lit faces) set the reference, so a darker room lowers them all.
        val reference = lineHighlight(frame, line)
        val bright = (reference * BRIGHT).toInt()
        val contrast = (reference * CONTRAST).toInt()
        val rodContrast = (reference * ROD_CONTRAST).toInt()

        /** Brightest window of [rows] rows near [c]: its darkest pixel and its centre row. */
        fun brightest(
            x: Int,
            c: Int,
            rows: Int,
        ): Pair<Int, Int> {
            var best = -1
            var bestCenter = c
            for (top in c - search - rows / 2..c + search - rows / 2) {
                var m = 255
                for (y in top until top + rows) m = minOf(m, l(x, y))
                if (m > best) {
                    best = m
                    bestCenter = top + rows / 2
                }
            }
            return best to bestCenter
        }

        return Array(frame.width) { x ->
            val c = line.centerAt(x).roundToInt()
            val cx = (x / 2).coerceAtMost(frame.width / 2 - 1)
            var greenRows = 0
            var rows = 0
            for (y in c - half..c + half step 2) {
                val cy = (y / 2).coerceIn(0, frame.height / 2 - 1)
                if (128 - frame.u(cx, cy) > GREEN_U && 128 - frame.v(cx, cy) > GREEN_V) greenRows++
                rows++
            }
            // A third: the pink dot covers the middle of a green stone.
            if (greenRows * 3 >= rows) return@Array Kind.GREEN

            // A dot is a blob: dark stone body at the rim above, below, left and right of it.
            // The rod is dark above and below too, but stays bright to the sides.
            val (dot, y) = brightest(x, c, dotRows)
            val around = maxOf(maxOf(l(x, y - rim), l(x, y + rim)), maxOf(l(x - rim, y), l(x + rim, y)))
            if (dot >= bright && dot - around >= contrast) return@Array Kind.DOT

            // A bright face filling the stone, with the dark slot beyond it.
            val (face, fy) = brightest(x, c, faceRows)
            if (face >= bright && face - maxOf(l(x, fy - beyond), l(x, fy + beyond)) >= contrast) return@Array Kind.FACE

            // Thin line: a row brighter than the rows two above and two below it.
            var rod = Int.MIN_VALUE
            for (y in c - search - 1..c + search + 1) rod = maxOf(rod, l(x, y) - maxOf(l(x, y - 2), l(x, y + 2)))
            if (rod >= rodContrast) Kind.ROD else Kind.NONE
        }
    }

    /**
     * The light level on the counter line: the luma that the brightest [HIGHLIGHT_SHARE] of the
     * pixels around the line reach (white dots and lit faces), at least a floor so a black frame
     * doesn't turn noise into stones.
     */
    fun lineHighlight(
        frame: Frame,
        line: StoneLine.Line,
    ): Int {
        val histogram = IntArray(256)
        val half = line.stoneSize / 2
        var n = 0
        for (x in 0 until frame.width step 2) {
            val c = line.centerAt(x).roundToInt()
            for (y in (c - half).coerceAtLeast(0)..(c + half).coerceAtMost(frame.height - 1)) {
                histogram[frame.luma(x, y)]++
                n++
            }
        }
        var above = 0
        for (v in 255 downTo 0) {
            above += histogram[v]
            if (above >= n * HIGHLIGHT_SHARE) return maxOf(v, MIN_HIGHLIGHT)
        }
        return MIN_HIGHLIGHT
    }

    private const val MIN_HIGHLIGHT = 80

    /**
     * Runs of equal kind. Gaps up to [maxGap] between two runs of the same kind are closed (the
     * pink dot inside a green face, a rod hidden for a pixel by a reflection); dots stay single.
     * Rods shorter than [minRod] and stone runs shorter than [minStone] are dropped.
     */
    fun runs(
        kinds: Array<Kind>,
        maxGap: Int,
        minRod: Int,
        minStone: Int,
    ): List<Run> {
        val raw = mutableListOf<Run>()
        var x = 0
        while (x < kinds.size) {
            val k = kinds[x]
            var e = x + 1
            while (e < kinds.size && kinds[e] == k) e++
            if (k != Kind.NONE) raw += Run(k, x, e)
            x = e
        }
        val merged = mutableListOf<Run>()
        for (r in raw) {
            val prev = merged.lastOrNull()
            val gap = if (prev == null) Int.MAX_VALUE else r.start - prev.end
            when {
                prev == null -> merged += r
                // The pink dot inside a green face, a rod hidden for a pixel by a reflection.
                prev.kind == r.kind && r.kind != Kind.DOT && gap <= maxGap -> merged[merged.size - 1] = Run(r.kind, prev.start, r.end)
                // Touching pieces of one stone seen as different kinds (a lit green face partly
                // white, a large dot partly face): one face, counted by length.
                prev.isStone && r.isStone && gap <= 1 -> merged[merged.size - 1] = Run(Kind.FACE, prev.start, r.end)
                else -> merged += r
            }
        }
        return merged.filter { if (it.kind == Kind.ROD) it.length >= minRod else it.length >= minStone }
    }

    /** Groups runs into bars separated by more than [barGap] columns of nothing. */
    fun bars(
        runs: List<Run>,
        barGap: Int,
        pitch: Float,
        stones: Int,
    ): List<Bar> {
        val groups = mutableListOf<MutableList<Run>>()
        for (r in runs) {
            val g = groups.lastOrNull()
            if (g != null && r.start - g.last().end <= barGap) g += r else groups += mutableListOf(r)
        }
        return groups.map { Bar(it.first().start, it.last().end, it, pitch, stones) }
    }

    private const val GREEN_U = 12
    private const val GREEN_V = 6

    /** Fractions of [lineHighlight]: tuned at a highlight of about 240 (110, 60, 40). */
    private const val BRIGHT = 0.46f
    private const val CONTRAST = 0.25f
    private const val ROD_CONTRAST = 0.17f

    /** Stone highlights brighter than this share of the line's pixels set the light level. */
    private const val HIGHLIGHT_SHARE = 0.03f

    /** Cost of calling a dot or short run no stone (a reflection, an end cap), in pitches. */
    private const val NOT_A_STONE_COST = 0.8f

    /** Stone runs shorter than this many pitches count as one stone, at [SHORT_RUN_COST]. */
    private const val SHORT_RUN = 0.75f
    private const val SHORT_RUN_COST = 0.15f

    /** Extra cost for counting a bar's outermost run as a stone. */
    private const val END_COST = 0.1f

    /** Cost of dropping a bar's outermost short run (probably an end cap). */
    private const val END_DROP_COST = 0.3f

    /** Largest total length error, in pitches, for a bar's runs to count as read. */
    const val MAX_ALLOCATION_COST = 1.5f
}
