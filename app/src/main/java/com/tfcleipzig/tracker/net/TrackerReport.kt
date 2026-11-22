package com.tfcleipzig.tracker.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a phone tells the server about itself every few seconds (`POST /api/trackers`), so the
 * operator sees every phone's battery, reading and camera at a glance and can open its debug page.
 * Field names are the server's (snake_case); the server ignores fields it doesn't know.
 */
@Serializable
data class TrackerReport(
    /** Stable per phone: Android's ANDROID_ID. */
    val id: String,
    val name: String,
    @SerialName("app_version") val appVersion: String,
    val ip: String?,
    @SerialName("debug_url") val debugUrl: String?,
    @SerialName("uptime_s") val uptimeS: Long,
    val battery: Battery,
    @SerialName("wifi_rssi") val wifiRssi: Int?,
    /** Detected end of the table, "A" or "B". */
    @SerialName("table_end") val tableEnd: String?,
    /** The team this end's goal counter counts: "home" or "away". */
    val side: String,
    val reading: Reading?,
    val camera: Camera,
    val calibration: String,
    val sync: String,
    /** "on power, battery 87 %", or why tracking is held back. */
    val power: String = "",
) {
    @Serializable
    data class Battery(
        /** State of charge, 0..100. */
        val level: Int?,
        val charging: Boolean?,
        @SerialName("temperature_c") val temperatureC: Float?,
    )

    @Serializable
    data class Reading(
        val goals: Int?,
        val sets: Int?,
        val timeouts: Int?,
        val confidence: Float,
        val problems: List<String>,
    )

    @Serializable
    data class Camera(
        val fps: Float?,
        val zoom: Float?,
        val rotation: Int,
        @SerialName("exposure_ns") val exposureNs: Long?,
        val iso: Int?,
        @SerialName("stone_luma") val stoneLuma: Float?,
        @SerialName("target_luma") val targetLuma: Float?,
        @SerialName("read_ms") val readMs: Float?,
    )
}
