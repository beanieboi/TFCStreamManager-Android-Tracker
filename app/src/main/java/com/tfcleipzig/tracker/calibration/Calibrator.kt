package com.tfcleipzig.tracker.calibration

import com.tfcleipzig.tracker.detection.TableEndReader
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the camera zoom and exposure that read the counters best, by trying settings and scoring
 * the frames each one gives. Plain logic: [feed] it every reading (analysis thread), carry out the
 * [Command]s it returns on the camera.
 *
 * 1. Find: the saved zoom, else 1×, 1.5×, … until both bars show up.
 * 2. Zoom: the largest zoom that keeps [marginPitches] of free frame around the bars. Digital
 *    zoom crops around the frame centre and a margin measured in pitches doesn't change with zoom,
 *    so one reading gives it. Kept if it scores no worse than the zoom it came from.
 * 3. Exposure: every exposure compensation step; the best score wins (ties go to the one nearer
 *    0). Then exposure and white balance are locked at it, so players' shirts can't shift them.
 *
 * Each setting gets [settleFrames] to take effect, then [measureFrames] are scored: the summed
 * confidence of the frames that agree with the most common reading, over all frames. A setting
 * that reads steadily beats one that flickers between values, and both beat one that reads
 * nothing.
 */
class Calibrator(
    private val zoomRange: ClosedFloatingPointRange<Float>,
    private val exposureRange: IntRange,
    private val startZoom: Float,
    private val settleFrames: Int = 10,
    private val measureFrames: Int = 20,
    private val marginPitches: Float = 1.5f,
) {
    sealed interface Command {
        data class Zoom(
            val ratio: Float,
        ) : Command

        data class Exposure(
            val index: Int,
            val locked: Boolean,
        ) : Command

        data class Done(
            val zoom: Float,
            val exposure: Int,
            val score: Float,
        ) : Command

        /** Nothing worked; the camera is back at [zoom], exposure 0, unlocked. */
        data class Failed(
            val reason: String,
            val zoom: Float,
        ) : Command
    }

    private enum class Phase { FIND, ZOOM, EXPOSURE, LOCK, DONE }

    private var phase = Phase.FIND
    private var zoom = startZoom
    private var exposure = 0
    private var frames = 0
    private val window = mutableListOf<TableEndReader.Reading>()

    private val findZooms =
        (listOf(startZoom) + FIND_ZOOMS.filter { abs(it - startZoom) > 0.1f })
            .filter { it in zoomRange }
            .toMutableList()
    private var bestZoomScore = -1f
    private val exposureScores = mutableMapOf<Int, Float>()
    private val exposures = exposureRange.toMutableList()
    private var lockSent = false

    /** One line for the HUD. */
    @Volatile var status: String = "starting"
        private set

    val finished get() = phase == Phase.DONE

    /** The commands to start with: exposure unlocked at 0, the first zoom to try. */
    fun start(): List<Command> {
        zoom = findZooms.removeAt(0)
        status = "find bars at zoom %.2f".format(zoom)
        return listOf(Command.Exposure(0, locked = false), Command.Zoom(zoom))
    }

    /** One frame's reading at [width]×[height]; returns what to change, often nothing. */
    fun feed(
        reading: TableEndReader.Reading,
        width: Int,
        height: Int,
    ): List<Command> {
        if (phase == Phase.DONE) return emptyList()
        frames++
        if (frames <= settleFrames) return emptyList()
        if (phase == Phase.LOCK && !lockSent) {
            // The exposure has settled at the chosen value; freeze it, then measure once more.
            lockSent = true
            frames = 0
            return listOf(Command.Exposure(exposure, locked = true))
        }
        window += reading
        if (window.size < measureFrames) return emptyList()

        val measured = window.toList()
        window.clear()
        frames = 0
        val score = score(measured)
        return when (phase) {
            Phase.FIND -> afterFind(measured, score, width, height)
            Phase.ZOOM -> afterZoom(score)
            Phase.EXPOSURE -> afterExposure(score)
            Phase.LOCK -> {
                phase = Phase.DONE
                status = "done: zoom %.2f, exposure %+d, score %.2f".format(zoom, exposure, score)
                listOf(Command.Done(zoom, exposure, score))
            }
            Phase.DONE -> emptyList()
        }
    }

    private fun afterFind(
        measured: List<TableEndReader.Reading>,
        score: Float,
        width: Int,
        height: Int,
    ): List<Command> {
        val withBars = measured.filter { it.line != null && it.setBar != null && it.goalBar != null }
        if (withBars.size * 2 < measured.size) {
            if (findZooms.isEmpty()) {
                phase = Phase.DONE
                status = "failed: no counters at any zoom"
                return listOf(Command.Zoom(startZoom), Command.Failed("no counters found at any zoom", startZoom))
            }
            zoom = findZooms.removeAt(0)
            status = "find bars at zoom %.2f".format(zoom)
            return listOf(Command.Zoom(zoom))
        }
        bestZoomScore = score
        val factor = withBars.mapNotNull { zoomFactor(it, width, height, marginPitches) }.sorted().let { it[it.size / 2] }
        val target = (floor(zoom * factor / ZOOM_STEP) * ZOOM_STEP).coerceIn(zoomRange.start, zoomRange.endInclusive)
        if (abs(target - zoom) < ZOOM_STEP) return startExposure()
        phase = Phase.ZOOM
        val from = zoom
        zoom = target
        status = "zoom %.2f -> %.2f".format(from, target)
        previousZoom = from
        return listOf(Command.Zoom(target))
    }

    private var previousZoom = startZoom

    private fun afterZoom(score: Float): List<Command> {
        // More pixels per stone is worth a little score; a lot less means the bars got cut off.
        if (score < bestZoomScore * KEEP_ZOOM_SCORE) {
            zoom = previousZoom
            return startExposure() + Command.Zoom(zoom)
        }
        return startExposure()
    }

    private fun startExposure(): List<Command> {
        phase = Phase.EXPOSURE
        exposure = exposures.removeAt(0)
        status = "exposure %+d at zoom %.2f".format(exposure, zoom)
        return listOf(Command.Exposure(exposure, locked = false))
    }

    private fun afterExposure(score: Float): List<Command> {
        exposureScores[exposure] = score
        if (exposures.isNotEmpty()) {
            exposure = exposures.removeAt(0)
            status = "exposure %+d at zoom %.2f".format(exposure, zoom)
            return listOf(Command.Exposure(exposure, locked = false))
        }
        val top = exposureScores.values.max()
        if (top <= 0f) {
            phase = Phase.DONE
            status = "failed: nothing read at any exposure"
            return listOf(Command.Exposure(0, locked = false), Command.Failed("nothing read at any exposure", zoom))
        }
        exposure = exposureScores.filterValues { it >= top * TIE }.keys.minBy { abs(it) }
        phase = Phase.LOCK
        status = "lock exposure %+d".format(exposure)
        return listOf(Command.Exposure(exposure, locked = false))
    }

    companion object {
        val FIND_ZOOMS = listOf(1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f)
        const val ZOOM_STEP = 0.05f

        /** A larger zoom is kept down to this share of the score it came from. */
        const val KEEP_ZOOM_SCORE = 0.9f

        /** Exposures within this share of the best count as tied. */
        const val TIE = 0.95f

        /**
         * Summed confidence of the frames that read the most common (goals, sets, timeouts), over
         * all frames.
         */
        fun score(readings: List<TableEndReader.Reading>): Float {
            if (readings.isEmpty()) return 0f
            val full = readings.filter { it.goals != null && it.sets != null && it.timeouts != null }
            val mode =
                full
                    .groupingBy { Triple(it.goals, it.sets, it.timeouts) }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key ?: return 0f
            return full.filter { Triple(it.goals, it.sets, it.timeouts) == mode }.sumOf { it.confidence.toDouble() }.toFloat() /
                readings.size
        }

        /**
         * How much more zoom keeps [marginPitches] of frame around both bars (horizontally) and
         * around the stone line (vertically). Zoom scales distances from the centre and the pitch
         * alike, so a margin in pitches stays put: the point d from the centre stays inside with
         * margin m·p while k·(d + m·p) ≤ half the frame.
         */
        fun zoomFactor(
            reading: TableEndReader.Reading,
            width: Int,
            height: Int,
            marginPitches: Float = 1.5f,
        ): Float? {
            val line = reading.line ?: return null
            val set = reading.setBar ?: return null
            val goal = reading.goalBar ?: return null
            val pitch = (set.pitch + goal.pitch) / 2
            val cx = width / 2f
            val cy = height / 2f
            val dx = max(abs(set.left - cx), abs(goal.right - cx))
            val dy = max(abs(line.centerAt(set.left) - cy), abs(line.centerAt(goal.right) - cy)) + line.stoneSize
            return min(cx / (dx + marginPitches * pitch), cy / (dy + marginPitches * pitch))
        }
    }
}
