package com.tfcleipzig.tracker.calibration

import com.tfcleipzig.tracker.calibration.Calibrator.Command
import com.tfcleipzig.tracker.detection.BarScan
import com.tfcleipzig.tracker.detection.Rotation
import com.tfcleipzig.tracker.detection.StoneLine
import com.tfcleipzig.tracker.detection.TableEndReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [Calibrator] against a fake camera: the counters sit where they do in the reference photos,
 * scaled about the frame centre by the zoom; they read from 8 px pitch on, while in frame, best at
 * exposure +2, and only when the frame is turned by [FakeCamera.upright].
 */
class CalibratorTest {
    private class FakeCamera(
        var zoom: Float,
        val bestExposure: Int = 2,
        /** Reading fails every n-th frame (hands, motion), 0 = never. */
        val dropEvery: Int = 0,
        /** The turn that puts this camera's counters upright; other turns show nothing. */
        val upright: Rotation = Rotation.R0,
    ) {
        var exposure = 0
        var locked = false
        private var n = 0

        fun frame(rotation: Rotation = upright): TableEndReader.Reading {
            if (rotation != upright) return TableEndReader.Reading(null, null, null, null, null, null)
            n++

            // At zoom 1: set bar from x 264, goal bar to x 409, line at y 271, pitch 5.7 px.
            fun x(at1: Float) = W / 2 + (at1 - W / 2) * zoom
            val pitch = 5.7f * zoom
            val y = H / 2 + (271f - H / 2) * zoom
            val line = StoneLine.Line(y, 0f, (pitch / BarScan.PITCH_PER_BLOB_HEIGHT).toInt(), emptyList())
            val left = x(264f)
            val right = x(409f)
            val inFrame = left > pitch && right < W - pitch && y + pitch < H
            if (!inFrame || pitch < 8f) return TableEndReader.Reading(null, null, null, null, null, null)
            val set = BarScan.Bar(left.toInt(), x(316f).toInt(), emptyList(), pitch, 5)
            val goal = BarScan.Bar(x(354f).toInt(), right.toInt(), emptyList(), pitch, 7)
            if (dropEvery > 0 && n % dropEvery == 0) return TableEndReader.Reading(null, null, null, line, set, goal)
            val confidence = (1f - 0.2f * abs(exposure - bestExposure)).coerceAtLeast(0f)
            return TableEndReader.Reading(3, 1, 0, line, set, goal, confidence = confidence)
        }

        fun apply(commands: List<Command>): Command? {
            for (c in commands) {
                when (c) {
                    is Command.Zoom -> zoom = c.ratio.coerceIn(1f, 4f)
                    is Command.Exposure -> {
                        exposure = c.index
                        locked = c.locked
                    }
                    is Command.Done, is Command.Failed -> return c
                }
            }
            return null
        }
    }

    private fun run(
        camera: FakeCamera,
        calibrator: Calibrator,
    ): Command {
        camera.apply(calibrator.start())
        repeat(5000) {
            val readings = calibrator.rotations.associateWith { camera.frame(it) }
            camera.apply(calibrator.feed(readings, W.toInt(), H.toInt()))?.let { return it }
        }
        error("calibration didn't finish: ${calibrator.status}")
    }

    @Test
    fun findsTheBarsZoomsInAndPicksTheBestExposure() {
        val camera = FakeCamera(zoom = 1f)
        val result = run(camera, Calibrator(1f..4f, -4..4, startZoom = 1f))
        result as Command.Done
        // Bars show up at 1.5x; the margin of 1.5 pitches then allows 3.25x.
        assertEquals(3.25f, result.zoom, 0.01f)
        assertEquals(2, result.exposure)
        assertEquals(3.25f, camera.zoom, 0.01f)
        assertEquals(2, camera.exposure)
        assertTrue("exposure locked", camera.locked)
        assertTrue("score ${result.score}", result.score > 0.9f)
    }

    @Test
    fun findsHowThePhoneHangs() {
        val camera = FakeCamera(zoom = 1f, upright = Rotation.R180)
        val calibrator = Calibrator(1f..4f, -4..4, startZoom = 1f)
        val result = run(camera, calibrator) as Command.Done
        assertEquals(Rotation.R180, result.rotation)
        assertEquals(3.25f, result.zoom, 0.01f)
        assertEquals(listOf(Rotation.R180), calibrator.rotations)
    }

    @Test
    fun keepsTheSavedRotationWhenItStillReads() {
        val camera = FakeCamera(zoom = 1f, upright = Rotation.R180)
        val calibrator = Calibrator(1f..4f, -4..4, startZoom = 3.25f, startRotation = Rotation.R180)
        assertEquals(Rotation.R180, calibrator.rotations.first())
        val result = run(camera, calibrator) as Command.Done
        assertEquals(Rotation.R180, result.rotation)
    }

    @Test
    fun startsFromTheSavedZoom() {
        val camera = FakeCamera(zoom = 1f)
        val result = run(camera, Calibrator(1f..4f, -4..4, startZoom = 2.5f)) as Command.Done
        assertEquals(3.25f, result.zoom, 0.01f)
    }

    @Test
    fun keepsTheMarginAtTheChosenZoom() {
        val camera = FakeCamera(zoom = 1f)
        run(camera, Calibrator(1f..4f, -4..4, startZoom = 1f))
        val r = camera.frame()
        val margin = minOf(r.setBar!!.left, W.toInt() - r.goalBar!!.right) / r.goalBar!!.pitch
        assertTrue("margin $margin pitches", margin >= 1.4f)
    }

    @Test
    fun copesWithFramesThatDontRead() {
        val camera = FakeCamera(zoom = 1f, dropEvery = 4)
        val result = run(camera, Calibrator(1f..4f, -4..4, startZoom = 1f)) as Command.Done
        assertEquals(2, result.exposure)
    }

    @Test
    fun failsAndRestoresTheZoomWithoutCounters() {
        val camera = FakeCamera(zoom = 1f)
        val calibrator = Calibrator(1f..4f, -4..4, startZoom = 2f)
        camera.apply(calibrator.start())
        var result: Command? = null
        repeat(2000) {
            if (result == null) {
                result = camera.apply(calibrator.feed(TableEndReader.Reading(null, null, null, null, null, null), W.toInt(), H.toInt()))
            }
        }
        result as Command.Failed
        assertEquals(2f, camera.zoom, 0.01f)
    }

    @Test
    fun scoreRewardsSteadyReadings() {
        fun r(goals: Int) = TableEndReader.Reading(goals, 0, 0, null, null, null, confidence = 1f)
        val steady = Calibrator.score(List(10) { r(3) })
        val flicker = Calibrator.score(List(10) { r(if (it % 2 == 0) 3 else 4) })
        assertEquals(1f, steady, 1e-6f)
        assertEquals(0.5f, flicker, 1e-6f)
    }

    private companion object {
        const val W = 640f
        const val H = 480f
    }
}
