package com.tfcleipzig.tracker.reference

/**
 * The labeled photos in `testdata/`: `Side A/score 3 - timeout 1 - set 0.jpg` is a photo from the
 * Side A phone showing that end's goal, timeout and set counters. Shared by the JVM reference test
 * and the on-device benchmark.
 */
data class ReferencePhoto(
    /** Path relative to `testdata/`, e.g. `Side A/score 3 - timeout 1 - set 0.jpg`. */
    val path: String,
    val side: String,
    val expected: Expected,
) {
    data class Expected(
        val score: Int,
        val timeouts: Int,
        val sets: Int,
    )

    companion object {
        private val NAME = Regex("""score (\d+) - timeout (\d+) - set (\d+)\.jpg""")

        /** Null for files that don't follow the naming scheme. */
        fun parse(path: String): ReferencePhoto? {
            val side = path.substringBefore('/', "")
            val m = NAME.matchEntire(path.substringAfterLast('/')) ?: return null
            val (score, timeouts, sets) = m.destructured
            return ReferencePhoto(path, side, Expected(score.toInt(), timeouts.toInt(), sets.toInt()))
        }
    }
}
