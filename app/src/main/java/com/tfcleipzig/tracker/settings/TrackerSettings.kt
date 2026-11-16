package com.tfcleipzig.tracker.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.detection.EndVote
import com.tfcleipzig.tracker.detection.Side

/**
 * Per-phone setup, kept across restarts: which team's score the phone's table end shows, and the
 * camera zoom and exposure that read the counters best. The end is detected ([EndVote]); zoom and
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

    /** Exposure compensation index from calibration; locked with white balance when [calibrated]. */
    var exposure: Int
        get() = prefs.getInt(KEY_EXPOSURE, 0)
        set(value) = prefs.edit { putInt(KEY_EXPOSURE, value) }

    /** A calibration finished with these [zoom] and [exposure]; without one, the app calibrates on start. */
    var calibrated: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATED, false)
        set(value) = prefs.edit { putBoolean(KEY_CALIBRATED, value) }

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
        const val KEY_EXPOSURE = "exposure"
        const val KEY_CALIBRATED = "calibrated"
    }
}
