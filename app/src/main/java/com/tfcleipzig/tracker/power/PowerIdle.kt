package com.tfcleipzig.tracker.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the phone alive through power cuts instead of letting the camera drain the battery.
 *
 * A phone that runs flat switches off, and plugged in while off it stays in the charger screen
 * (see docs/remote-management.md): nobody can bring it back without touching it. So on battery
 * the phone idles: after [graceMs] (power blips don't count) the screen may go off, which closes
 * the camera (CameraX follows the activity's lifecycle); Wi-Fi stays up. When power comes back
 * the screen comes on, the camera reopens with the saved calibration, and the tracker carries on.
 *
 * Screen timeout is a system setting: the app needs WRITE_SETTINGS (tools/setup-phone.sh grants
 * it). Without it the phone stays on and [onChange] says why.
 */
class PowerIdle(
    private val context: Context,
    /** Keep the screen on from the app's side ([android.view.View.setKeepScreenOn]). */
    private val keepScreenOn: (Boolean) -> Unit,
    /** "on power", "on battery, idle in 60 s", ... for the HUD; main thread. */
    private val onChange: (String) -> Unit = {},
    private val graceMs: Long = 60_000,
    private val idleTimeoutMs: Int = 15_000,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    /** On external power right now. */
    var powered = true
        private set

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> onPower()
                    Intent.ACTION_POWER_DISCONNECTED -> onBattery()
                }
            }
        }

    private val idle = Runnable { goIdle() }

    fun start() {
        if (registered) return
        registered = true
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        val plugged =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (plugged != 0) onPower(wake = false) else onBattery()
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
        handler.removeCallbacks(idle)
    }

    private fun onPower(wake: Boolean = true) {
        powered = true
        handler.removeCallbacks(idle)
        keepScreenOn(true)
        setScreenTimeout(Int.MAX_VALUE)
        if (wake) wakeScreen()
        Log.i(TAG, "on power")
        onChange("on power")
    }

    private fun onBattery() {
        powered = false
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, graceMs)
        Log.i(TAG, "on battery, idle in ${graceMs / 1000} s")
        onChange("on battery, idle in ${graceMs / 1000} s")
    }

    private fun goIdle() {
        if (powered) return
        keepScreenOn(false)
        val ok = setScreenTimeout(idleTimeoutMs)
        val status = if (ok) "on battery, idle" else "on battery, can't idle: no WRITE_SETTINGS"
        Log.i(TAG, status)
        onChange(status)
    }

    /** False when the app may not change system settings. */
    private fun setScreenTimeout(ms: Int): Boolean {
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "no WRITE_SETTINGS, screen timeout unchanged")
            return false
        }
        return Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
    }

    /**
     * Turns the screen on: the activity resumes and reopens the camera. Needs the
     * TURN_SCREEN_ON app op (tools/setup-phone.sh); without it the screen stays off.
     */
    @Suppress("DEPRECATION") // the plain way to switch the screen on from a background app
    private fun wakeScreen() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        pm
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "tracker:power")
            .acquire(WAKE_MS)
    }

    private companion object {
        const val TAG = "PowerIdle"
        const val WAKE_MS = 5_000L
    }
}
