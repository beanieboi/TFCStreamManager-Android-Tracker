package com.tfcleipzig.tracker.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The JSON the server's `POST /api/trackers` expects (linux/src/services). */
class TrackerReportTest {
    @Test
    fun usesTheServersFieldNames() {
        val report =
            TrackerReport(
                id = "3f9a1c2b4d5e6f70",
                name = "TrebleDroid_vanilla 6f70",
                appVersion = "0.1",
                ip = "192.168.1.48",
                debugUrl = "http://192.168.1.48:8081/",
                uptimeS = 1234,
                battery = TrackerReport.Battery(87, true, 31.5f),
                wifiRssi = -69,
                tableEnd = "A",
                side = "home",
                reading = TrackerReport.Reading(2, 0, 1, 0.8f, emptyList()),
                camera = TrackerReport.Camera(24.9f, 2.75f, 180, 10_000_000, 400, 196f, 200f, 12.3f),
                calibration = "done",
                sync = "sent",
            )
        val json = Json.parseToJsonElement(Json.encodeToString(TrackerReport.serializer(), report)).jsonObject
        assertEquals(
            setOf(
                "id",
                "name",
                "app_version",
                "ip",
                "debug_url",
                "uptime_s",
                "battery",
                "wifi_rssi",
                "table_end",
                "side",
                "reading",
                "camera",
                "calibration",
                "sync",
            ),
            json.keys,
        )
        assertEquals(setOf("level", "charging", "temperature_c"), (json["battery"] as JsonObject).keys)
        assertEquals(setOf("goals", "sets", "timeouts", "confidence", "problems"), (json["reading"] as JsonObject).keys)
        assertEquals(
            setOf("fps", "zoom", "rotation", "exposure_ns", "iso", "stone_luma", "target_luma", "read_ms"),
            (json["camera"] as JsonObject).keys,
        )
    }
}
