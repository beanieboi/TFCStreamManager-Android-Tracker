package com.tfcleipzig.tracker.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import com.tfcleipzig.tracker.detection.CounterReader
import com.tfcleipzig.tracker.detection.DotReader
import com.tfcleipzig.tracker.detection.Frame
import com.tfcleipzig.tracker.detection.TableEndReader

/**
 * Debug path: runs [TableEndReader] on a still photo instead of a camera frame. The photo is
 * scaled to the analysis size first, so a photo taken from the tracker's mount reads like a frame.
 */
class PhotoCheck(
    private val reader: CounterReader = DotReader(),
) {
    data class Result(
        /** The photo at analysis size with the counter line and bars drawn on it. */
        val image: Bitmap,
        val reading: TableEndReader.Reading,
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
                // Same squeeze as the analysis stream and the unit tests, whatever the aspect.
                decoder.setTargetSize(WIDTH, HEIGHT)
            }
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val reading = reader.read(Frame.fromArgb(pixels, WIDTH, HEIGHT))
        draw(bitmap, reading)
        return Result(bitmap, reading, sourceWidth, sourceHeight)
    }

    private fun draw(
        bitmap: Bitmap,
        reading: TableEndReader.Reading,
    ) {
        val line = reading.line ?: return
        val canvas = Canvas(bitmap)
        val paint =
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
        val h = line.stoneSize.toFloat()
        for ((bar, ok) in listOf(reading.setBar to (reading.sets != null), reading.goalBar to (reading.goals != null))) {
            bar ?: continue
            paint.color = if (ok) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
            canvas.drawLine(bar.left.toFloat(), line.centerAt(bar.left) - h, bar.right.toFloat(), line.centerAt(bar.right) - h, paint)
            canvas.drawLine(bar.left.toFloat(), line.centerAt(bar.left) + h, bar.right.toFloat(), line.centerAt(bar.right) + h, paint)
        }
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}
