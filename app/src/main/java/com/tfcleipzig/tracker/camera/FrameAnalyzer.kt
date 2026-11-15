package com.tfcleipzig.tracker.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.tfcleipzig.tracker.detection.Ball
import com.tfcleipzig.tracker.detection.BallDetector
import com.tfcleipzig.tracker.detection.GoalDetector

/** Analysis-thread glue: frame -> [BallDetector] -> [GoalDetector], plus stats for the HUD. */
class FrameAnalyzer(
    private val detector: BallDetector,
    private val goals: GoalDetector,
    private val onStats: (Stats) -> Unit,
) : ImageAnalysis.Analyzer {
    data class Stats(
        val fps: Float,
        val detectMs: Long,
        val width: Int,
        val height: Int,
        val ball: Ball?,
    )

    private var windowStartNs = 0L
    private var framesInWindow = 0
    private var fps = 0f

    override fun analyze(image: ImageProxy) {
        image.use {
            val start = System.nanoTime()
            val ball = detector.detect(it)
            val detectMs = (System.nanoTime() - start) / 1_000_000
            goals.update(ball, it.imageInfo.timestamp / 1_000_000)

            framesInWindow++
            val now = System.nanoTime()
            if (windowStartNs == 0L) windowStartNs = now
            val elapsed = now - windowStartNs
            if (elapsed >= 1_000_000_000L) {
                fps = framesInWindow * 1e9f / elapsed
                framesInWindow = 0
                windowStartNs = now
            }
            onStats(Stats(fps, detectMs, it.width, it.height, ball))
        }
    }
}
