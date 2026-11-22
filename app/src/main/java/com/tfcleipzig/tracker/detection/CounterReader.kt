package com.tfcleipzig.tracker.detection

/** Reads goals, sets and timeouts at one table end from an upright frame. */
fun interface CounterReader {
    fun read(frame: Frame): TableEndReader.Reading
}
