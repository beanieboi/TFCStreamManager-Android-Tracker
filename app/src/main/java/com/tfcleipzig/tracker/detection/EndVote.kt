package com.tfcleipzig.tracker.detection

/**
 * Settles which end of the table the phone watches from the per-frame guesses
 * ([TableEndReader.Reading.end]). An end is taken once it has [agree] of the last [window]
 * guesses (about 2 s of frames at the start); while the guesses are mixed, the last settled end
 * stays. Frames without a guess don't count.
 */
class EndVote(
    private val window: Int = 30,
    private val agree: Int = 25,
) {
    private val votes = ArrayDeque<TableEnd>()

    /** The settled end, null until enough frames agree. */
    var end: TableEnd? = null
        private set

    /** Adds one frame's guess; returns the settled end. */
    fun add(guess: TableEnd?): TableEnd? {
        if (guess != null) {
            votes.addLast(guess)
            if (votes.size > window) votes.removeFirst()
        }
        if (votes.size == window) {
            TableEnd.entries.firstOrNull { e -> votes.count { it == e } >= agree }?.let { end = it }
        }
        return end
    }
}
