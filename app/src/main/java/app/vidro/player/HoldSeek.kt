package app.vidro.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Right-edge hold plays at 2×, then steps forward. Left-edge hold seeks backward.
 * Releasing restores the previous speed only when fast-forward was engaged.
 */
class HoldSeekController(
    private val scope: CoroutineScope,
    private val onFast: (Boolean) -> Unit,
    private val onSeekBy: (Long) -> Unit,
    private val onLabel: (String?) -> Unit,
) {
    private var job: Job? = null
    private var fast = false

    fun start(forward: Boolean) {
        stop()
        job = scope.launch {
            if (forward) forwardLoop() else rewindLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (fast) {
            fast = false
            onFast(false)
        }
        onLabel(null)
    }

    private suspend fun CoroutineScope.forwardLoop() {
        fast = true
        onFast(true)
        onLabel("» 2×")
        delay(1200)
        var step = 10_000L
        var interval = 800L
        var accumulated = 0L
        while (isActive) {
            accumulated += step
            onSeekBy(step)
            onLabel("» 2×  +${accumulated / 1000} с")
            delay(interval)
            if (step < 20_000L) step = 20_000L
            if (interval > 320L) interval = (interval * 0.85).toLong()
        }
    }

    private suspend fun CoroutineScope.rewindLoop() {
        var step = 2_000L
        var interval = 400L
        var accumulated = 0L
        while (isActive) {
            accumulated += step
            onSeekBy(-step)
            onLabel("«  −${accumulated / 1000} с")
            delay(interval)
            if (step < 5_000L) step = 5_000L
            else if (step < 10_000L) step = 10_000L
            if (interval > 200L) interval = (interval * 0.88).toLong()
        }
    }
}
