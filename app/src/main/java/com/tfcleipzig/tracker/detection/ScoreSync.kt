package com.tfcleipzig.tracker.detection

/**
 * Decides when the counters' reading goes to the server: once the same goals and sets have been
 * read in [steadyFrames] readable frames in a row, and they differ from what the server last
 * accepted. Unreadable frames (hands on the bars) neither count nor break the run, so moving a
 * stone shows up as soon as the hand is gone. Pure logic; the caller sends and reports back.
 */
class ScoreSync(
    private val steadyFrames: Int = 12,
) {
    data class Score(
        val goals: Int,
        val sets: Int,
    )

    private var candidate: Score? = null
    private var count = 0

    /** What the server last accepted from this phone. */
    var sent: Score? = null
        private set

    /** Being sent right now; not offered again until [accepted] or [failed]. */
    private var pending: Score? = null

    /** Feed every reading; returns the score to send now, or null. */
    fun update(reading: TableEndReader.Reading): Score? {
        val goals = reading.goals ?: return null
        val sets = reading.sets ?: return null
        val score = Score(goals, sets)
        if (score != candidate) {
            candidate = score
            count = 0
        }
        count++
        if (count < steadyFrames || score == sent || pending != null) return null
        pending = score
        return score
    }

    fun accepted(score: Score) {
        sent = score
        if (pending == score) pending = null
    }

    /** Sending [score] failed; it is offered again on the next steady frame. */
    fun failed(score: Score) {
        if (pending == score) pending = null
    }
}
