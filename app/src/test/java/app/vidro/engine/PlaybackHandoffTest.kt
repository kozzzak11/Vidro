package app.vidro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHandoffTest {
    @Test
    fun blankFallsBackToStartPlaying() {
        listOf(null, "", "   ", "null", "\"null\"").forEach { raw ->
            val handoff = PlaybackHandoff.parse(raw)
            assertEquals(0L, handoff.positionMs)
            assertTrue(handoff.playing)
        }
    }

    @Test
    fun readsQuotedWebViewResult() {
        val handoff = PlaybackHandoff.parse("\"12500|1\"")
        assertEquals(12_500L, handoff.positionMs)
        assertTrue(handoff.playing)
    }

    @Test
    fun pausedFlagIsZero() {
        val handoff = PlaybackHandoff.parse("  \"8000|0\"  ")
        assertEquals(8_000L, handoff.positionMs)
        assertFalse(handoff.playing)
    }

    @Test
    fun garbageTimeIsZeroAndStillHonorsPlayFlag() {
        val handoff = PlaybackHandoff.parse("abc|0")
        assertEquals(0L, handoff.positionMs)
        assertFalse(handoff.playing)
    }

    @Test
    fun negativeTimeIsClamped() {
        val handoff = PlaybackHandoff.parse("-40|1")
        assertEquals(0L, handoff.positionMs)
        assertTrue(handoff.playing)
    }
}
