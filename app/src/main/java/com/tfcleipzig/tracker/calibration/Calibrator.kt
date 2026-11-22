package com.tfcleipzig.tracker.calibration

import com.tfcleipzig.tracker.calibration.ExposureTracker.Exposure
import com.tfcleipzig.tracker.detection.Rotation
import com.tfcleipzig.tracker.detection.TableEndReader
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the rotation, exposure and zoom that read the counters best, by trying settings and
 * scoring the frames each one gives. Plain logic: [feed] it every reading (analysis thread), carry
 * out the [Command]s it returns on the camera.
 *
 * Exposure is always manual (exposure time and ISO): auto exposure meters the whole frame, mostly
 * dark floor around a lit table, and overexposes the counters.
 *
 * 1. Find: at the start exposure, the saved zoom, else 1×, 1.5×, … until the counters read
 *    (goals, sets and timeouts) in at least half the frames; if no zoom does, again ¼ and 4× as
 *    bright. Bars alone aren't enough: a bar cut off by the frame edge still looks like a bar.
 *    Every frame is read in all four [Rotation]s (the phone can hang any way round); the rotation
 *    that reads most often wins and is kept from then on. The saved rotation wins ties.
 * 2. Exposure: [EXPOSURE_STEPS] around the one that found the bars; the best score wins, ties go to
 *    the one nearest the start. Its stones' brightness becomes the target [ExposureTracker] keeps.
 * 3. Zoom: the largest zoom that keeps [marginPitches] of free frame around the bars. Digital zoom
 *    crops around the frame centre and a margin measured in pitches doesn't change with zoom, so
 *    one reading gives it. Kept if it scores nearly as well as the zoom it came from.
 *
 * Each setting gets [settleFrames] to take effect, then [measureFrames] are scored: the summed
 * confidence of the frames that agree with the most common reading, over all frames. A setting
 * that reads steadily beats one that flickers between values, and both beat one that reads
 * nothing.
 */
