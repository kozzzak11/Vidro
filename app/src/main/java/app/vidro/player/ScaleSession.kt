package app.vidro.player

import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.max

/** Fit / fill / stretch / 100% for this process. Pinch resets when the mode button is pressed. */
object ScaleSession {
    enum class Mode { FIT, FILL, STRETCH, ORIGINAL }

    var mode: Mode = Mode.FIT
    var pinch: Float = 1f

    fun cycle() {
        mode = when (mode) {
            Mode.FIT -> Mode.FILL
            Mode.FILL -> Mode.STRETCH
            Mode.STRETCH -> Mode.ORIGINAL
            Mode.ORIGINAL -> Mode.FIT
        }
        pinch = 1f
    }

    fun label(): String = when (mode) {
        Mode.FIT -> "Вписать"
        Mode.FILL -> "Заполнить"
        Mode.STRETCH -> "Растянуть"
        Mode.ORIGINAL -> "100%"
    }

    fun addPinch(zoom: Float) {
        if (zoom.isNaN() || zoom <= 0f) return
        pinch = (pinch * zoom).coerceIn(0.5f, 6f)
    }
}

@OptIn(UnstableApi::class)
fun applyPlayerScale(view: PlayerView, videoWidth: Int) {
    view.resizeMode = when (ScaleSession.mode) {
        ScaleSession.Mode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ScaleSession.Mode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    view.clipChildren = true
    view.clipToPadding = true
    val surface = view.videoSurfaceView ?: return
    val pinch = ScaleSession.pinch.coerceIn(0.5f, 6f)
    val (sx, sy) = when (ScaleSession.mode) {
        ScaleSession.Mode.ORIGINAL -> {
            val fitted = surface.width
            val pixel = if (videoWidth > 0 && fitted > 0) videoWidth.toFloat() / fitted else 1f
            val scale = (pixel * pinch).coerceIn(0.2f, 8f)
            scale to scale
        }
        ScaleSession.Mode.FIT -> pinch to pinch
        ScaleSession.Mode.FILL -> max(1f, pinch) to max(1f, pinch)
        ScaleSession.Mode.STRETCH -> 1f to 1f
    }
    if (surface.scaleX != sx) surface.scaleX = sx
    if (surface.scaleY != sy) surface.scaleY = sy
}

fun applyWebScale(view: View) {
    val pinch = ScaleSession.pinch.coerceIn(0.5f, 6f)
    view.pivotX = view.width / 2f
    view.pivotY = view.height / 2f
    view.scaleX = pinch
    view.scaleY = pinch
}
