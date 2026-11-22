package com.tfcleipzig.tracker.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.tfcleipzig.tracker.detection.Ball
import com.tfcleipzig.tracker.detection.BallDetector
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.FrameRotator
import com.tfcleipzig.tracker.detection.GoalDetector
import com.tfcleipzig.tracker.detection.Rotation
import com.tfcleipzig.tracker.detection.TableEndReader

/**
 * Analysis-thread glue: frame -> [TableEndReader] (counters) and [BallDetector] ->
 * [GoalDetector], plus timings for the HUD and the benchmarks.
 *
 * The counters are read in each of [rotations] (asked per frame): normally just the calibrated
 * one, all four while calibration searches. The first is the one that counts: it goes to
 * [onFrame] and [Stats.counters].
 */
class FrameAnalyzer(
    private val detector: BallDetector,
    private val goals: GoalDetector,
    private val counters: TableEndReader = TableEndReader(),
    private val rotations: () -> List<Rotation> = { listOf(Rotation.R0) },
    /** Every frame, turned, with its reading and read time, while the frame's buffers are valid (recording). */
    private val onFrame: (Frame, TableEndReader.Reading, Float) -> Unit = { _, _, _ -> },
    private val onStats: (Stats) -> Unit,
) : ImageAnalysis.Analyzer {
    data class Stats(
        val fps: Float,
        /** The frame as read, i.e. turned by [rotation]. */
        val width: Int,
        val height: Int,
        /** The camera frame, before turning. */
        val rawWidth: Int,
        val rawHeight: Int,
        val rotation: Rotation,
        /** Sensor capture until the analyzer got the frame. */
        val captureAgeMs: Float,
        /** Copying the YUV planes. */
        val copyMs: Float,
        /** [TableEndReader.read], all rotations. */
        val readMs: Float,
        val detectMs: Long,
        /** Sensor capture until the reading was done. */
        val totalMs: Float,
        val ball: Ball?,
        val counters: TableEndReader.Reading,
        /** The reading in each rotation asked for, [rotation] first. */
        val readings: Map<Rotation, TableEndReader.Reading>,
    )

    private val copier = FrameCopier()
    private val rotator = FrameRotator()
    private var windowStartNs = 0L
    private var framesInWindow = 0
    private var fps = 0f

    override fun analyze(image: ImageProxy) {
        image.use {
            val age = captureAgeMs(it)
            val t0 = System.nanoTime()
            framesInWindow++
            if (windowStartNs == 0L) windowStartNs = t0
            if (t0 - windowStartNs >= 1_000_000_000L) {
                fps = framesInWindow * 1e9f / (t0 - windowStartNs)
                framesInWindow = 0
                windowStartNs = t0
            }
            val frame = copier.copy(it)
            val t1 = System.nanoTime()
            val asked = rotations().ifEmpty { listOf(Rotation.R0) }
            val readings = LinkedHashMap<Rotation, TableEndReader.Reading>()
            var main = frame
            for (r in asked) {
                val turned = rotator.rotate(frame, r)
                if (readings.isEmpty()) main = turned
                readings[r] = counters.read(turned)
            }
            val reading = readings.getValue(asked[0])
            val t2 = System.nanoTime()
            onFrame(main, reading, (t2 - t1) / 1e6f)
            val ball = detector.detect(it)
            val t3 = System.nanoTime()
            goals.update(ball, it.imageInfo.timestamp / 1_000_000)
            onStats(
                Stats(
                    fps = fps,
                    width = main.width,
                    height = main.height,
                    rawWidth = it.width,
                    rawHeight = it.height,
                    rotation = asked[0],
                    captureAgeMs = age,
                    copyMs = (t1 - t0) / 1e6f,
                    readMs = (t2 - t1) / 1e6f,
                    detectMs = (t3 - t2) / 1_000_000,
                    totalMs = age + (t2 - t0) / 1e6f,
                    ball = ball,
                    counters = reading,
                    readings = readings,
                ),
            )
        }
    }
}
