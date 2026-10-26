package app.vidro.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.vidro.engine.Scripts
import app.vidro.ui.theme.VidroPalette
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun WebFullscreen(
    custom: View,
    inPip: Boolean,
    onClose: () -> Unit,
    onPip: () -> Unit,
    eval: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var holdLabel by remember { mutableStateOf<String?>(null) }
    var modeLabel by remember { mutableStateOf(ScaleSession.label()) }
    val hold = remember {
        HoldSeekController(
            scope = scope,
            onFast = { fast -> eval(if (fast) Scripts.holdRate(2.0) else Scripts.holdRelease()) },
            onSeekBy = { delta -> eval(Scripts.holdSeek(delta / 1000.0)) },
            onLabel = { holdLabel = it },
        )
    }
    DisposableEffect(hold) {
        onDispose { hold.stop() }
    }
    LaunchedEffect(custom) {
        eval(Scripts.objectFit(ScaleSession.mode.name))
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                }
            },
            update = { frame ->
                if (custom.parent !== frame) {
                    (custom.parent as? ViewGroup)?.removeView(custom)
                    frame.removeAllViews()
                    frame.addView(
                        custom,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    frame.addView(
                        FullscreenTouchLayer(frame.context).apply { target = custom },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                val layer = frame.getChildAt(frame.childCount - 1) as? FullscreenTouchLayer
                layer?.target = custom
                layer?.onHoldStart = { forward -> hold.start(forward) }
                layer?.onHoldEnd = { hold.stop() }
                layer?.onPinch = { zoom ->
                    ScaleSession.addPinch(zoom)
                    applyWebScale(custom)
                    modeLabel = ScaleSession.label()
                }
                layer?.onSwipePip = onPip
                applyWebScale(custom)
            },
        )
        if (!inPip && holdLabel != null) {
            Text(
                holdLabel.orEmpty(),
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (!inPip) {
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding()) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
            IconButton(
                onClick = {
                    ScaleSession.cycle()
                    modeLabel = ScaleSession.label()
                    applyWebScale(custom)
                    eval(Scripts.objectFit(ScaleSession.mode.name))
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 4.dp),
            ) {
                Icon(Icons.Filled.AspectRatio, contentDescription = modeLabel, tint = VidroPalette.Lime)
            }
            Text(
                modeLabel,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 48.dp, end = 16.dp),
            )
        }
    }
}

@SuppressLint("ViewConstructor")
private class FullscreenTouchLayer(context: Context) : View(context) {
    var target: View? = null
    var onHoldStart: ((Boolean) -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onPinch: ((Float) -> Unit)? = null
    var onSwipePip: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var holding = false
    private var forwarded = false
    private var decided = false
    private var pipSent = false
    private var span = 0f

    private val holdCheck = Runnable {
        if (decided || forwarded) return@Runnable
        val w = width.toFloat().coerceAtLeast(1f)
        val onEdge = downX <= w * 0.18f || downX >= w * 0.82f
        if (!onEdge) return@Runnable
        holding = true
        decided = true
        onHoldStart?.invoke(downX >= w * 0.5f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                holding = false
                forwarded = false
                decided = false
                pipSent = false
                span = 0f
                postDelayed(holdCheck, 300)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdCheck)
                if (holding) {
                    holding = false
                    onHoldEnd?.invoke()
                }
                cancelForward()
                span = spacing(event)
                decided = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && span > 0f) {
                    val next = spacing(event)
                    if (next > 0f) {
                        onPinch?.invoke(next / span)
                        span = next
                    }
                    return true
                }
                val adx = abs(event.x - downX)
                val ady = abs(event.y - downY)
                val dy = event.y - downY
                if (!decided && (adx > 28f || ady > 28f)) {
                    removeCallbacks(holdCheck)
                    decided = true
                    val w = width.toFloat().coerceAtLeast(1f)
                    val center = downX in w * 0.30f..w * 0.70f
                    val fromTop = downY < height * 0.18f
                    if (!((center || fromTop) && dy > 0f && ady > adx)) {
                        forwardDown(event)
                    }
                }
                if (forwarded) {
                    target?.dispatchTouchEvent(event)
                } else if (decided && !pipSent) {
                    val w = width.toFloat().coerceAtLeast(1f)
                    val center = downX in w * 0.30f..w * 0.70f
                    val fromTop = downY < height * 0.18f
                    if ((center || fromTop) && dy > 160f && dy > adx * 1.6f) {
                        pipSent = true
                        onSwipePip?.invoke()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdCheck)
                if (holding) onHoldEnd?.invoke()
                if (forwarded) {
                    target?.dispatchTouchEvent(event)
                } else if (!decided && !holding && event.actionMasked == MotionEvent.ACTION_UP) {
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
                    target?.dispatchTouchEvent(down)
                    target?.dispatchTouchEvent(event)
                    down.recycle()
                }
                holding = false
                forwarded = false
                return true
            }
        }
        return true
    }

    private fun forwardDown(current: MotionEvent) {
        if (forwarded) return
        val down = MotionEvent.obtain(current.downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
        target?.dispatchTouchEvent(down)
        down.recycle()
        forwarded = true
    }

    private fun cancelForward() {
        if (!forwarded) return
        val cancel = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        target?.dispatchTouchEvent(cancel)
        cancel.recycle()
        forwarded = false
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }
}
