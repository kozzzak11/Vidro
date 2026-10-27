package app.vidro.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Speed-hold and double-tap math for the site fullscreen player.
 * The finger math stays out of the view so it can be tested without a device.
 */
object SiteGesture {
    const val MIN_HOLD_RATE = 2
    const val MAX_HOLD_RATE = 4
    const val SEEK_STEP_SEC = 10
    const val MULTI_TAP_WINDOW_MS = 700L

    /** One extra step of speed per [stepPx] of travel to the right. Floor is 2× while held. */
    fun holdRate(deltaPx: Float, stepPx: Float): Int {
        if (stepPx <= 0f) return MIN_HOLD_RATE
        val steps = (deltaPx / stepPx).toInt()
        return (MIN_HOLD_RATE + steps).coerceIn(MIN_HOLD_RATE, MAX_HOLD_RATE)
    }

    /**
     * Rate to request after the video element has refused something faster.
     * [acceptedMax] below 2 means even 2× did not stick, so the label follows the element.
     */
    fun clampHoldRate(finger: Int, acceptedMax: Int): Int {
        val upper = acceptedMax.coerceIn(1, MAX_HOLD_RATE)
        val lower = min(MIN_HOLD_RATE, upper)
        return finger.coerceIn(lower, max(lower, upper))
    }

    /**
     * Running total to show for this tap. Each tap still seeks [SEEK_STEP_SEC];
     * the total grows only while the same side keeps being tapped inside the window.
     */
    fun burstSeconds(
        previousTotal: Int,
        previousForward: Boolean,
        previousAt: Long,
        forward: Boolean,
        now: Long,
        windowMs: Long = MULTI_TAP_WINDOW_MS,
    ): Int {
        val continuing = previousTotal > 0 &&
            previousForward == forward &&
            now - previousAt in 0..windowMs
        return if (continuing) previousTotal + SEEK_STEP_SEC else SEEK_STEP_SEC
    }

    /** WebView returns a JSON string such as `"2"` or a bare number. */
    fun parseAppliedRate(raw: String?): Int? {
        val text = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        val value = text.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.roundToInt().takeIf { it in 1..16 }
    }
}
