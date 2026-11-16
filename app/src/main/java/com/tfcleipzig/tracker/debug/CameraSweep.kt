package com.tfcleipzig.tracker.debug

import android.os.Handler
import android.os.Looper

/**
 * Cycles the camera through zoom × exposure combinations, [dwellMs] each, until stopped, so a
 * recording covers many settings: the replay then shows which one reads best, with the truth
 * entered at the table. Exposure stays unlocked (auto exposure at each compensation).
 * Main thread.
 */
class CameraSweep(
    zooms: List<Float>,
    exposures: IntRange,
    private val dwellMs: Long = 3000,
    private val apply: (zoom: Float, exposure: Int) -> Unit,
) {
    private val settings = zooms.flatMap { z -> exposures.map { e -> z to e } }
    private val handler = Handler(Looper.getMainLooper())
    private var next = 0

    var running = false
        private set

    private val step =
        object : Runnable {
            override fun run() {
                val (zoom, exposure) = settings[next % settings.size]
                next++
                apply(zoom, exposure)
                handler.postDelayed(this, dwellMs)
            }
        }

    fun start() {
        if (running || settings.isEmpty()) return
        running = true
        next = 0
        handler.post(step)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(step)
    }

    companion object {
        /** The zoom and 0.5 either side, within [range]. */
        fun zoomsAround(
            zoom: Float,
            range: ClosedFloatingPointRange<Float>,
        ): List<Float> = listOf(zoom - 0.5f, zoom, zoom + 0.5f).map { it.coerceIn(range.start, range.endInclusive) }.distinct()
    }
}
