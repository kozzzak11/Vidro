package app.vidro.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One gesture stream: still 300ms on an outer edge holds, movement becomes a drag,
 * two fingers pinch, a quick second tap seeks, a center or top-origin downward swipe is PiP.
 */
fun Modifier.vidroPlayerGestures(locked: Boolean, handler: PlayerGestureHandler): Modifier = pointerInput(locked, handler) {
    if (locked) {
        detectTapGestures(onTap = { handler.onTap() })
        return@pointerInput
    }
    val scope = CoroutineScope(coroutineContext)
    var lastTap = 0L
    var lastX = 0f
    var pendingTap: Job? = null

    fun tapOrDouble(now: Long, x: Float, width: Float) {
        val quick = now - lastTap in 1..279 && abs(x - lastX) < width * 0.25f
        if (quick) {
            pendingTap?.cancel()
            when {
                x < width * 0.36f -> handler.onDouble(-1)
                x > width * 0.64f -> handler.onDouble(1)
                else -> handler.onDouble(0)
            }
            lastTap = 0L
        } else {
            lastTap = now
            lastX = x
            pendingTap?.cancel()
            pendingTap = scope.launch {
                delay(280)
                handler.onTap()
            }
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val width = size.width.toFloat().coerceAtLeast(1f)
        val height = size.height.toFloat().coerceAtLeast(1f)
        val onEdge = start.x <= width * 0.18f || start.x >= width * 0.82f
        var totalX = 0f
        var totalY = 0f
        var mode = 0
        var holding = false
        var dragArmed = false

        fun absorb(event: PointerEvent) {
            val change = event.changes.firstOrNull { it.id == down.id } ?: return
            totalX += change.position.x - change.previousPosition.x
            totalY += change.position.y - change.previousPosition.y
        }

        val early = withTimeoutOrNull(300) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) return@withTimeoutOrNull event
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                if (change == null || !change.pressed) return@withTimeoutOrNull event
                absorb(event)
                if (abs(totalX) > 28f || abs(totalY) > 28f) return@withTimeoutOrNull event
            }
            @Suppress("UNREACHABLE_CODE")
            awaitPointerEvent()
        }

        fun startDrag(nextMode: Int) {
            if (dragArmed) return
            pendingTap?.cancel()
            dragArmed = true
            mode = nextMode
            handler.onDragStart()
        }

        fun classify(event: PointerEvent): Boolean {
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                if (holding) {
                    holding = false
                    handler.onHoldEnd()
                }
                pendingTap?.cancel()
                val zoom = event.calculateZoom()
                if (zoom != 1f) handler.onPinch(zoom)
                event.changes.forEach { it.consume() }
                mode = 4
                return pressed.isEmpty()
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
            if (change == null || !change.pressed) {
                if (holding) handler.onHoldEnd()
                else if (mode == 4 || mode == 6) Unit
                else if (mode == 0) tapOrDouble(change?.uptimeMillis ?: down.uptimeMillis, start.x, width)
                else if (dragArmed) handler.onDragEnd()
                return true
            }
            if (mode == 0 && !holding) {
                if (abs(totalX) <= 28f && abs(totalY) <= 28f) {
                    change.consume()
                    return false
                }
                val center = start.x in width * 0.30f..width * 0.70f
                val fromTop = start.y < height * 0.18f
                val next = when {
                    abs(totalX) > abs(totalY) -> 1
                    (center || fromTop) && totalY > 0f -> 5
                    start.x < width / 2f -> 2
                    else -> 3
                }
                if (next in 1..3) startDrag(next) else mode = next
            }
            if (holding || mode == 4) {
                change.consume()
                return false
            }
            if (mode == 5) {
                change.consume()
                if (totalY > 160f && totalY > abs(totalX) * 1.6f) {
                    handler.onPip()
                    mode = 6
                } else if (abs(totalX) > abs(totalY) + 24f) {
                    startDrag(1)
                } else if (abs(totalY) > 48f && (start.x <= width * 0.30f || start.x >= width * 0.70f)) {
                    startDrag(if (start.x < width / 2f) 2 else 3)
                }
                return false
            }
            if (mode in 1..3) {
                change.consume()
                handler.onDrag(mode, totalX, totalY, width, height)
            }
            return false
        }

        if (early == null) {
            if (onEdge) {
                pendingTap?.cancel()
                holding = true
                handler.onHoldStart(start.x >= width - width * 0.18f)
            }
        } else if (classify(early)) {
            return@awaitEachGesture
        }

        while (true) {
            val event = awaitPointerEvent()
            if (!holding && mode != 4) absorb(event)
            if (classify(event)) break
        }
    }
}

interface PlayerGestureHandler {
    fun onTap()
    fun onDouble(zone: Int)
    fun onPinch(zoom: Float)
    fun onHoldStart(forward: Boolean)
    fun onHoldEnd()
    fun onDragStart()
    fun onDrag(mode: Int, totalX: Float, totalY: Float, width: Float, height: Float)
    fun onDragEnd()
    fun onPip()
}
