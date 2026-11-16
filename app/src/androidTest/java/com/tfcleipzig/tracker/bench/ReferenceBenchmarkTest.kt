package com.tfcleipzig.tracker.bench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.TableEndReader
import com.tfcleipzig.tracker.reference.ReferencePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TableEndReader] on the phone's CPU, on the reference photos (packed into the test APK from
 * `testdata/`). Decoding and scaling the JPEGs is not timed; only the read is, as it runs per
 * camera frame. Also reports how many counters read right, since the photos are scaled by
 * Android's decoder here rather than AWT.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceBenchmarkTest {
    @Test
    fun readTimeOnReferencePhotos() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val photos =
            listOf("Side A", "Side B")
                .flatMap { dir -> assets.list(dir).orEmpty().map { "$dir/$it" } }
                .mapNotNull { ReferencePhoto.parse(it) }
                .sortedBy { it.path }
        assertTrue("no reference photos in the test APK", photos.isNotEmpty())

        val reader = TableEndReader()
        val all = mutableListOf<Float>()
        var correct = 0
        var wrong = 0
        val lines = StringBuilder()
        for (photo in photos) {
            val frame = assets.open(photo.path).use { decode(it) }
            repeat(WARMUP) { reader.read(frame) }
            val times =
                List(RUNS) {
                    val t = System.nanoTime()
                    reader.read(frame)
                    (System.nanoTime() - t) / 1e6f
                }
            all += times
            val r = reader.read(frame)
            val e = photo.expected
            for ((want, got) in listOf(e.score to r.goals, e.sets to r.sets, e.timeouts to r.timeouts)) {
                if (got == want) {
                    correct++
                } else if (got != null) {
                    wrong++
                }
            }
            lines.appendLine(
                "%-42s read ms %s   goals %s sets %s timeouts %s".format(
                    photo.path,
                    BenchReport.spread(times),
                    r.goals,
                    r.sets,
                    r.timeouts,
                ),
            )
        }
        lines.appendLine()
        lines.appendLine("all photos, read ms: ${BenchReport.spread(all)}")
        lines.appendLine("counters correct $correct/${3 * photos.size}, wrong $wrong")
        BenchReport.write("reference-read", lines.toString())
        assertEquals("wrong readings on device", 0, wrong)
    }

    /** 640x480 like the analysis stream: decode at a quarter, then filter-scale the rest. */
    private fun decode(input: java.io.InputStream): Frame {
        val quarter = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })!!
        val scaled = Bitmap.createScaledBitmap(quarter, W, H, true)
        val pixels = IntArray(W * H)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        return Frame.fromArgb(pixels, W, H)
    }

    private companion object {
        const val W = 640
        const val H = 480
        const val WARMUP = 10
        const val RUNS = 30
    }
}
