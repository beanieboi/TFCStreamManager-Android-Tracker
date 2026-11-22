package com.tfcleipzig.tracker.debug

import com.tfcleipzig.tracker.debug.Recording.Record
import com.tfcleipzig.tracker.detection.DotReader
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.TableEndReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * [TableEndReader] and [DotReader] side by side on recorded raw frames with a truth and no hands:
 * correct / unread / wrong per reader, per session and in total. Wrong dot reads are saved as PNG.
 *
 * ```
 * RECORDING=recordings ./gradlew :app:testDebugUnitTest --tests '*ReaderComparisonTest*' --rerun
 * ```
 * Report in `build/reports/reader-comparison.txt`.
 */
class ReaderComparisonTest {
    private class Tally {
        var correct = 0
        var unread = 0
        var wrong = 0

        fun add(
            truth: Recording.Truth,
            read: TableEndReader.Reading,
        ): Boolean {
            val want = listOf(truth.goals, truth.sets, truth.timeouts)
            val got = listOf(read.goals, read.sets, read.timeouts)
            val isWrong = want.indices.any { want[it] != null && got[it] != null && got[it] != want[it] }
            when {
                isWrong -> wrong++
                want.indices.all { want[it] == null || got[it] == want[it] } -> correct++
                else -> unread++
            }
            return isWrong
        }

        override fun toString() = "%4d correct %4d unread %4d wrong".format(correct, unread, wrong)
    }

    @Test
    fun compare() {
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

        val out = File("build/reports/reader-comparison").apply { mkdirs() }
        val old = TableEndReader()
        val dots = DotReader()
        val oldTotal = Tally()
        val dotTotal = Tally()
        val report = StringBuilder()
        val dotProblems = mutableMapOf<String, Int>()
        val oldWrong = mutableListOf<String>()
        for (session in sessions) {
            val meta = Recording.json.decodeFromString<Recording.Meta>(File(session, Recording.META).readText())
            val records =
                File(session, Recording.RECORDS)
                    .readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Recording.json.decodeFromString<Record>(it) }.getOrNull() }
            val oldTally = Tally()
            val dotTally = Tally()
            for (r in records) {
                val truth = r.truth ?: continue
                if (r.handInView || r.mode != "") continue
                val file = r.frame?.let { File(session, it) }?.takeIf { it.exists() } ?: continue
                val frame = Recording.unpack(file.readBytes(), meta.width, meta.height)
                val o = old.read(frame)
                if (oldTally.add(truth, o)) {
                    oldWrong +=
                        "${session.name} n=${r.n} truth ${truth.goals}/${truth.sets}/${truth.timeouts} old ${o.goals}/${o.sets}/${o.timeouts}"
                }
                oldTotal.add(truth, o)
                val d = dots.read(frame)
                for (p in d.problems) dotProblems.merge(p.replace(Regex("[0-9.,]+"), "#"), 1, Int::plus)
                dotTotal.add(truth, d)
                if (dotTally.add(truth, d)) {
                    val name =
                        "%s-%06d-truth-%s-%s-%s-read-%s-%s-%s.png".format(
                            session.name,
                            r.n,
                            truth.goals,
                            truth.sets,
                            truth.timeouts,
                            d.goals,
                            d.sets,
                            d.timeouts,
                        )
                    ImageIO.write(image(frame), "png", File(out, name))
                }
            }
            report.appendLine("%-22s old %s | dots %s".format(session.name, oldTally, dotTally))
        }
        report.appendLine("%-22s old %s | dots %s".format("total", oldTotal, dotTotal))
        report.appendLine(
            "dot reader problems: " + dotProblems.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" },
        )
        report.appendLine("old reader wrong:")
        oldWrong.forEach { report.appendLine("  $it") }
        println(report)
        File("build/reports/reader-comparison.txt").writeText(report.toString())
    }

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
}
