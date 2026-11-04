package com.pod4me.ui

/** Freeze the available distances for one drag; normal playback leaves the slider centered. */
internal data class RadioSeek(val position: Long, val duration: Long, val fraction: Float = 0f) {
    val offset: Long get() {
        val end = duration.coerceAtLeast(0)
        val start = position.coerceIn(0, end)
        val distance = if (fraction < 0) start else end - start
        return (fraction.coerceIn(-1f, 1f) * distance).toLong()
    }
}
