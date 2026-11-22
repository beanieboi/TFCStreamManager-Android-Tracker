package com.tfcleipzig.tracker.power

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryGateTest {
    /** Levels as a flat battery charges and drains again, and whether tracking may run at each. */
    @Test
    fun opensAt15AndClosesBelow10() {
        var open = false
        val levels = listOf(3, 9, 12, 14, 15, 14, 11, 10, 9, 12, 15)
        val got =
            levels.map { level ->
                open = BatteryGate.decide(open, level, openAt = 15, closeAt = 10)
                open
            }
        assertEquals(listOf(false, false, false, false, true, true, true, true, false, false, true), got)
    }

    @Test
    fun aFullBatteryIsOpenFromTheStart() {
        assertEquals(true, BatteryGate.decide(open = false, level = 100, openAt = 15, closeAt = 10))
    }
}
