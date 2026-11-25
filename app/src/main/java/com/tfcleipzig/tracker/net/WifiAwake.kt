package com.tfcleipzig.tracker.net

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Keeps Wi-Fi power save off so the phone stays reachable (adb, debug page). With power save on,
 * the WP5's Wi-Fi drops broadcast ARP requests, and other hosts can't reach the phone until it
 * sends something itself.
 *
 * A low-latency Wi-Fi lock: Android turns power save off while it is held, the app is in front and
 * the screen is on. That is always the case on power; on battery [com.tfcleipzig.tracker.power.PowerIdle]
 * turns the screen off after a while and power save comes back, which saves battery.
 * Check with `adb shell iw dev wlan0 get power_save`.
 */
class WifiAwake(context: Context) {
    private val lock =
        context.applicationContext
            .getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tracker:wifi-awake")
            .apply { setReferenceCounted(false) }

    fun start() = lock.acquire()

    fun stop() {
        if (lock.isHeld) lock.release()
    }
}
