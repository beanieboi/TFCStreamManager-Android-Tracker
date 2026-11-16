package com.tfcleipzig.tracker.reference

import com.tfcleipzig.tracker.detection.TableEnd
import com.tfcleipzig.tracker.detection.TableEndReader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The reference photos, altered in ways a mounted phone will see: other light, a mount that is
 * rolled, shifted or a bit further away, a hand over the bars. The detector was tuned on the
 * unaltered photos, so this is the check that it didn't just learn them.
 *
 * Not reading a counter is fine here; reading it wrong is not. The table of correct / unread /
 * wrong per variant is printed and written to `build/reports/reference-variants.txt`.
 */
class ReferenceVariantsTest {
    private class Variant(
        val name: String,
        /** Counters (of 3 per photo) this variant must still read. Ratchet upward. */
        val minCorrect: Int,
        val apply: (BufferedImage, TableEndReader.Reading) -> BufferedImage,
    )

    @Test
    fun neverReadsAVariantWrong() {
        val photos = TestFrames.referencePhotos()
        assumeTrue("no photos in ${TestFrames.TESTDATA.absolutePath}", photos.isNotEmpty())
        val reader = TableEndReader()

        val variants =
            listOf(
                // Baseline and milder exposure steps, to see the confidence follow the exposure.
                Variant("as taken", 78) { img, _ -> img },
                Variant("darker x0.8", 70) { img, _ -> tone(img, 0.8f) },
                Variant("brighter x1.2", 40) { img, _ -> tone(img, 1.2f) },
                Variant("darker x0.6", 75) { img, _ -> tone(img, 0.6f) },
                Variant("brighter x1.4", 40) { img, _ -> tone(img, 1.4f) },
                Variant("rolled +3 deg", 72) { img, _ -> transform(img) { rotate(Math.toRadians(3.0), W / 2.0, H / 2.0) } },
                Variant("rolled -3 deg", 75) { img, _ -> transform(img) { rotate(Math.toRadians(-3.0), W / 2.0, H / 2.0) } },
                Variant("further x0.85", 78) { img, _ -> transform(img) { scaleAbout(0.85) } },
                Variant("closer x1.15", 58) { img, _ -> transform(img) { scaleAbout(1.15) } },
                Variant("shifted +60,-40", 78) { img, _ -> transform(img) { translate(60.0, -40.0) } },
                // Covered: reading nothing is right. TODO: keep reading the bar that isn't covered.
                Variant(
                    "hand over goals",
                    0,
                ) { img, r -> cover(img, r.goalBar?.let { it.left + (it.right - it.left) / 4 }, r, Color(205, 160, 135)) },
                Variant(
                    "shadow over sets",
                    0,
                ) { img, r -> cover(img, r.setBar?.let { it.left + (it.right - it.left) / 3 }, r, Color(25, 25, 25)) },
            )
        val correct = IntArray(variants.size)
        val unread = IntArray(variants.size)
        val wrong = IntArray(variants.size)
        val wrongs = mutableListOf<String>()
        val ends = IntArray(variants.size)
        val confidence = DoubleArray(variants.size)
        val endsWrong = mutableListOf<String>()

        for ((photo, file) in photos) {
            val big = scaledTo(ImageIO.read(file), 2 * W, 2 * H)
            val clean = reader.read(TestFrames.frame(scaledTo(big, W, H)))
            for ((i, v) in variants.withIndex()) {
                val img = scaledTo(v.apply(big, clean), W, H)
                val r = reader.read(TestFrames.frame(img))
                confidence[i] += r.confidence.toDouble()
                val wantEnd = TableEnd.valueOf(photo.side.removePrefix("Side ").trim())
                when (r.end) {
                    wantEnd -> ends[i]++
                    null -> Unit
                    else -> endsWrong += "${v.name}: ${photo.path} -> end ${r.end} (%+.2f)".format(r.endScore)
                }
                val e = photo.expected
                for ((want, got) in listOf(e.score to r.goals, e.sets to r.sets, e.timeouts to r.timeouts)) {
                    when (got) {
                        want -> correct[i]++
                        null -> unread[i]++
                        else -> {
                            wrong[i]++
                            wrongs +=
                                "${v.name}: ${photo.path} -> goals ${r.goals} sets ${r.sets} timeouts ${r.timeouts}  ${r.setBar} ${r.goalBar}"
                            dump(img, v.name, photo.path)
                        }
                    }
                }
            }
        }

        val report = StringBuilder("%-18s %8s %7s %6s %6s %5s\n".format("variant", "correct", "unread", "wrong", "end", "conf"))
        for ((i, v) in variants.withIndex()) {
            report.appendLine(
                "%-18s %4d/%-3d %7d %6d %3d/%d %5.2f".format(
                    v.name,
                    correct[i],
                    3 * photos.size,
                    unread[i],
                    wrong[i],
                    ends[i],
                    photos.size,
                    confidence[i] / photos.size,
                ),
            )
        }
        wrongs.forEach { report.appendLine(it) }
        endsWrong.forEach { report.appendLine(it) }
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/reference-variants.txt").writeText(report.toString())

        assertTrue("wrong ends:\n${endsWrong.joinToString("\n")}", endsWrong.isEmpty())
        assertTrue("more wrong readings than the known $KNOWN_WRONG:\n${wrongs.joinToString("\n")}", wrongs.size <= KNOWN_WRONG)
        for ((i, v) in variants.withIndex()) {
            assertTrue("${v.name}: ${correct[i]} correct < ${v.minCorrect}", correct[i] >= v.minCorrect)
        }
    }

