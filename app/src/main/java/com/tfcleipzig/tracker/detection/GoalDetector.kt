package com.tfcleipzig.tracker.detection

/**
 * Turns per-frame ball positions into goals.
 *
 * A goal is the ball last seen inside a goal mouth and then gone for [Config.lostAfterMs].
 * [Config.homeGoal] is the goal the home team defends, so the ball vanishing there is a goal
 * for [Side.AWAY], and vice versa. Pure logic, no Android types, so it is unit-testable.
 */
class GoalDetector(
    private val config: Config,
    private val onGoal: (Side) -> Unit,
) {
    data class Config(
        val homeGoal: Zone,
        val awayGoal: Zone,
        val minConfidence: Float = 0.5f,
        val lostAfterMs: Long = 400,
        val cooldownMs: Long = 3_000,
    )

    private var lastBall: Ball? = null
    private var lastSeenMs = 0L
    private var cooldownUntilMs = 0L

    fun update(
        ball: Ball?,
        timestampMs: Long,
    ) {
        if (ball != null && ball.confidence >= config.minConfidence) {
            lastBall = ball
            lastSeenMs = timestampMs
            return
        }
        val last = lastBall ?: return
        if (timestampMs - lastSeenMs < config.lostAfterMs) return

        lastBall = null
        if (timestampMs < cooldownUntilMs) return
        val scorer =
            when (last) {
                in config.homeGoal -> Side.AWAY
                in config.awayGoal -> Side.HOME
                else -> return
            }
        cooldownUntilMs = timestampMs + config.cooldownMs
        onGoal(scorer)
    }
}
