package app.vidro.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.vidro.engine.Scripts

class SiteGestureTest {
    @Test
    fun holdStartsAtDoubleAndCapsAtFour() {
        val step = 64f
        assertEquals(2, SiteGesture.holdRate(0f, step))
        assertEquals(2, SiteGesture.holdRate(-40f, step))
        assertEquals(2, SiteGesture.holdRate(63f, step))
        assertEquals(3, SiteGesture.holdRate(64f, step))
        assertEquals(3, SiteGesture.holdRate(127f, step))
        assertEquals(4, SiteGesture.holdRate(128f, step))
        assertEquals(4, SiteGesture.holdRate(800f, step))
    }

    @Test
    fun rejectedRateLowersTheCapWithoutGoingNegative() {
        assertEquals(4, SiteGesture.clampHoldRate(4, 4))
        assertEquals(3, SiteGesture.clampHoldRate(4, 3))
        assertEquals(2, SiteGesture.clampHoldRate(4, 2))
        assertEquals(2, SiteGesture.clampHoldRate(2, 4))
        assertEquals(1, SiteGesture.clampHoldRate(4, 1))
    }

    @Test
    fun doubleTapBurstAccumulatesOnTheSameSide() {
        val first = SiteGesture.burstSeconds(0, true, 0L, true, 1_000L)
        assertEquals(10, first)
        val second = SiteGesture.burstSeconds(first, true, 1_000L, true, 1_400L)
        assertEquals(20, second)
        val third = SiteGesture.burstSeconds(second, true, 1_400L, true, 2_000L)
        assertEquals(30, third)
        val expired = SiteGesture.burstSeconds(third, true, 2_000L, true, 2_800L)
        assertEquals(10, expired)
    }

    @Test
    fun switchingSidesStartsAFreshBurst() {
        val again = SiteGesture.burstSeconds(30, true, 1_000L, false, 1_200L)
        assertEquals(10, again)
    }

    @Test
    fun appliedRateParsesWebViewQuoting() {
        assertEquals(2, SiteGesture.parseAppliedRate("\"2\""))
        assertEquals(4, SiteGesture.parseAppliedRate("4"))
        assertEquals(3, SiteGesture.parseAppliedRate("\"3.2\""))
        assertNull(SiteGesture.parseAppliedRate(null))
        assertNull(SiteGesture.parseAppliedRate("null"))
    }

    @Test
    fun speedScriptTriesSlowerRatesAndIgnoresAStaleHold() {
        val js = Scripts.holdRate(4.0, 7)
        assertTrue(js.contains("__vidroGen"))
        assertTrue(js.contains("try"))
        assertTrue(js.contains("4.0"))
        val release = Scripts.holdRelease(8)
        assertTrue(release.contains("__vidroGen"))
        assertTrue(release.contains("__vidroRate=null"))
    }
}
