package com.tfcleipzig.tracker.calibration

import com.tfcleipzig.tracker.detection.TableEndReader

/**
 * Says when the counters are lost: [frames] frames in a row without a stone line, i.e. the reader
 * found nothing of the counters at all. Then the phone has moved or the scene changed, and it's
 * time to calibrate again. A hand over one bar still leaves a line, so moving stones doesn't count.
 *
 * Fires once per loss; [reset] when a calibration starts, so it can fire again after one fails.
 */
class LostWatch(
    private val frames: Int = 3,
) {
    private var missing = 0

    /** Feed every reading outside calibration; true on the frame the counters count as lost. */
    fun update(reading: TableEndReader.Reading): Boolean {
        if (reading.line != null) {
            missing = 0
            return false
        }
        missing++
        return missing == frames
    }

    fun reset() {
        missing = 0
    }
}
