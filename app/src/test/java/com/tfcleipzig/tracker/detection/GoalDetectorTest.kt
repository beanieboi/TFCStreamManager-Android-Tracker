package com.tfcleipzig.tracker.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalDetectorTest {
    private val config =
        GoalDetector.Config(
            homeGoal = Zone(0f, 0.4f, 0.1f, 0.6f),
            awayGoal = Zone(0.9f, 0.4f, 1f, 0.6f),
            lostAfterMs = 300,
            cooldownMs = 2_000,
        )
    private val goals = mutableListOf<Side>()
    private val detector = GoalDetector(config) { goals += it }

    private fun ball(
        x: Float,
        y: Float = 0.5f,
    ) = Ball(x, y, 1f)

    @Test
    fun `ball vanishing in home goal is a goal for away`() {
        detector.update(ball(0.5f), 0)
        detector.update(ball(0.05f), 100)
        detector.update(null, 200)
        assertEquals(emptyList<Side>(), goals)
        detector.update(null, 400)
        assertEquals(listOf(Side.AWAY), goals)
    }

    @Test
    fun `ball vanishing in away goal is a goal for home`() {
        detector.update(ball(0.95f), 0)
        detector.update(null, 500)
        assertEquals(listOf(Side.HOME), goals)
    }

    @Test
    fun `ball lost mid-field is not a goal`() {
        detector.update(ball(0.5f), 0)
        detector.update(null, 1_000)
        assertEquals(emptyList<Side>(), goals)
    }

    @Test
    fun `short occlusion in goal mouth is not a goal`() {
        detector.update(ball(0.05f), 0)
        detector.update(null, 100)
        detector.update(ball(0.2f), 200)
        detector.update(null, 1_000)
        assertEquals(emptyList<Side>(), goals)
    }

    @Test
    fun `low confidence counts as not seen`() {
        detector.update(ball(0.05f), 0)
        detector.update(Ball(0.5f, 0.5f, 0.1f), 400)
        assertEquals(listOf(Side.AWAY), goals)
    }

    @Test
    fun `cooldown suppresses a second goal`() {
        detector.update(ball(0.05f), 0)
        detector.update(null, 400)
        detector.update(ball(0.95f), 500)
        detector.update(null, 1_000)
        detector.update(ball(0.95f), 3_000)
        detector.update(null, 3_400)
        assertEquals(listOf(Side.AWAY, Side.HOME), goals)
    }
}
