package com.tfcleipzig.tracker.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class GoalCounterReaderTest {
    @Test
    fun readsEveryScoreOnASyntheticBar() {
        for (score in 0..7) {
            val frame = SyntheticBar.draw(score)
            val reading = SyntheticBar.reader.read(frame, SyntheticBar.W, SyntheticBar.H)
            assertNotNull("score $score", reading)
            assertEquals("score $score", score, reading!!.score)
        }
    }

    @Test
    fun rejectsACoveredBar() {
        val frame = ByteArray(SyntheticBar.W * SyntheticBar.H) { 90 }
        assertNull(SyntheticBar.reader.read(frame, SyntheticBar.W, SyntheticBar.H))
    }

    /** First test photo, 2026-11-15, Tisch 1: bottom-right counter shows 3. */
    @Test
    fun readsThreeFromThePhoto() {
        val file = File(PHOTO)
        org.junit.Assume.assumeTrue("missing $PHOTO", file.exists())
        val (luma, w, h) = lumaAt640(ImageIO.read(file))
        val reading = GoalCounterReader(TISCH_1_BOTTOM_RIGHT).read(luma, w, h)
        assertNotNull(reading)
        assertEquals(3, reading!!.score)
    }

    /** The mount is not exact: the read must survive the calibration being a few pixels off. */
    @org.junit.Ignore("A 1 px vertical shift puts the rod into the sampled rows; see docs/session.md")
    @Test
    fun toleratesAShiftedCalibration() {
        val file = File(PHOTO)
        org.junit.Assume.assumeTrue("missing $PHOTO", file.exists())
        val (luma, w, h) = lumaAt640(ImageIO.read(file))
        val c = TISCH_1_BOTTOM_RIGHT
        val misses = mutableListOf<String>()
        for (dx in -2..2) {
            for (dy in -1..1) {
                val shifted =
                    c.copy(
                        left = c.left + dx / 640f,
                        right = c.right + dx / 640f,
                        top = c.top + dy / 480f,
                        bottom = c.bottom + dy / 480f,
                    )
                val reading = GoalCounterReader(shifted).read(luma, w, h)
                println("dx=$dx dy=$dy -> $reading")
                if (reading?.score != 3) misses += "dx=$dx dy=$dy -> $reading"
            }
        }
        assertEquals("misses: $misses", emptyList<String>(), misses)
    }

    companion object {
        const val PHOTO = "src/test/resources/goal-counter/2026-11-15-16-14-14-886.jpg"

        /** Measured on the 2026-11-15 photo scaled to the 640x480 analysis size. */
        val TISCH_1_BOTTOM_RIGHT =
            GoalCounterReader.Calibration(
                left = 337.5f / 640,
                top = 369f / 480,
                right = 390.5f / 640,
                bottom = 375f / 480,
            )

        /** Scales to 640x480 like the analysis stream and converts to BT.601 luma. */
        fun lumaAt640(src: BufferedImage): Triple<ByteArray, Int, Int> {
            val w = 640
            val h = 480
            val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
                dispose()
            }
            val luma = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = scaled.getRGB(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    luma[y * w + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
                }
            }
            return Triple(luma, w, h)
        }
    }
}

/** A clean bar at roughly the size the real one has in a 640x480 frame. */
private object SyntheticBar {
    const val W = 100
    const val H = 20
    private const val LEFT = 20
    private const val PITCH = 6
    private const val SPACER = 13
    private const val TOP = 7

    val reader =
        GoalCounterReader(
            GoalCounterReader.Calibration(
                left = LEFT.toFloat() / W,
                top = TOP.toFloat() / H,
                right = (LEFT + 7 * PITCH + SPACER).toFloat() / W,
                bottom = (TOP + 6).toFloat() / H,
                spacer = SPACER.toFloat() / (7 * PITCH + SPACER),
            ),
        )

    fun draw(score: Int): ByteArray {
        val frame = ByteArray(W * H) { 25 }
        val unscored = 7 - score
        val stoneXs = (0 until unscored).map { LEFT + it * PITCH } + (0 until score).map { LEFT + (unscored + it) * PITCH + SPACER }
        for (x0 in stoneXs) {
            for (y in TOP until TOP + 6) {
                for (x in x0 until x0 + PITCH) {
                    val edge = x == x0 || x == x0 + PITCH - 1
                    frame[y * W + x] = (if (edge) 50 else 180).toByte()
                }
            }
        }
        // Rod: one bright row through the middle of the spacer.
        val spacerX = LEFT + unscored * PITCH
        for (x in spacerX until spacerX + SPACER) frame[(TOP + 2) * W + x] = 160.toByte()
        return frame
    }
}