class Calibrator(
    private val zoomRange: ClosedFloatingPointRange<Float>,
    private val startZoom: Float,
    private val startExposure: Exposure = DEFAULT_EXPOSURE,
    private val startRotation: Rotation = Rotation.R0,
    private val settleFrames: Int = 10,
    private val measureFrames: Int = 20,
    private val marginPitches: Float = 1.5f,
) {
    sealed interface Command {
        data class Zoom(
            val ratio: Float,
        ) : Command

        /** Manual exposure: auto exposure off. */
        data class SetExposure(
            val exposure: Exposure,
        ) : Command

        data class Done(
            val zoom: Float,
            val exposure: Exposure,
            val score: Float,
            val rotation: Rotation,
            /** The stones' brightness at [exposure]: what [ExposureTracker] keeps. */
            val stoneLuma: Float,
        ) : Command

        /** Nothing worked; the camera is back at [zoom] and [exposure]. */
        data class Failed(
            val reason: String,
            val zoom: Float,
            val exposure: Exposure,
        ) : Command
    }

    private enum class Phase { FIND, EXPOSURE, ZOOM, DONE }

    private var phase = Phase.FIND
    private var zoom = startZoom
    private var exposure = startExposure
    private var frames = 0

    /** The rotation the readings are scored in; during find, the one that leads so far. */
    @Volatile var rotation = startRotation
        private set
    private val window = mutableListOf<TableEndReader.Reading>()

    /** During find, the other rotations' readings of the same frames. */
    private val otherWindows = mutableMapOf<Rotation, MutableList<TableEndReader.Reading>>()

    private val zooms =
        (listOf(startZoom) + FIND_ZOOMS.filter { abs(it - startZoom) > 0.1f })
            .filter { it in zoomRange }

    /** Find order: every zoom at the start exposure, then at the others. */
    private val findSteps =
        FIND_BRIGHTNESS
            .flatMap { b -> zooms.map { z -> z to scaled(startExposure, b) } }
            .toMutableList()

    /** Exposure phase: the steps still to try, and what each one gave. */
    private val exposureSteps = mutableListOf<Exposure>()
    private val exposureResults = mutableMapOf<Exposure, List<TableEndReader.Reading>>()
    private var foundExposure = startExposure

    private var zoomFrom = startZoom
    private var bestScore = 0f
    private var bestReadings = emptyList<TableEndReader.Reading>()

    /** One line for the HUD. */
    @Volatile var status: String = "starting"
        private set

    val finished get() = phase == Phase.DONE

    /** The rotations to read each frame in and [feed]: all four while finding, then just [rotation]. */
    val rotations: List<Rotation>
        get() = if (phase == Phase.FIND) listOf(rotation) + Rotation.entries.filter { it != rotation } else listOf(rotation)

    /** The commands to start with: the first exposure and zoom to try. */
    fun start(): List<Command> = nextFind()

    /** One frame's reading at [width]×[height], in [rotation]; returns what to change, often nothing. */
    fun feed(
        reading: TableEndReader.Reading,
        width: Int,
        height: Int,
    ): List<Command> = feed(mapOf(rotation to reading), width, height)

    /**
     * One frame read in each of [rotations]; [width]×[height] is the frame before turning.
     * Returns what to change, often nothing.
     */
    fun feed(
        readings: Map<Rotation, TableEndReader.Reading>,
        width: Int,
        height: Int,
    ): List<Command> {
        if (phase == Phase.DONE) return emptyList()
        // A frame read before the rotation changed: not in the one being scored.
        val reading = readings[rotation] ?: return emptyList()
        frames++
        if (frames <= settleFrames) return emptyList()
        window += reading
        if (phase == Phase.FIND) {
            for ((r, other) in readings) if (r != rotation) otherWindows.getOrPut(r) { mutableListOf() } += other
        }
        if (window.size < measureFrames) return emptyList()

        val measured = window.toList()
        window.clear()
        val others = otherWindows.toMap()
        otherWindows.clear()
        frames = 0
        return when (phase) {
            Phase.FIND -> afterFind(bestRotation(measured, others))
            Phase.EXPOSURE -> afterExposure(measured, width, height)
            Phase.ZOOM -> afterZoom(measured)
            Phase.DONE -> emptyList()
        }
    }

    /**
     * The rotation whose readings read most often (ties: more bars, the leading one, then the
     * better score); switches [rotation] to it and returns its readings.
     */
    private fun bestRotation(
        measured: List<TableEndReader.Reading>,
        others: Map<Rotation, List<TableEndReader.Reading>>,
    ): List<TableEndReader.Reading> {
        val candidates = listOf(rotation to measured) + others.filterValues { it.size == measured.size }.toList()
        val best =
            candidates.maxWith(
                compareBy<Pair<Rotation, List<TableEndReader.Reading>>> { (_, rs) -> rs.count(::reads) }
                    .thenBy { (_, rs) -> rs.count(::hasBars) }
                    .thenBy { (r, _) -> if (r == rotation) 1 else 0 }
                    .thenBy { (_, rs) -> score(rs) },
            )
        rotation = best.first
        return best.second
    }

    private fun nextFind(): List<Command> {
        val (z, e) = findSteps.removeAt(0)
        zoom = z
        exposure = e
        status = "find bars at zoom %.2f, %s".format(zoom, describe(exposure))
        return listOf(Command.SetExposure(exposure), Command.Zoom(zoom))
    }

    private fun afterFind(measured: List<TableEndReader.Reading>): List<Command> {
        if (measured.count(::reads) * 2 < measured.size) {
            if (findSteps.isEmpty()) return fail("no counters at any zoom or exposure", startZoom, startExposure)
            return nextFind()
        }
        // Found: try exposures around this one, at this zoom.
        foundExposure = exposure
        exposureSteps += EXPOSURE_STEPS.map { scaled(foundExposure, it) }.distinct()
        phase = Phase.EXPOSURE
        return nextExposure()
    }

    private fun nextExposure(): List<Command> {
        exposure = exposureSteps.removeAt(0)
        status = "rotation ${rotation.degrees}°, exposure %s at zoom %.2f".format(describe(exposure), zoom)
        return listOf(Command.SetExposure(exposure))
    }

    private fun afterExposure(
        measured: List<TableEndReader.Reading>,
        rawWidth: Int,
        rawHeight: Int,
    ): List<Command> {
        exposureResults[exposure] = measured
        if (exposureSteps.isNotEmpty()) return nextExposure()

        val scores = exposureResults.mapValues { score(it.value) }
        val top = scores.values.max()
        if (top <= 0f) return fail("nothing read at any exposure", zoom, foundExposure)
        exposure = scores.filterValues { it >= top * TIE }.keys.minBy { abs(ln(it.value / foundExposure.value)) }
        bestScore = scores.getValue(exposure)
        bestReadings = exposureResults.getValue(exposure)

        // Zoom: as far as the margin allows, judged on the chosen exposure's readings.
        val (width, height) = rotation.size(rawWidth, rawHeight)
        val factors = bestReadings.filter(::reads).mapNotNull { zoomFactor(it, width, height, marginPitches) }.sorted()
        val target =
            factors
                .getOrNull(factors.size / 2)
                ?.let { (floor(zoom * it / ZOOM_STEP) * ZOOM_STEP).coerceIn(zoomRange.start, zoomRange.endInclusive) }
        if (target == null || abs(target - zoom) < ZOOM_STEP) return done(bestReadings, bestScore, listOf(Command.SetExposure(exposure)))
        phase = Phase.ZOOM
        zoomFrom = zoom
        zoom = target
        status = "zoom %.2f -> %.2f at %s".format(zoomFrom, target, describe(exposure))
        return listOf(Command.SetExposure(exposure), Command.Zoom(target))
    }

    private fun afterZoom(measured: List<TableEndReader.Reading>): List<Command> {
        val score = score(measured)
        // More pixels per stone is worth a little score; a lot less means the bars got cut off.
        if (score < bestScore * KEEP_ZOOM_SCORE) {
            zoom = zoomFrom
            return done(bestReadings, bestScore, listOf(Command.Zoom(zoom)))
        }
        return done(measured, score, emptyList())
    }

    private fun done(
        measured: List<TableEndReader.Reading>,
        score: Float,
        before: List<Command>,
    ): List<Command> {
        phase = Phase.DONE
        val lumas = measured.mapNotNull { it.stoneLuma }.sorted()
        val luma = lumas.getOrNull(lumas.size / 2) ?: TARGET_LUMA
        status = "done: rotation ${rotation.degrees}°, zoom %.2f, %s, stones %.0f, score %.2f".format(zoom, describe(exposure), luma, score)
        return before + Command.Done(zoom, exposure, score, rotation, luma)
    }

    private fun fail(
        reason: String,
        zoom: Float,
        exposure: Exposure,
    ): List<Command> {
        phase = Phase.DONE
        this.zoom = zoom
        this.exposure = exposure
        status = "failed: $reason"
        return listOf(Command.Zoom(zoom), Command.SetExposure(exposure), Command.Failed(reason, zoom, exposure))
    }

    companion object {
        val FIND_ZOOMS = listOf(1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f)
        const val ZOOM_STEP = 0.05f

        /** Find at the start exposure, then this much darker and brighter. */
        val FIND_BRIGHTNESS = listOf(1.0, 0.25, 4.0)

        /** Exposure phase: these multiples of the exposure that found the bars (half-stop steps over ±2 stops). */
        val EXPOSURE_STEPS = listOf(0.25, 0.354, 0.5, 0.707, 1.0, 1.414, 2.0, 2.83, 4.0)

        /** Where a calibration starts without a saved one: what read well on site, 2026-11-22. */
        val DEFAULT_EXPOSURE = Exposure(10_000_000, 400)

        /** Stone brightness to keep when a calibration measured none. */
        const val TARGET_LUMA = 200f

        /** A larger zoom is kept down to this share of the score it came from. */
        const val KEEP_ZOOM_SCORE = 0.9f

        /** Exposures within this share of the best count as tied. */
        const val TIE = 0.95f

        fun scaled(
            exposure: Exposure,
            factor: Double,
        ): Exposure = ExposureTracker.split(exposure.value * factor)

        fun describe(e: Exposure) = "%.1f ms iso %d".format(e.exposureNs / 1e6, e.iso)

        fun hasBars(reading: TableEndReader.Reading) = reading.line != null && reading.setBar != null && reading.goalBar != null

        /** All three counters read, so the bars are whole and the right ones. */
        fun reads(reading: TableEndReader.Reading) =
            hasBars(reading) && reading.goals != null && reading.sets != null && reading.timeouts != null

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
