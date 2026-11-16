package com.tfcleipzig.tracker.debug

import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.TableEnd
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-site recordings, made on the phone ([Recorder]) and replayed on the PC
 * (`RecordingReplayTest`). A session is a directory:
 *
 * - `meta.json`: [Meta]
 * - `records.jsonl`: one [Record] per analysed frame, what the phone read and what was true
 * - `frames/<n>.i420`: some frames raw, as the reader saw them ([pack]): Y plane, then U, then V,
 *   no padding. Raw rather than JPEG so a replay reads exactly the same pixels.
 */
object Recording {
    const val META = "meta.json"
    const val RECORDS = "records.jsonl"
    const val FRAMES = "frames"

    val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Meta(
        val device: String,
        val width: Int,
        val height: Int,
        val startedMs: Long,
        val format: String = "i420",
    )

    /** What the stones really show, as entered at the table; null where not given. */
    @Serializable
    data class Truth(
        val goals: Int? = null,
        val sets: Int? = null,
        val timeouts: Int? = null,
    )

    @Serializable
    data class Record(
        val n: Long,
        val timeMs: Long,
        val zoom: Float?,
        val exposure: Int?,
        val exposureLocked: Boolean,
        /** What the camera was doing: "" (normal), "calibrate", "sweep". */
        val mode: String,
        val truth: Truth?,
        /** Someone's hand is over the counters, or stones are being moved: truth may not hold. */
        val handInView: Boolean,
        /** A note entered at this frame. */
        val mark: String? = null,
        val goals: Int?,
        val sets: Int?,
        val timeouts: Int?,
        val end: TableEnd?,
        val endScore: Float,
        val confidence: Float,
        val problems: List<String>,
        val setBar: String?,
        val goalBar: String?,
        val readMs: Float,
        /** `frames/<n>.i420` when this frame was saved. */
        val frame: String? = null,
    )

    /** [frame] as I420 without padding. */
    fun pack(frame: Frame): ByteArray {
        val w = frame.width
        val h = frame.height
        val cw = w / 2
        val ch = h / 2
        val out = ByteArray(w * h + 2 * cw * ch)
        for (y in 0 until h) System.arraycopy(frame.y, y * frame.yRowStride, out, y * w, w)
        var i = w * h
        for (plane in 0..1) {
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    out[i++] = (if (plane == 0) frame.u(cx, cy) else frame.v(cx, cy)).toByte()
                }
            }
        }
        return out
    }

    /** A [pack]ed frame back. */
    fun unpack(
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): Frame {
        val cw = width / 2
        val ch = height / 2
        require(bytes.size == width * height + 2 * cw * ch) { "${bytes.size} bytes isn't a ${width}x$height I420 frame" }
        val y = bytes.copyOfRange(0, width * height)
        val u = bytes.copyOfRange(width * height, width * height + cw * ch)
        val v = bytes.copyOfRange(width * height + cw * ch, bytes.size)
        return Frame(width, height, y, width, u, v, cw, 1)
    }

    /** A [pack]ed frame as NV21, for Android's JPEG encoder. */
    fun nv21(
        packed: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val ySize = width * height
        val c = ySize / 4
        val out = packed.copyOf()
        for (i in 0 until c) {
            out[ySize + 2 * i] = packed[ySize + c + i] // V
            out[ySize + 2 * i + 1] = packed[ySize + i] // U
        }
        return out
    }
}
