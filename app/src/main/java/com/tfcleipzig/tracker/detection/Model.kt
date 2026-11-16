package com.tfcleipzig.tracker.detection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Matches the server's `Side` (`home` / `away`). */
@Serializable
enum class Side {
    @SerialName("home")
    HOME,

    @SerialName("away")
    AWAY,
}

/**
 * The two ends of a table, one tracker phone each. Told apart by the counter colours: at end A
 * the goal bar has black stones (one green) and the set bar green ones, at end B the reverse.
 * The reference photos in `testdata/` are named after them (`Side A`, `Side B`).
 */
enum class TableEnd { A, B }

/** Ball position in normalized frame coordinates (0..1, origin top-left, analysis orientation). */
data class Ball(
    val x: Float,
    val y: Float,
    val confidence: Float,
)

/** Axis-aligned rectangle in normalized frame coordinates. */
data class Zone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    operator fun contains(ball: Ball): Boolean = ball.x in left..right && ball.y in top..bottom
}
