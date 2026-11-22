package com.tfcleipzig.tracker.calibration

import com.tfcleipzig.tracker.calibration.ExposureTracker.Exposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureTrackerTest {
    /** A scene whose stones are [light] × the exposure value bright, clipping at 255. */
    private fun luma(
        light: Double,
        e: Exposure,
    ) = (light * e.value).toFloat().coerceAtMost(255f)

    /** Runs [readings] readings of a scene at [light]; returns the exposure it ends at. */
    private fun ExposureTracker.run(
        light: Double,
        readings: Int,
    ): Exposure {
        repeat(readings) { update(luma(light, exposure)) }
        return exposure
    }

    private val start = Exposure(10_000_000, 400)

    /** Light at calibration: the stones at 200. */
    private val calibrated = 200.0 / start.value

    @Test
    fun keepsStillInSteadyLight() {
        val t = ExposureTracker(200f, start)
        assertEquals(start, t.run(calibrated, 500))
    }

    @Test
    fun smallChangesStayInTheBand() {
        val t = ExposureTracker(200f, start)
        assertEquals(start, t.run(calibrated * 1.15, 500))
    }

    @Test
    fun followsTheLightGettingDarker() {
        val t = ExposureTracker(200f, start)
        val e = t.run(calibrated / 3, 1000)
        assertEquals(200f, luma(calibrated / 3, e), 40f)
        assertTrue("longer exposure: $e", e.exposureNs > start.exposureNs)
    }

    /** Brighter, with the stones clipped at first: it takes a few steps down. */
    @Test
    fun comesDownFromClipping() {
        val t = ExposureTracker(200f, start)
        val e = t.run(calibrated * 4, 1000)
        assertEquals(200f, luma(calibrated * 4, e), 40f)
    }

    @Test
    fun raisesIsoOnceExposureTimeIsUsedUp() {
        val t = ExposureTracker(200f, start)
        val e = t.run(calibrated / 8, 2000)
        assertTrue("exposure time at its maximum: $e", e.exposureNs in 29_000_000L..30_000_000L)
        assertTrue("iso raised: $e", e.iso > 400)
        assertEquals(200f, luma(calibrated / 8, e), 40f)
    }

    @Test
    fun ignoresReadingsWithoutBars() {
        val t = ExposureTracker(200f, start)
        repeat(500) { assertNull(t.update(null)) }
        assertEquals(start, t.exposure)
    }
}
