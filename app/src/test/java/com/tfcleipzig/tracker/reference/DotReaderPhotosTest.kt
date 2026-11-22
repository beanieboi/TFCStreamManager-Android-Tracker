package com.tfcleipzig.tracker.reference

import com.tfcleipzig.tracker.detection.DotReader
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [DotReader] on the reference photos that aren't deprecated. These were taken before the dots
 * were renewed (2026-11-22), so worn dots may leave a photo unread; a wrong count is never
 * acceptable.
 */
class DotReaderPhotosTest {
    @Test
    fun neverReadsAPhotoWrong() {
        val photos = TestFrames.referencePhotos().filter { !it.first.deprecated }
        assumeTrue("no photos", photos.isNotEmpty())
        val reader = DotReader()
        val wrong = mutableListOf<String>()
        var correct = 0
        for ((photo, file) in photos) {
            val r = reader.read(TestFrames.frame(TestFrames.scaled(file)))
            val e = photo.expected
            val got = listOf(r.goals, r.sets, r.timeouts)
            val want = listOf(e.score, e.sets, e.timeouts)
            if (got == want) correct++
            if (want.indices.any { got[it] != null && got[it] != want[it] }) wrong += "${photo.path}: $got ${r.problems}"
            println("%-40s %-18s %s".format(photo.path, got, r.problems))
        }
        println("$correct of ${photos.size} correct")
        assertEquals(emptyList<String>(), wrong)
    }
}
