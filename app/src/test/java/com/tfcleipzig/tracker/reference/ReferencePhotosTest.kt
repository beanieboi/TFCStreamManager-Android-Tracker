package com.tfcleipzig.tracker.reference

import com.tfcleipzig.tracker.detection.TableEndReader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs [TableEndReader] on every labeled photo in `testdata/` (scaled to the 640x480 analysis
 * size) and compares goals, sets and timeouts with the file names.
 *
 * A wrong count is worse than none (it would send a wrong score), so the two are tracked apart.
 * The assertions are a ratchet: raise [MIN_CORRECT] and lower [MAX_WRONG] as detection improves,
 * never the other way round. The full table is printed and written to
 * `build/reports/reference-photos.txt`.
 */
class ReferencePhotosTest {
    private enum class Counter { GOALS, SETS, TIMEOUTS }

    @Test
    fun readsTheReferencePhotos() {
        val photos = TestFrames.referencePhotos()
        assumeTrue("no photos in ${TestFrames.TESTDATA.absolutePath}", photos.isNotEmpty())

        val reader = TableEndReader()
        val correct = mutableMapOf<Counter, Int>().withDefault { 0 }
        val wrong = mutableMapOf<Counter, Int>().withDefault { 0 }
        val timesUs = mutableListOf<Long>()
        val report = StringBuilder()
        report.appendLine("%-42s %-9s %-9s %-9s %6s  %s".format("photo", "goals", "sets", "timeouts", "us", "bars"))

        for ((photo, file) in photos) {
            val frame = TestFrames.frame(TestFrames.scaled(file))
            reader.read(frame) // warm up the JIT on this frame before timing it
            val start = System.nanoTime()
            val reading = reader.read(frame)
            val us = (System.nanoTime() - start) / 1_000
            timesUs += us

            val e = photo.expected
            val cells =
                listOf(
                    Triple(Counter.GOALS, e.score, reading.goals),
                    Triple(Counter.SETS, e.sets, reading.sets),
                    Triple(Counter.TIMEOUTS, e.timeouts, reading.timeouts),
                ).map { (counter, want, got) ->
                    when (got) {
                        want -> correct[counter] = correct.getValue(counter) + 1
                        null -> Unit
                        else -> wrong[counter] = wrong.getValue(counter) + 1
                    }
                    when (got) {
                        want -> "$got ok"
                        null -> "$want ?"
                        else -> "$want->$got !"
                    }
                }
            report.appendLine(
                "%-42s %-9s %-9s %-9s %6d  %s %s".format(photo.path, cells[0], cells[1], cells[2], us, reading.setBar, reading.goalBar),
            )
            if (VERBOSE) {
                reading.setBar?.let { report.appendLine("    set  ${it.describe()}") }
                reading.goalBar?.let { report.appendLine("    goal ${it.describe()}") }
            }
        }

        val n = photos.size
        report.appendLine()
        for (c in Counter.entries) {
            report.appendLine("%-9s correct %2d/%d  wrong %2d".format(c.name.lowercase(), correct.getValue(c), n, wrong.getValue(c)))
        }
        val sorted = timesUs.sorted()
        report.appendLine("read time (JVM): median %d us, max %d us".format(sorted[n / 2], sorted.last()))
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/reference-photos.txt").writeText(report.toString())

        for (c in Counter.entries) {
            assertTrue("$c correct ${correct.getValue(c)} < ${MIN_CORRECT.getValue(c)}", correct.getValue(c) >= MIN_CORRECT.getValue(c))
            assertTrue("$c wrong ${wrong.getValue(c)} > ${MAX_WRONG.getValue(c)}", wrong.getValue(c) <= MAX_WRONG.getValue(c))
        }
    }

    private companion object {
        /** `REFERENCE_VERBOSE=1 ./gradlew ... --rerun` adds every bar's runs to the report. */
        val VERBOSE = System.getenv("REFERENCE_VERBOSE") != null

        val MIN_CORRECT = mapOf(Counter.GOALS to 26, Counter.SETS to 26, Counter.TIMEOUTS to 26)
        val MAX_WRONG = mapOf(Counter.GOALS to 0, Counter.SETS to 0, Counter.TIMEOUTS to 0)
    }
}
