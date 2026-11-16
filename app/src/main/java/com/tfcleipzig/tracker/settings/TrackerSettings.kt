package com.tfcleipzig.tracker.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.detection.Side

/**
 * Per-phone setup, kept across restarts: which table end the phone watches, which team's score
 * that end shows, and the camera zoom that frames both counter bars.
 *
 * Set on the phone (HUD buttons) or over adb, e.g.
 * ```
 * adb shell am start -n com.tfcleipzig.tracker/.MainActivity --es end B --es side away --ef zoom 2.3
 * ```
 */
class TrackerSettings(
    context: Context,
) {
    /** The two ends of a table; the reference photos in `testdata/` are named after them. */
    enum class End { A, B }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var end: End
        get() = prefs.getString(KEY_END, null)?.let { runCatching { End.valueOf(it) }.getOrNull() } ?: End.A
        set(value) = prefs.edit { putString(KEY_END, value.name) }

    /** The team whose score this end's goal bar counts. */
    var side: Side
        get() = prefs.getString(KEY_SIDE, null)?.let { runCatching { Side.valueOf(it) }.getOrNull() } ?: Side.HOME
        set(value) = prefs.edit { putString(KEY_SIDE, value.name) }

    /** Requested zoom; the camera clamps it to its range. */
    var zoom: Float
        get() = prefs.getFloat(KEY_ZOOM, CameraPipeline.DEFAULT_ZOOM)
        set(value) = prefs.edit { putFloat(KEY_ZOOM, value) }

    /**
     * Takes `end` (A/B), `side` (home/away) and `zoom` (float) extras from [intent], removing them.
     * Returns what was applied, for logs; unknown values are ignored.
     */
    fun apply(intent: Intent): List<String> {
        val applied = mutableListOf<String>()
        intent.getStringExtra(KEY_END)?.let { v ->
            End.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }?.let {
                end = it
                applied += "end $it"
            }
        }
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
        listOf(KEY_END, KEY_SIDE, KEY_ZOOM).forEach(intent::removeExtra)
        return applied
    }

    private companion object {
        const val PREFS = "tracker"
        const val KEY_END = "end"
        const val KEY_SIDE = "side"
        const val KEY_ZOOM = "zoom"
    }
}
