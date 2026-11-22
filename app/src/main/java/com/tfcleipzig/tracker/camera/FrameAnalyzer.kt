package com.tfcleipzig.tracker.camera

import com.tfcleipzig.tracker.detection.Ball
import com.tfcleipzig.tracker.detection.BallDetector
import com.tfcleipzig.tracker.detection.CounterReader
import com.tfcleipzig.tracker.detection.DotReader
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
    private val counters: CounterReader = DotReader(),
    private val rotations: () -> List<Rotation> = { listOf(Rotation.R0) },
    /**
     * Frames closer than this to the last one read are dropped unread (asked per frame): keeps
     * the CPU down even where the camera doesn't honour the frame duration it's given.
     */
    private val minIntervalMs: () -> Long = { 0L },
    /** Every frame, turned, with its reading and read time, while the frame's buffers are valid (recording). */
    private val onFrame: (Frame, TableEndReader.Reading, Float) -> Unit = { _, _, _ -> },
    private val onStats: (Stats) -> Unit,
) : CameraPipeline.FrameSink {
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
        /** Copying the YUV planes: done by [CameraPipeline] before the frame gets here, so 0. */
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

    private var lastReadNs = 0L
    private val rotator = FrameRotator()
    private var windowStartNs = 0L
    private var framesInWindow = 0
    private var fps = 0f

    override fun onFrame(
        frame: Frame,
        timestampNs: Long,
    ) {
        run {
            val age = captureAgeMs(timestampNs)
            val t0 = System.nanoTime()
            framesInWindow++
            if (windowStartNs == 0L) windowStartNs = t0
            if (t0 - windowStartNs >= 1_000_000_000L) {
                fps = framesInWindow * 1e9f / (t0 - windowStartNs)
                framesInWindow = 0
                windowStartNs = t0
            }
            if (t0 - lastReadNs < minIntervalMs() * 1_000_000) return
            lastReadNs = t0
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
            val ball = detector.detect(main)
            val t3 = System.nanoTime()
            goals.update(ball, timestampNs / 1_000_000)
            onStats(
                Stats(
                    fps = fps,
                    width = main.width,
                    height = main.height,
                    rawWidth = frame.width,
                    rawHeight = frame.height,
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
