package com.tfcleipzig.tracker.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.tfcleipzig.tracker.detection.Frame
import kotlin.math.abs

/**
 * Copies a CameraX `YUV_420_888` image into a [Frame], reusing its buffers between frames.
 * The planes keep the camera's strides, so this is three bulk copies and no per-pixel work.
 */
class FrameCopier {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    fun copy(image: ImageProxy): Frame {
        val (yp, up, vp) = image.planes
        y = fill(y, yp)
        u = fill(u, up)
        v = fill(v, vp)
        return Frame(image.width, image.height, y, yp.rowStride, u, v, up.rowStride, up.pixelStride)
    }

    private fun fill(
        into: ByteArray,
        plane: ImageProxy.PlaneProxy,
    ): ByteArray {
        val buffer = plane.buffer
        buffer.rewind()
        val out = if (into.size == buffer.remaining()) into else ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}

/**
 * Milliseconds from when the sensor captured [image] until now. Camera timestamps run on either
 * the realtime or the monotonic clock depending on the device; the one the timestamp is closest
 * to (and not ahead of) is the one it uses.
 */
fun captureAgeMs(image: ImageProxy): Float {
    val ts = image.imageInfo.timestamp
    val realtime = SystemClock.elapsedRealtimeNanos() - ts
    val monotonic = System.nanoTime() - ts
    val age = listOf(realtime, monotonic).filter { it >= 0 }.minByOrNull { abs(it) } ?: return Float.NaN
    return age / 1e6f
}
