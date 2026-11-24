package com.tfcleipzig.tracker.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Holds tracking back while the battery is nearly empty. A phone that ran flat boots as soon as it
 * gets power again (tools/system/tracker-autoboot.rc), and the camera would then draw more than the
 * charger delivers. So below [openAt] % the camera stays off and the phone just charges; tracking
 * starts at [openAt] % and stops again only below [closeAt] %, so it doesn't flicker around one
 * level.
 */
class BatteryGate(
    private val context: Context,
    /** Called on the main thread when the gate opens or closes, with the battery level. */
    private val onChange: (open: Boolean, level: Int) -> Unit,
    private val openAt: Int = 15,
    private val closeAt: Int = 10,
) {
    /** Tracking may run. */
    var open = true
        private set

    /** Battery level in %, -1 before the first reading. */
    var level = -1
        private set

    private var registered = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = update(intent)
        }

    fun start() {
        if (registered) return
        registered = true
        // Sticky: the current state comes back right away.
        context
            .registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            ?.let(::update)
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }

    private fun update(intent: Intent) {
        val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (l < 0 || scale <= 0) return
        level = l * 100 / scale
        val next = decide(open, level, openAt, closeAt)
        val changed = next != open || !reported
        reported = true
        open = next
        if (changed) {
            Log.i(TAG, "battery $level %: tracking ${if (open) "on" else "off until $openAt %"}")
            onChange(open, level)
        }
    }

    /** The first reading is always passed on, so the app knows where it stands. */
    private var reported = false

    companion object {
        private const val TAG = "BatteryGate"

        /** Open from [openAt] % on, closed below [closeAt] %, else as it was. */
        fun decide(
            open: Boolean,
            level: Int,
            openAt: Int,
            closeAt: Int,
        ): Boolean =
            when {
                level >= openAt -> true
                level < closeAt -> false
                else -> open
            }
    }
}
