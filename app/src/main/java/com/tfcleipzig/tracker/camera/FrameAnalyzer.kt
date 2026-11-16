package com.tfcleipzig.tracker.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.tfcleipzig.tracker.detection.Ball
import com.tfcleipzig.tracker.detection.BallDetector
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.GoalDetector
import com.tfcleipzig.tracker.detection.TableEndReader

/**
 * Analysis-thread glue: frame -> [TableEndReader] (counters) and [BallDetector] ->
 * [GoalDetector], plus timings for the HUD and the benchmarks.
 */
class FrameAnalyzer(
    private val detector: BallDetector,
    private val goals: GoalDetector,
    private val counters: TableEndReader = TableEndReader(),
    /** Every frame with its reading and read time, while the frame's buffers are valid (recording). */
    private val onFrame: (Frame, TableEndReader.Reading, Float) -> Unit = { _, _, _ -> },
    private val onStats: (Stats) -> Unit,
) : ImageAnalysis.Analyzer {
    data class Stats(
        val fps: Float,
        val width: Int,
        val height: Int,
        /** Sensor capture until the analyzer got the frame. */
        val captureAgeMs: Float,
        /** Copying the YUV planes. */
        val copyMs: Float,
        /** [TableEndReader.read]. */
        val readMs: Float,
        val detectMs: Long,
        /** Sensor capture until the reading was done. */
        val totalMs: Float,
        val ball: Ball?,
        val counters: TableEndReader.Reading,
    )

    private val copier = FrameCopier()
    private var windowStartNs = 0L
    private var framesInWindow = 0
    private var fps = 0f

    override fun analyze(image: ImageProxy) {
        image.use {
            val age = captureAgeMs(it)
            val t0 = System.nanoTime()
            val frame = copier.copy(it)
            val t1 = System.nanoTime()
            val reading = counters.read(frame)
            val t2 = System.nanoTime()
            onFrame(frame, reading, (t2 - t1) / 1e6f)
            val ball = detector.detect(it)
            val t3 = System.nanoTime()
            goals.update(ball, it.imageInfo.timestamp / 1_000_000)

            framesInWindow++
            if (windowStartNs == 0L) windowStartNs = t3
            val elapsed = t3 - windowStartNs
            if (elapsed >= 1_000_000_000L) {
                fps = framesInWindow * 1e9f / elapsed
                framesInWindow = 0
                windowStartNs = t3
            }
            onStats(
                Stats(
                    fps = fps,
                    width = it.width,
                    height = it.height,
                    captureAgeMs = age,
                    copyMs = (t1 - t0) / 1e6f,
                    readMs = (t2 - t1) / 1e6f,
                    detectMs = (t3 - t2) / 1_000_000,
                    totalMs = age + (t2 - t0) / 1e6f,
                    ball = ball,
                    counters = reading,
                ),
            )
        }
    }
}
