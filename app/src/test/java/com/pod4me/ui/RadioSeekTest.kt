package com.pod4me.ui

import org.junit.Assert.*
import org.junit.Test

class RadioSeekTest {
    @Test fun centerNeverSeeksAndBothSidesRespectAvailableAudio() {
        for (position in listOf(0L, 120_000L, 600_000L)) {
            val seek = RadioSeek(position, 600_000)
            assertEquals(0L, seek.offset)
            assertEquals(-position, seek.copy(fraction = -1f).offset)
            assertEquals(600_000 - position, seek.copy(fraction = 1f).offset)
        }
        assertEquals(-60_000L, RadioSeek(120_000, 600_000, -.5f).offset)
        assertEquals(240_000L, RadioSeek(120_000, 600_000, .5f).offset)
    }
    @Test fun boundsHandleStartupAndEndedBuffers() {
        assertEquals(0L, RadioSeek(0, 0, -1f).offset)
        assertEquals(0L, RadioSeek(0, -1, 1f).offset)
        assertEquals(0L, RadioSeek(700_000, 600_000, 1f).offset)
        assertEquals(-600_000L, RadioSeek(700_000, 600_000, -1f).offset)
    }
}
