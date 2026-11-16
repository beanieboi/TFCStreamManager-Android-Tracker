package com.tfcleipzig.tracker.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndVoteTest {
    @Test
    fun settlesOnceEnoughFramesAgree() {
        val vote = EndVote(window = 10, agree = 8)
        repeat(9) { assertNull(vote.add(TableEnd.A)) }
        assertEquals(TableEnd.A, vote.add(TableEnd.A))
    }

    @Test
    fun framesWithoutAGuessDontCount() {
        val vote = EndVote(window = 10, agree = 8)
        repeat(9) { vote.add(TableEnd.B) }
        repeat(20) { assertNull(vote.add(null)) }
        assertEquals(TableEnd.B, vote.add(TableEnd.B))
    }

    @Test
    fun mixedGuessesKeepTheSettledEnd() {
        val vote = EndVote(window = 10, agree = 8)
        repeat(10) { vote.add(TableEnd.A) }
        repeat(10) { assertEquals(TableEnd.A, vote.add(if (it % 2 == 0) TableEnd.B else TableEnd.A)) }
    }

    @Test
    fun switchesWhenThePhoneIsMovedToTheOtherEnd() {
        val vote = EndVote(window = 10, agree = 8)
        repeat(10) { vote.add(TableEnd.A) }
        repeat(7) { assertEquals(TableEnd.A, vote.add(TableEnd.B)) }
        assertEquals(TableEnd.B, vote.add(TableEnd.B))
    }
}
