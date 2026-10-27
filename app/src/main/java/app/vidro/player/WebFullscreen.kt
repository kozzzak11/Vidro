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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The site's fullscreen element, with Vidro's fill, edge-seek and exit laid on top.
 * Touches outside the side strips go straight to the site, so skip intro and the
 * player's own buttons stay tappable.
 */
@Composable
fun WebFullscreen(
    custom: View,
    inPip: Boolean,
    trafficLabel: String?,
    onClose: () -> Unit,
    onPip: () -> Unit,
    eval: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var holdLabel by remember { mutableStateOf<String?>(null) }
    var modeLabel by remember { mutableStateOf(ScaleSession.label()) }
    var chromeTick by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
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
        ScaleSession.mode = ScaleSession.Mode.FILL
        ScaleSession.pinch = 1f
        modeLabel = ScaleSession.label()
        applyWebScale(custom)
        repeat(3) {
            eval(Scripts.objectFit(ScaleSession.mode.name))
            delay(if (it == 0) 80 else 500)
        }
    }
    LaunchedEffect(chromeTick, custom) {
        chrome = true
        delay(3500)
        chrome = false
    }
    val reveal = { chromeTick += 1 }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> WebStage(context) },
            update = { stage ->
                stage.mount(custom)
                stage.onHold = { forward ->
                    reveal()
                    hold.start(forward)
                }
                stage.onHoldEnd = { hold.stop() }
                stage.onEdgeTap = { reveal() }
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
    private val rewindStrip = EdgeSeekStrip(context)
    private val forwardStrip = EdgeSeekStrip(context)
    var onHold: ((Boolean) -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onEdgeTap: (() -> Unit)? = null

    init {
        clipChildren = true
        clipToPadding = true
        addView(rewindStrip)
        addView(forwardStrip)
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
        rewindStrip.target = custom
        forwardStrip.target = custom
        rewindStrip.onHoldStart = { onHold?.invoke(false) }
        forwardStrip.onHoldStart = { onHold?.invoke(true) }
        rewindStrip.onHoldEnd = { onHoldEnd?.invoke() }
        forwardStrip.onHoldEnd = { onHoldEnd?.invoke() }
        rewindStrip.onTap = { onEdgeTap?.invoke() }
        forwardStrip.onTap = { onEdgeTap?.invoke() }
        rewindStrip.bringToFront()
        forwardStrip.bringToFront()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val widthPx = width
        val heightPx = height
        if (widthPx <= 0 || heightPx <= 0) return
        val stripW = (widthPx * 0.15f).toInt().coerceIn(dp(48), (widthPx * 0.22f).toInt().coerceAtLeast(dp(48)))
        val stripTop = (heightPx * 0.20f).toInt()
        val stripBottom = (heightPx * 0.70f).toInt()
        rewindStrip.layout(0, stripTop, stripW, stripBottom)
        forwardStrip.layout(widthPx - stripW, stripTop, widthPx, stripBottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

@SuppressLint("ViewConstructor")
private class EdgeSeekStrip(context: Context) : View(context) {
    var target: View? = null
    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private var moved = false
    private val slop = 24f * resources.displayMetrics.density

    private val holdCheck = Runnable {
        if (moved || holding) return@Runnable
        holding = true
        cancelTarget()
        onHoldStart?.invoke()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                holding = false
                moved = false
                forwardToTarget(event)
                postDelayed(holdCheck, 320)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                    moved = true
                    removeCallbacks(holdCheck)
                }
                if (!holding) forwardToTarget(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdCheck)
                if (holding) {
                    onHoldEnd?.invoke()
                } else {
                    forwardToTarget(event)
                    if (!moved) onTap?.invoke()
                }
                holding = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdCheck)
                if (holding) onHoldEnd?.invoke() else forwardToTarget(event)
                holding = false
                return true
            }
        }
        return true
    }

    private fun forwardToTarget(event: MotionEvent) {
        val destination = target ?: return
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(left.toFloat(), top.toFloat())
        destination.dispatchTouchEvent(copy)
        copy.recycle()
    }

    private fun cancelTarget() {
        val destination = target ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX + left, downY + top, 0)
        destination.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
}
