package com.tfcleipzig.tracker.detection

import com.tfcleipzig.tracker.detection.ScoreSync.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreSyncTest {
    private fun r(
        goals: Int?,
        sets: Int? = 0,
    ) = TableEndReader.Reading(goals, sets, 0, null, null, null)

    private fun ScoreSync.feed(
        n: Int,
        reading: TableEndReader.Reading,
    ) = (1..n).mapNotNull { update(reading) }

    @Test
    fun sendsOnceSteady() {
        val sync = ScoreSync(steadyFrames = 3)
        assertEquals(listOf(Score(2, 0)), sync.feed(10, r(2)))
    }

    @Test
    fun flickerIsNotSent() {
        val sync = ScoreSync(steadyFrames = 3)
        val sent = (1..20).mapNotNull { sync.update(r(if (it % 2 == 0) 2 else 3)) }
        assertEquals(emptyList<Score>(), sent)
    }

    @Test
    fun handsDontBreakTheRun() {
        val sync = ScoreSync(steadyFrames = 3)
        assertNull(sync.update(r(4)))
        assertNull(sync.update(r(null)))
        assertNull(sync.update(r(4)))
        assertEquals(Score(4, 0), sync.update(r(4)))
    }

    @Test
    fun notSentAgainOnceAccepted() {
        val sync = ScoreSync(steadyFrames = 3)
        val first = sync.feed(5, r(1)).single()
        sync.accepted(first)
        assertEquals(emptyList<Score>(), sync.feed(10, r(1)))
        assertEquals(listOf(Score(2, 0)), sync.feed(10, r(2)))
    }

    @Test
    fun retriedAfterAFailure() {
        val sync = ScoreSync(steadyFrames = 3)
        val first = sync.feed(5, r(1)).single()
        sync.failed(first)
        assertEquals(Score(1, 0), sync.update(r(1)))
    }
}
