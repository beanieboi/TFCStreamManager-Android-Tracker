package com.tfcleipzig.tracker.debug

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.tfcleipzig.tracker.debug.DebugServer.Request
import com.tfcleipzig.tracker.debug.DebugServer.Response
import com.tfcleipzig.tracker.detection.TableEnd
import com.tfcleipzig.tracker.detection.TableEndReader
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream

/**
 * The debug page (`assets/debug.html`) and what it calls, on [DebugServer]: live picture, the
 * reading, truth entry, recording, camera sweep, calibration. See docs/on-site-debugging.md.
 */
class DebugPage(
    private val context: Context,
    private val recorder: Recorder,
    private val host: Host,
) {
    /** What the page shows and does in the app; the actions are called on a server thread. */
    interface Host {
        val reading: TableEndReader.Reading?
        val end: TableEnd?
        val fps: Float
        val zoom: Float?
        val exposure: Int?
        val exposureLocked: Boolean
        val mode: String
        val calibration: String

        /** Degrees the frames are turned clockwise before reading. */
        val rotation: Int

        /** Last score sync with the server. */
        val sync: String
        val sweeping: Boolean

        /** Manual sensor exposure, e.g. "8000 us iso 100"; null when auto. */
        val manual: String?

        fun calibrate()

        /** Last focus on the counters: "auto", "focusing", "on counters", ... */
        val focus: String

        /** Manual exposure at [exposureNs] and [iso]; [exposureNs] 0 goes back to auto. */
        fun manual(
            exposureNs: Long,
            iso: Int,
        )

        /** Focus on the counters at the next reading that finds them, or on ([x], [y]) of the camera frame, [size] wide. */
        fun focus(
            x: Float?,
            y: Float?,
            size: Float?,
        )

        /** Zoom by hand; kept as the phone's zoom. */
        fun zoom(ratio: Float)

        fun sweep(on: Boolean)
    }

    fun handle(request: Request): Response {
        if (request.method == "POST") return post(request)
        return when (request.path) {
            "/", "/index.html" -> Response(200, "text/html; charset=utf-8", context.assets.open("debug.html").use { it.readBytes() })
            "/state" -> Response.json(state())
            "/frame.jpg" -> frame()
            else -> Response.notFound
        }
    }

    private fun post(request: Request): Response {
        val p = request.params
        when (request.path) {
            "/truth" -> {
                var t = recorder.truth ?: Recording.Truth()
                p["goals"]?.let { t = t.copy(goals = it.toIntOrNull()) }
                p["sets"]?.let { t = t.copy(sets = it.toIntOrNull()) }
                p["timeouts"]?.let { t = t.copy(timeouts = it.toIntOrNull()) }
                recorder.truth = t
                // Entering what the stones show now means the hands are off them.
                recorder.handInView = false
            }
            "/hand" -> recorder.handInView = p["on"] == "1"
            "/record" -> if (p["on"] == "1") recorder.start() else recorder.stop()
            "/mark" -> p["text"]?.takeIf { it.isNotBlank() }?.let { recorder.mark(it) }
            "/calibrate" -> host.calibrate()
            "/sweep" -> host.sweep(p["on"] == "1")
            "/manual" -> host.manual(p["ns"]?.toLongOrNull() ?: 0L, p["iso"]?.toIntOrNull() ?: 100)
            "/focus" -> host.focus(p["x"]?.toFloatOrNull(), p["y"]?.toFloatOrNull(), p["size"]?.toFloatOrNull())
            "/zoom" -> p["ratio"]?.toFloatOrNull()?.let(host::zoom)
            else -> return Response.notFound
        }
        return Response.json(state())
    }

    private fun state(): String {
        val r = host.reading
        val t = recorder.truth
        return buildJsonObject {
            putJsonObject("reading") {
                put("goals", r?.goals)
                put("sets", r?.sets)
                put("timeouts", r?.timeouts)
                put("end", r?.end?.name)
                put("endScore", r?.endScore ?: 0f)
                put("confidence", r?.confidence ?: 0f)
                putJsonArray("problems") { r?.problems?.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("truth") {
                put("goals", t?.goals)
                put("sets", t?.sets)
                put("timeouts", t?.timeouts)
            }
            put("hand", recorder.handInView)
            put("end", host.end?.name)
            put("fps", host.fps)
            put("zoom", host.zoom?.let { "%.2f".format(java.util.Locale.ROOT, it) })
            put("exposure", host.exposure)
            put("locked", host.exposureLocked)
            put("mode", host.mode)
            put("calibration", host.calibration)
            put("rotation", host.rotation)
            put("sync", host.sync)
            put("manual", host.manual)
            put("focus", host.focus)
            put("sweep", host.sweeping)
            put("recording", recorder.recording)
            put("session", recorder.session?.name)
            put("frames", recorder.frames)
            put("rawFrames", recorder.rawFrames)
            put("skipped", recorder.skipped)
        }.toString()
    }

    private fun frame(): Response {
        val (packed, w, h) = recorder.latest ?: return Response.text("no frame yet", status = 503)
        val out = ByteArrayOutputStream()
        YuvImage(Recording.nv21(packed, w, h), ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
        return Response(200, "image/jpeg", out.toByteArray())
    }

    private companion object {
        const val JPEG_QUALITY = 80
    }
}
