package com.tfcleipzig.tracker.debug

import com.tfcleipzig.tracker.debug.Recording.Record
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.TableEndReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Replays recordings made at the table (see [Recording], docs/on-site-debugging.md):
 *
 * ```
 * adb pull /sdcard/Android/data/com.tfcleipzig.tracker/files/recordings/ recordings/
 * RECORDING=recordings ./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*' --rerun
 * ```
 *
 * `RECORDING` is one session directory or a directory of them, relative to the project root.
 * For every camera setting (mode, zoom, exposure, locked) it reports correct / unread / wrong
 * against the truth entered at the table, twice: as the phone read it live (every frame), and as
 * the current [TableEndReader] reads the saved raw frames, so a change to the reader can be tried
 * on real footage. Frames marked with hands in view are left out. Wrong replayed frames are saved
 * as PNG next to the report, in `build/reports/recording/<session>/`.
 */
class RecordingReplayTest {
    private class Tally {
        var frames = 0
        var confidence = 0.0
        val correct = IntArray(3)
        val unread = IntArray(3)
        val wrong = IntArray(3)
        val labelled = IntArray(3)

        fun add(
            truth: Recording.Truth,
            got: List<Int?>,
            conf: Float,
        ): List<Int> {
            frames++
            confidence += conf
            val wrongAt = mutableListOf<Int>()
            listOf(truth.goals, truth.sets, truth.timeouts).forEachIndexed { i, want ->
                want ?: return@forEachIndexed
                labelled[i]++
                when (got[i]) {
                    want -> correct[i]++
                    null -> unread[i]++
                    else -> {
                        wrong[i]++
                        wrongAt += i
                    }
                }
            }
            return wrongAt
        }

        fun line(key: String): String =
            "%-34s %5d  %s  conf %.2f".format(
                key,
                frames,
                (0..2).joinToString("  ") { i ->
                    if (labelled[i] ==
                        0
                    ) {
                        "%-17s".format("-")
                    } else {
                        "%4d/%-4d ?%-3d !%-3d".format(correct[i], labelled[i], unread[i], wrong[i])
                    }
                },
                if (frames == 0) 0.0 else confidence / frames,
            )
    }

    @Test
    fun replay() {
        val path = System.getenv("RECORDING")
        assumeTrue("set RECORDING to a recording directory", path != null)
        val root = File(path!!).takeIf { it.isAbsolute } ?: File("..", path)
        val sessions =
            if (File(root, Recording.META).exists()) {
                listOf(root)
            } else {
                root
                    .listFiles()
                    .orEmpty()
                    .filter { File(it, Recording.META).exists() }
                    .sortedBy { it.name }
            }
        assumeTrue("no recordings in ${root.absolutePath}", sessions.isNotEmpty())
        sessions.forEach(::replay)
    }

    private fun replay(session: File) {
        val meta = Recording.json.decodeFromString<Recording.Meta>(File(session, Recording.META).readText())
        val records =
            File(session, Recording.RECORDS)
                .readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { Recording.json.decodeFromString<Record>(it) }.getOrNull() }
        val out = File("build/reports/recording/${session.name}").apply { mkdirs() }
        val reader = TableEndReader()
        val live = sortedMapOf<String, Tally>()
        val replayed = sortedMapOf<String, Tally>()
        var differs = 0
        var dumped = 0

        for (r in records) {
            val truth = r.truth ?: continue
            if (r.handInView) continue
            val key = key(r)
            live.getOrPut(key) { Tally() }.add(truth, listOf(r.goals, r.sets, r.timeouts), r.confidence)
            val file = r.frame?.let { File(session, it) }?.takeIf { it.exists() } ?: continue
            val frame = Recording.unpack(file.readBytes(), meta.width, meta.height)
            val now = reader.read(frame)
            if (listOf(now.goals, now.sets, now.timeouts) != listOf(r.goals, r.sets, r.timeouts)) differs++
            val wrong = replayed.getOrPut(key) { Tally() }.add(truth, listOf(now.goals, now.sets, now.timeouts), now.confidence)
            if (wrong.isNotEmpty() && dumped < MAX_DUMPS) {
                val names = wrong.joinToString("-") { listOf("goals", "sets", "timeouts")[it] }
                ImageIO.write(image(frame), "png", File(out, "%06d-wrong-%s.png".format(r.n, names)))
                dumped++
            }
        }

        val header = "%-34s %5s  %-17s  %-17s  %-17s".format("mode zoom exposure", "n", "goals", "sets", "timeouts")
        val report =
            buildString {
                appendLine("${session.name}: ${meta.device}, ${meta.width}x${meta.height}, ${records.size} frames")
                appendLine("labelled, no hands: ${live.values.sumOf { it.frames }} frames, ${replayed.values.sumOf { it.frames }} raw")
                appendLine("  correct/labelled ?unread !wrong")
                appendLine()
                appendLine("as read live on the phone")
                appendLine(header)
                live.forEach { (k, t) -> appendLine(t.line(k)) }
                appendLine()
                appendLine("raw frames replayed with the current reader ($differs read differently from the phone)")
                appendLine(header)
                replayed.forEach { (k, t) -> appendLine(t.line(k)) }
                val marks = records.filter { it.mark != null }
                if (marks.isNotEmpty()) {
                    appendLine()
                    appendLine("notes")
                    marks.forEach { appendLine("  frame ${it.n}: ${it.mark}") }
                }
            }
        println(report)
        File(out.parentFile, "${session.name}.txt").writeText(report)
    }

    private fun key(r: Record): String =
        "%-9s %5s %4s %s".format(
            r.mode.ifEmpty { "normal" },
            r.zoom?.let { "%.2f".format(it) } ?: "?",
            r.exposure?.let { "%+d".format(it) } ?: "?",
            if (r.exposureLocked) "locked" else "auto",
        )

    private fun image(frame: Frame): BufferedImage {
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val l = frame.luma(x, y).toDouble()
                val u = frame.u(x / 2, y / 2) - 128.0
                val v = frame.v(x / 2, y / 2) - 128.0
                val r = (l + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (l - 0.344 * u - 0.714 * v).toInt().coerceIn(0, 255)
                val b = (l + 1.772 * u).toInt().coerceIn(0, 255)
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        return img
    }

    private companion object {
        const val MAX_DUMPS = 200
    }
}
