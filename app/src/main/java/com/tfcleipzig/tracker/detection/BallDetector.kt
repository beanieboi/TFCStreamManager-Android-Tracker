package com.tfcleipzig.tracker.detection

/**
 * Finds the ball in one camera frame. Runs on the analysis thread; must not keep [frame]
 * after returning. Return null when no ball is visible.
 */
fun interface BallDetector {
    fun detect(frame: Frame): Ball?
}

/**
 * Placeholder until there is a real detector. Candidates for old phones:
 * - color/brightness threshold + blob search on a downscaled frame (cheap, needs a fixed setup)
 * - a small LiteRT (TFLite) model, NNAPI/GPU delegate where the SoC supports it
 */
class NoopBallDetector : BallDetector {
    override fun detect(frame: Frame): Ball? = null
}
