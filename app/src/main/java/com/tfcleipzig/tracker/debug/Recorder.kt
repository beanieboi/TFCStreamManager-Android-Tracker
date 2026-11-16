package com.tfcleipzig.tracker.debug

import com.tfcleipzig.tracker.debug.Recording.Record
import com.tfcleipzig.tracker.debug.Recording.Truth
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.TableEndReader
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Records a session at the table (see [Recording]): every frame's reading, the truth entered at
 * the table, the camera settings, and raw frames: [rawEveryMs] apart, plus every frame whose
 * reading or truth differs from the frame before (the interesting ones).
 *
 * [record] runs on the analysis thread and only copies; files are written on a thread of their
 * own. When writing falls behind, raw frames are skipped (counted in [skipped]), records never.
 *
 * Also keeps the latest frame, packed, for the debug page's live picture, recording or not.
 */
class Recorder(
    private val root: File,
    private val device: String,
    private val rawEveryMs: Long = 500,
) {
    /** The camera state a record notes; filled in by the app. */
    data class Camera(
        val zoom: Float?,
        val exposure: Int?,
        val exposureLocked: Boolean,
        val mode: String,
    )

    @Volatile var truth: Truth? = null

    @Volatile var handInView = false

    @Volatile private var pendingMark: String? = null

    @Volatile var session: File? = null
        private set

    @Volatile var frames = 0L
        private set

    @Volatile var rawFrames = 0L
        private set

    @Volatile var skipped = 0L
        private set

    /** Latest frame, [Recording.pack]ed, with its size; for the live picture. */
    @Volatile var latest: Triple<ByteArray, Int, Int>? = null
        private set

    private var latestAtMs = 0L
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private var records: Writer? = null
    private var lastRawMs = 0L
    private var previousKey: Any? = null

    val recording get() = session != null

    /** Starts a new session directory under [root]; returns it. */
    @Synchronized
    fun start(): File {
        stop()
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(Date())
        val dir = File(root, name)
        File(dir, Recording.FRAMES).mkdirs()
        frames = 0
        rawFrames = 0
        skipped = 0
        lastRawMs = 0
        previousKey = null
        writer.execute { records = File(dir, Recording.RECORDS).bufferedWriter() }
        session = dir
        return dir
    }

    @Synchronized
    fun stop() {
        session ?: return
        session = null
        writer.execute {
            records?.close()
            records = null
        }
    }

    /** A note to attach to the next record. */
    fun mark(text: String) {
        pendingMark = text
    }

    /** Analysis thread. */
    fun record(
        frame: Frame,
        reading: TableEndReader.Reading,
        camera: Camera,
        readMs: Float,
    ) {
        val now = System.currentTimeMillis()
        if (now - latestAtMs >= LIVE_EVERY_MS) {
            latest = Triple(Recording.pack(frame), frame.width, frame.height)
            latestAtMs = now
        }
        val dir = session ?: return
        if (frames == 0L) {
            val meta = Recording.Meta(device, frame.width, frame.height, now)
            writer.execute { File(dir, Recording.META).writeText(Recording.json.encodeToString(meta)) }
        }
        val n = frames++
        val truth = truth
        val hand = handInView
        val key = listOf(reading.goals, reading.sets, reading.timeouts, truth, hand, camera.zoom, camera.exposure)
        val changed = key != previousKey
        previousKey = key
        var raw: ByteArray? = null
        if (changed || now - lastRawMs >= rawEveryMs) {
            if (writer.queue.size < MAX_QUEUED) {
                raw = Recording.pack(frame)
                lastRawMs = now
            } else {
                skipped++
            }
        }
        val mark = pendingMark.also { pendingMark = null }
        val record =
            Record(
                n = n,
                timeMs = now,
                zoom = camera.zoom,
                exposure = camera.exposure,
                exposureLocked = camera.exposureLocked,
                mode = camera.mode,
                truth = truth,
                handInView = hand,
                mark = mark,
                goals = reading.goals,
                sets = reading.sets,
                timeouts = reading.timeouts,
                end = reading.end,
                endScore = reading.endScore,
                confidence = reading.confidence,
                problems = reading.problems,
                setBar = reading.setBar?.toString(),
                goalBar = reading.goalBar?.toString(),
                readMs = readMs,
                frame = raw?.let { "${Recording.FRAMES}/$n.i420" },
            )
        if (raw != null) rawFrames++
        writer.execute {
            val out = records ?: return@execute
            if (raw != null) File(dir, record.frame!!).writeBytes(raw)
            out.write(Recording.json.encodeToString(record))
            out.write("\n")
            if (n % FLUSH_EVERY == 0L) out.flush()
        }
    }

    private companion object {
        const val LIVE_EVERY_MS = 200L
        const val MAX_QUEUED = 32
        const val FLUSH_EVERY = 30L
    }
}
