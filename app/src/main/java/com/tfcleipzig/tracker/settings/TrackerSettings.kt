package com.tfcleipzig.tracker.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tfcleipzig.tracker.calibration.Calibrator
import com.tfcleipzig.tracker.calibration.ExposureTracker
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.detection.EndVote
import com.tfcleipzig.tracker.detection.Rotation
import com.tfcleipzig.tracker.detection.Side

/**
 * Per-phone setup, kept across restarts: which team's score the phone's table end shows, and the
 * camera zoom, exposure and frame rotation that read the counters best. The end is detected ([EndVote]); zoom and
 * exposure are found by calibration ([com.tfcleipzig.tracker.calibration.Calibrator]) and can be
 * nudged by hand.
 *
 * Set on the phone (HUD buttons) or over adb, e.g.
 * ```
 * adb shell am start -n com.tfcleipzig.tracker/.MainActivity --es side away --ef zoom 2.3
 * ```
 */
class TrackerSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The team whose score this end's goal bar counts. */
    var side: Side
        get() = prefs.getString(KEY_SIDE, null)?.let { runCatching { Side.valueOf(it) }.getOrNull() } ?: Side.HOME
        set(value) = prefs.edit { putString(KEY_SIDE, value.name) }

    /**
     * Manual exposure (exposure time and ISO) from calibration, kept up to date as the light
     * changes ([com.tfcleipzig.tracker.calibration.ExposureTracker]).
     */
    var exposure: ExposureTracker.Exposure
        get() =
            ExposureTracker.Exposure(
                prefs.getLong(KEY_EXPOSURE_NS, Calibrator.DEFAULT_EXPOSURE.exposureNs),
                prefs.getInt(KEY_ISO, Calibrator.DEFAULT_EXPOSURE.iso),
            )
        set(value) = prefs.edit { putLong(KEY_EXPOSURE_NS, value.exposureNs).putInt(KEY_ISO, value.iso) }

    /** How bright the stones were at calibration: what the exposure is kept at. */
    var stoneLuma: Float
        get() = prefs.getFloat(KEY_STONE_LUMA, Calibrator.TARGET_LUMA)
        set(value) = prefs.edit { putFloat(KEY_STONE_LUMA, value) }

    /** A calibration finished with these [zoom] and [exposure]; without one, the app calibrates on start. */
    var calibrated: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATED, false)
        set(value) = prefs.edit { putBoolean(KEY_CALIBRATED, value) }

    /** How the phone hangs: the turn that puts the counters upright. Found by calibration only. */
    var rotation: Rotation
        get() = prefs.getString(KEY_ROTATION, null)?.let { runCatching { Rotation.valueOf(it) }.getOrNull() } ?: Rotation.R0
        set(value) = prefs.edit { putString(KEY_ROTATION, value.name) }

    /** Requested zoom; the camera clamps it to its range. */
    var zoom: Float
        get() = prefs.getFloat(KEY_ZOOM, CameraPipeline.DEFAULT_ZOOM)
        set(value) = prefs.edit { putFloat(KEY_ZOOM, value) }

    /**
     * Takes `side` (home/away) and `zoom` (float) extras from [intent], removing them.
     * Returns what was applied, for logs; unknown values are ignored.
     */
    fun apply(intent: Intent): List<String> {
        val applied = mutableListOf<String>()
        intent.getStringExtra(KEY_SIDE)?.let { v ->
            Side.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }?.let {
                side = it
                applied += "side $it"
            }
        }
        if (intent.hasExtra(KEY_ZOOM)) {
            val v = intent.getFloatExtra(KEY_ZOOM, Float.NaN)
            if (v.isFinite() && v > 0f) {
                zoom = v
                applied += "zoom $v"
            }
        }
        listOf(KEY_SIDE, KEY_ZOOM).forEach(intent::removeExtra)
        return applied
    }

    private companion object {
        const val PREFS = "tracker"

        // "end" (A/B) was a setting before it was detected; old values are ignored.
        const val KEY_SIDE = "side"
        const val KEY_ZOOM = "zoom"

        // "exposure" was an exposure compensation index before exposure went manual; ignored.
        const val KEY_EXPOSURE_NS = "exposure_ns"
        const val KEY_ISO = "iso"
        const val KEY_STONE_LUMA = "stone_luma"
        const val KEY_CALIBRATED = "calibrated"
        const val KEY_ROTATION = "rotation"
    }
}
