package com.tfcleipzig.tracker.reference

import com.tfcleipzig.tracker.detection.Frame
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Loads photos for JVM tests the way the analysis stream sees the scene. */
object TestFrames {
    const val WIDTH = 640
    const val HEIGHT = 480

    /** `testdata/` relative to Gradle's working directory for unit tests (`app/`). */
    val TESTDATA = File("../testdata")

    fun referencePhotos(): List<Pair<ReferencePhoto, File>> =
        TESTDATA
            .walkTopDown()
            .filter { it.isFile && it.extension == "jpg" }
            .mapNotNull { f -> ReferencePhoto.parse(f.relativeTo(TESTDATA).invariantSeparatorsPath)?.let { it to f } }
            .sortedBy { it.first.path }
            .toList()

    /** Squeezed to 640x480 with area averaging, like the camera's scaler. */
    fun scaled(file: File): BufferedImage {
        val scaled = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(ImageIO.read(file).getScaledInstance(WIDTH, HEIGHT, Image.SCALE_AREA_AVERAGING), 0, 0, null)
            dispose()
        }
        return scaled
    }

    fun frame(image: BufferedImage): Frame = Frame.fromArgb(image.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH), WIDTH, HEIGHT)
}
