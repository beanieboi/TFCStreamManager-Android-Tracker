package com.tfcleipzig.tracker.calibration

import kotlin.math.abs
import kotlin.math.ln

/**
 * Keeps the stones as bright as they were at calibration while the daylight changes, with the
 * camera on manual exposure. Auto exposure can't do this: it meters the whole frame, mostly dark
 * floor around a lit table, and overexposes the counters.
 *
 * Feed it [com.tfcleipzig.tracker.detection.TableEndReader.Reading.stoneLuma] for every reading.
 * Once [window] readings with a brightness have come in, their median is compared with
 * [targetLuma]; more than [band] off, and it proposes an exposure scaled by target / median (at
 * most ×2 or ×½ per step; clipped stones read 255 and understate how far off they are, so it
 * takes a few steps then). Exposure time goes up to [maxExposureNs] at the lowest ISO, beyond
 * that the ISO rises. After a change, [settle] readings are skipped while the camera catches up.
 *
 * Pure logic: the caller applies [Exposure]s to the camera.
 */
class ExposureTracker(
    private val targetLuma: Float,
    exposure: Exposure,
    private val window: Int = 50,
    private val settle: Int = 15,
    private val band: Float = 0.2f,
    private val minExposureNs: Long = 100_000,
    private val maxExposureNs: Long = 30_000_000,
    private val minIso: Int = 100,
    private val maxIso: Int = 2400,
) {
    data class Exposure(
        val exposureNs: Long,
        val iso: Int,
    ) {
        /** Exposure time × ISO: what the image brightness goes with. */
        val value get() = exposureNs.toDouble() * iso
    }

    /** What the camera is set to. */
    var exposure: Exposure = exposure
        private set

    private val lumas = mutableListOf<Float>()
    private var skip = settle

    /** One reading's stone brightness (null: no bars); returns a new exposure to set, or null. */
    fun update(stoneLuma: Float?): Exposure? {
        if (skip > 0) {
            skip--
            return null
        }
        if (stoneLuma == null) return null
        lumas += stoneLuma
        if (lumas.size < window) return null
        val median = lumas.sorted()[lumas.size / 2].coerceAtLeast(1f)
        lumas.clear()
        val ratio = targetLuma / median
        if (abs(ln(ratio)) <= ln(1 + band)) return null
        val next = split(exposure.value * ratio.coerceIn(0.5f, 2f))
        if (next == exposure) return null
        exposure = next
        skip = settle
        return next
    }

    /** Exposure time and ISO for [value]: [BASE_ISO], higher only once [maxExposureNs] isn't enough. */
    private fun split(value: Double): Exposure = split(value, minExposureNs, maxExposureNs, minIso, maxIso)

    companion object {
        /** The ISO exposure time is set at first; noise rises with ISO. */
        const val BASE_ISO = 400

        fun split(
            value: Double,
            minExposureNs: Long = 100_000,
            maxExposureNs: Long = 30_000_000,
            minIso: Int = 100,
            maxIso: Int = 2400,
        ): Exposure {
            val needed = kotlin.math.ceil(value / maxExposureNs).toInt()
            val iso = maxOf(BASE_ISO, needed).coerceIn(minIso, maxIso)
            val ns = (value / iso).toLong().coerceIn(minExposureNs, maxExposureNs)
            return Exposure(ns, iso)
        }
    }
}