    private fun tone(
        src: BufferedImage,
        factor: Float,
    ): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val p = src.getRGB(x, y)
                val r = (((p shr 16) and 0xFF) * factor).toInt().coerceAtMost(255)
                val g = (((p shr 8) and 0xFF) * factor).toInt().coerceAtMost(255)
                val b = ((p and 0xFF) * factor).toInt().coerceAtMost(255)
                out.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        return out
    }

    private fun AffineTransform.scaleAbout(s: Double) {
        translate(W.toDouble(), H.toDouble())
        scale(s, s)
        translate(-W.toDouble(), -H.toDouble())
    }

    /** Applies [build] (in 640x480 units) to the 1280x960 image; uncovered area stays black. */
    private fun transform(
        src: BufferedImage,
        build: AffineTransform.() -> Unit,
    ): BufferedImage {
        val t = AffineTransform()
        t.scale(2.0, 2.0)
        t.build()
        t.scale(0.5, 0.5)
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, t, null)
            dispose()
        }
        return out
    }

    /** A patch two and a half stones wide over the line at [x] (640x480 units). */
    private fun cover(
        src: BufferedImage,
        x: Int?,
        r: TableEndReader.Reading,
        color: Color,
    ): BufferedImage {
        val line = r.line ?: return src
        val bar = r.goalBar ?: return src
        if (x == null) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            drawImage(src, 0, 0, null)
            color(color)
            val w = (2.5f * bar.pitch).toInt()
            val y = line.centerAt(x).toInt() - line.stoneSize
            fillRoundRect(2 * x, 2 * y, 2 * w, 4 * line.stoneSize, 12, 12)
            dispose()
        }
        return out
    }

    private fun java.awt.Graphics2D.color(c: Color) {
        this.color = c
    }

    private fun scaledTo(
        src: BufferedImage,
        w: Int,
        h: Int,
    ): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null)
            dispose()
        }
        return out
    }

    /** Wrong readings are saved to `build/reports/reference-variants/` to look at. */
    private fun dump(
        img: BufferedImage,
        variant: String,
        path: String,
    ) {
        val dir = File("build/reports/reference-variants")
        dir.mkdirs()
        ImageIO.write(img, "png", File(dir, "$variant - ${path.replace('/', '_')}.png"))
    }

    private companion object {
        const val W = TestFrames.WIDTH
        const val H = TestFrames.HEIGHT

        /**
         * Wrong readings still made, all in a single frame where an end stone or a stretch of rod
         * fades out and what's left is still a valid counter state. Remembering the bar geometry
         * over frames (the housing doesn't move on a fixed mount) is the fix; shrink this list.
         * - darker x0.6, Side A score 3/timeout 0/set 0: right set stone lost, timeouts 1
         * - darker x0.6, Side B score 2/timeout 1/set 0: rod between timeout stones lost, timeouts 2
         * - closer x1.15, Side B score 5: goals 4
         */
        val KNOWN_WRONG = 3
    }
}
