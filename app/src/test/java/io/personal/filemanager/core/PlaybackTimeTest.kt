package io.personal.filemanager.core

import io.personal.filemanager.ui.viewer.SEEK_STEP_MS
import io.personal.filemanager.ui.viewer.formatPlaybackTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeTest {
    @Test
    fun formats() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:09", formatPlaybackTime(9_999))
        assertEquals("12:34", formatPlaybackTime((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatPlaybackTime((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("0:00", formatPlaybackTime(-5))
    }

    @Test
    fun seekStepIsExactlyTenSeconds() {
        assertEquals(10_000L, SEEK_STEP_MS)
    }
}
