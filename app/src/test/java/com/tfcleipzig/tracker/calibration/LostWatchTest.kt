package com.tfcleipzig.tracker.calibration

import com.tfcleipzig.tracker.detection.StoneLine
import com.tfcleipzig.tracker.detection.TableEndReader
import org.junit.Assert.assertEquals
import org.junit.Test

class LostWatchTest {
    private val nothing = TableEndReader.Reading(null, null, null, null, null, null)
    private val line = TableEndReader.Reading(null, null, null, StoneLine.Line(270f, 0f, 5, emptyList()), null, null)

    private fun LostWatch.feed(vararg readings: TableEndReader.Reading) = readings.map { update(it) }

    @Test
    fun firesOnTheThirdEmptyFrameInARow() {
        val w = LostWatch(3)
        assertEquals(listOf(false, false, true, false, false), w.feed(nothing, nothing, nothing, nothing, nothing))
    }

    @Test
    fun aLineInBetweenStartsOver() {
        val w = LostWatch(3)
        assertEquals(listOf(false, false, false, false, false, true), w.feed(nothing, nothing, line, nothing, nothing, nothing))
    }

    @Test
    fun firesAgainAfterReset() {
        val w = LostWatch(3)
        w.feed(nothing, nothing, nothing)
        w.reset()
        assertEquals(listOf(false, false, true), w.feed(nothing, nothing, nothing))
    }
}
