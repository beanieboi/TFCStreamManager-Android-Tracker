package com.tfcleipzig.tracker.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import com.tfcleipzig.tracker.detection.GoalCounterReader
import com.tfcleipzig.tracker.detection.lumaFromArgb

/**
 * Debug path: runs [GoalCounterReader] on a still photo instead of a camera frame. The photo is
 * scaled to the analysis size first, so a photo taken from the tracker's mount reads like a frame.
 */
class PhotoCheck(
    private val calibration: GoalCounterReader.Calibration,
) {
    data class Result(
        /** The photo at analysis size with the calibration box drawn on it. */
        val image: Bitmap,
        val reading: GoalCounterReader.Reading?,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    /** Blocking; call off the main thread. */
    fun check(source: ImageDecoder.Source): Result {
        var sourceWidth = 0
        var sourceHeight = 0
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                sourceWidth = info.size.width
                sourceHeight = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                // Same squeeze as the analysis stream and the unit test, whatever the aspect.
                decoder.setTargetSize(WIDTH, HEIGHT)
            }
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val reading = GoalCounterReader(calibration).read(lumaFromArgb(pixels), WIDTH, HEIGHT)

        val c = calibration
        val paint =
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = if (reading != null) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
            }
        Canvas(bitmap).drawRect(c.left * WIDTH - 1, c.top * HEIGHT - 1, c.right * WIDTH + 1, c.bottom * HEIGHT + 1, paint)
        return Result(bitmap, reading, sourceWidth, sourceHeight)
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}
