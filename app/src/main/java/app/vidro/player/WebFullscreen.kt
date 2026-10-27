package app.vidro.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.vidro.engine.Scripts
import app.vidro.ui.theme.VidroPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * The site's fullscreen element, with fill, speed-hold, double-tap seek and exit on top.
 * The top and bottom bands are left clear so the site's own buttons get the touch directly.
 */
@Composable
fun WebFullscreen(
    custom: View,
    inPip: Boolean,
    trafficLabel: String?,
    onClose: () -> Unit,
    onPip: () -> Unit,
    eval: (String) -> Unit,
    query: (String, (String?) -> Unit) -> Unit = { js, _ -> eval(js) },
) {
    val session = remember { mutableIntStateOf(0) }
    val speed = remember { mutableIntStateOf(SiteGesture.MIN_HOLD_RATE) }
    val cap = remember { mutableIntStateOf(SiteGesture.MAX_HOLD_RATE) }
    val disposed = remember { booleanArrayOf(false) }
    val latestSession = rememberUpdatedState(session.intValue)
    var seekText by remember { mutableStateOf<String?>(null) }
    var seekOnRight by remember { mutableStateOf(true) }
    var seekToken by remember { mutableIntStateOf(0) }
    var modeLabel by remember { mutableStateOf(ScaleSession.label()) }
    var chromeTick by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose {
            disposed[0] = true
            eval(Scripts.holdRelease(latestSession.value + 1))
        }
    }
    LaunchedEffect(custom) {
        ScaleSession.mode = ScaleSession.Mode.FILL
        ScaleSession.pinch = 1f
        modeLabel = ScaleSession.label()
        applyWebScale(custom)
        repeat(3) {
            eval(Scripts.objectFit(ScaleSession.mode.name))
            delay(if (it == 0) 80 else 500)
        }
    }
    LaunchedEffect(session.intValue, speed.intValue) {
        val gen = session.intValue
        val sent = speed.intValue
        if (gen % 2 == 0) return@LaunchedEffect
        while (isActive) {
            query(Scripts.holdRate(sent.toDouble(), gen)) { raw ->
                if (disposed[0] || session.intValue != gen) return@query
                val applied = SiteGesture.parseAppliedRate(raw) ?: return@query
                if (applied < sent) {
                    val nextCap = applied.coerceIn(1, SiteGesture.MAX_HOLD_RATE)
                    if (nextCap < cap.intValue) cap.intValue = nextCap
                    val clamped = SiteGesture.clampHoldRate(sent, nextCap)
                    if (clamped != speed.intValue) speed.intValue = clamped
                }
            }
            delay(200)
        }
    }
    LaunchedEffect(seekToken) {
        if (seekText == null) return@LaunchedEffect
        delay(750)
        seekText = null
    }
    LaunchedEffect(chromeTick, custom) {
        chrome = true
        delay(3500)
        chrome = false
    }
    val reveal = { chromeTick += 1 }
    val holding = session.intValue % 2 == 1
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> WebStage(context) },
            update = { stage ->
                stage.mount(custom)
                stage.onSpeed = { finger ->
                    if (session.intValue % 2 == 0) {
                        session.intValue += 1
                        cap.intValue = SiteGesture.MAX_HOLD_RATE
                        seekText = null
                    }
                    speed.intValue = SiteGesture.clampHoldRate(finger, cap.intValue)
                }
                stage.onSpeedEnd = {
                    if (session.intValue % 2 == 1) {
                        session.intValue += 1
                        eval(Scripts.holdRelease(session.intValue))
                    }
                }
                stage.onSeek = { forward, total ->
                    eval(Scripts.holdSeek(if (forward) SiteGesture.SEEK_STEP_SEC.toDouble() else -SiteGesture.SEEK_STEP_SEC.toDouble()))
                    seekOnRight = forward
                    seekText = if (forward) "+$total" else "−$total"
                    seekToken += 1
                }
                stage.onEdgeTap = { reveal() }
                applyWebScale(custom)
            },
        )
        if (!inPip && holding) {
            Text(
                "${speed.intValue}×",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 44.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (!inPip && seekText != null) {
            Text(
                seekText.orEmpty(),
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(if (seekOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 36.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (!inPip && chrome) {
            if (!trafficLabel.isNullOrBlank()) {
                Text(
                    trafficLabel,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding()) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
            IconButton(
                onClick = onPip,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 48.dp),
            ) {
                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Картинка в картинке", tint = Color.White)
            }
            IconButton(
                onClick = {
                    ScaleSession.cycle()
                    ScaleSession.pinch = 1f
                    modeLabel = ScaleSession.label()
                    applyWebScale(custom)
                    eval(Scripts.objectFit(ScaleSession.mode.name))
                    reveal()
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding(),
            ) {
                Icon(Icons.Filled.AspectRatio, contentDescription = modeLabel, tint = VidroPalette.Lime)
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 8.dp, bottom = 72.dp),
            ) {
                Icon(
                    Icons.Filled.FullscreenExit,
                    contentDescription = "Выйти из полноэкранного режима",
                    tint = VidroPalette.Lime,
                )
            }
        }
    }
}

private class WebStage(context: Context) : FrameLayout(context) {
    private val gestures = PictureGestures(context)
    var onSpeed: ((Int) -> Unit)? = null
    var onSpeedEnd: (() -> Unit)? = null
    var onSeek: ((Boolean, Int) -> Unit)? = null
    var onEdgeTap: (() -> Unit)? = null

    init {
        clipChildren = true
        clipToPadding = true
        addView(gestures)
    }

    fun mount(custom: View) {
        if (custom.parent !== this) {
            (custom.parent as? ViewGroup)?.removeView(custom)
            addView(
                custom,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        gestures.target = custom
        gestures.onSpeed = { onSpeed?.invoke(it) }
        gestures.onSpeedEnd = { onSpeedEnd?.invoke() }
        gestures.onSeek = { forward, total -> onSeek?.invoke(forward, total) }
        gestures.onEdgeTap = { onEdgeTap?.invoke() }
        gestures.bringToFront()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (width <= 0 || height <= 0) return
        // Middle of the picture only. Top and bottom stay open for site controls.
        val bandTop = (height * 0.18f).toInt()
        val bandBottom = (height * 0.68f).toInt()
        gestures.layout(0, bandTop, width, bandBottom)
    }
}

/**
 * Middle-band gestures. A lone tap is forwarded after the double-tap window so the
 * site still receives it. A second tap seeks instead of clicking twice. A press on
 * the right side that stays down becomes a continuous speed hold.
 */
@SuppressLint("ViewConstructor")
private class PictureGestures(context: Context) : View(context) {
    var target: View? = null
    var onSpeed: ((Int) -> Unit)? = null
    var onSpeedEnd: (() -> Unit)? = null
    var onSeek: ((Boolean, Int) -> Unit)? = null
    var onEdgeTap: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val slop = 24f * density
    private val stepPx = 64f * density
    private val doubleTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var holding = false
    private var dragging = false
    private var anchorParentX = 0f
    private var fingerRate = SiteGesture.MIN_HOLD_RATE

    private var singlePending = false
    private var pendingX = 0f
    private var pendingY = 0f
    private var burstTotal = 0
    private var burstForward = true
    private var burstAt = 0L
    private var pendingAt = 0L

    private val holdCheck = Runnable { beginHold() }
    private val singleFlush = Runnable { flushSingle() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(holdCheck)
        removeCallbacks(singleFlush)
        if (holding) onSpeedEnd?.invoke()
        holding = false
        singlePending = false
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.downTime
                holding = false
                dragging = false
                removeCallbacks(holdCheck)
                if (width > 0 && event.x >= width * 0.64f) postDelayed(holdCheck, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (holding) {
                    publish(SiteGesture.holdRate(event.x + left - anchorParentX, stepPx))
                    return true
                }
                if (!dragging && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                    removeCallbacks(holdCheck)
                    if (singlePending) flushSingle()
                    dragging = true
                    forwardSynthetic(MotionEvent.ACTION_DOWN, downX, downY, downTime, downTime)
                }
                if (dragging) forward(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdCheck)
                if (holding) {
                    holding = false
                    onSpeedEnd?.invoke()
                    return true
                }
                if (dragging) {
                    forward(event)
                    dragging = false
                    return true
                }
                onTap(event.x, event.eventTime)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdCheck)
                if (holding) {
                    holding = false
                    onSpeedEnd?.invoke()
                } else if (dragging) {
                    forward(event)
                    dragging = false
                }
                return true
            }
        }
        return true
    }

    private fun beginHold() {
        if (dragging || holding) return
        if (singlePending) flushSingle()
        holding = true
        anchorParentX = downX + left
        fingerRate = -1
        publish(SiteGesture.MIN_HOLD_RATE)
    }

    private fun publish(rate: Int) {
        if (rate == fingerRate) return
        fingerRate = rate
        onSpeed?.invoke(rate)
    }

    private fun onTap(x: Float, now: Long) {
        val forward = width > 0 && x >= width / 2f
        if (singlePending && now - pendingAt <= doubleTimeout) {
            singlePending = false
            removeCallbacks(singleFlush)
            emitSeek(forward, now)
            return
        }
        if (burstTotal > 0 && now - burstAt <= SiteGesture.MULTI_TAP_WINDOW_MS) {
            emitSeek(forward, now)
            return
        }
        if (singlePending) flushSingle()
        singlePending = true
        pendingX = x
        pendingY = downY
        pendingAt = now
        postDelayed(singleFlush, doubleTimeout)
    }

    private fun emitSeek(forward: Boolean, now: Long) {
        burstTotal = SiteGesture.burstSeconds(burstTotal, burstForward, burstAt, forward, now)
        burstForward = forward
        burstAt = now
        onSeek?.invoke(forward, burstTotal)
    }

    private fun flushSingle() {
        if (!singlePending) return
        singlePending = false
        removeCallbacks(singleFlush)
        val now = SystemClock.uptimeMillis()
        forwardSynthetic(MotionEvent.ACTION_DOWN, pendingX, pendingY, now, now)
        forwardSynthetic(MotionEvent.ACTION_UP, pendingX, pendingY, now, now + 20)
        val edge = width > 0 && (pendingX <= width * 0.22f || pendingX >= width * 0.78f)
        if (edge) onEdgeTap?.invoke()
        burstTotal = 0
    }

    private fun forward(event: MotionEvent) {
        val destination = target ?: return
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(left.toFloat(), top.toFloat())
        destination.dispatchTouchEvent(copy)
        copy.recycle()
    }

    private fun forwardSynthetic(action: Int, x: Float, y: Float, down: Long, time: Long) {
        val destination = target ?: return
        val ev = MotionEvent.obtain(down, time, action, x + left, y + top, 0)
        destination.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private companion object {
        const val HOLD_MS = 320L
    }
}
