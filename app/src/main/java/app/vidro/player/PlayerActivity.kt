package app.vidro.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.vidro.ui.formatClock
import app.vidro.ui.theme.VidroPalette
import app.vidro.ui.theme.VidroTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var playerView: PlayerView? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var orientationMode = 2
    private var hideJob: Job? = null
    private var hintJob: Job? = null
    private var tickerJob: Job? = null

    private var ready by mutableStateOf(false)
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var speed by mutableFloatStateOf(1f)
    private var locked by mutableStateOf(false)
    private var controls by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var title by mutableStateOf("")
    private var speedMenu by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var seeking by mutableStateOf(false)
    private var seekPreview by mutableLongStateOf(0L)
    private var gesture by mutableStateOf<String?>(null)
    private var gestureText by mutableStateOf("")
    private var seekHint by mutableStateOf<String?>(null)
    private var unlockHint by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        title = intent.getStringExtra(PlayerContract.EXTRA_TITLE).orEmpty()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 20)
        }
        setContent { PlayerScreen() }
        connectAndPlay(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(PlayerContract.EXTRA_URL)
        if (!url.isNullOrBlank() && url != mediaController?.currentMediaItem?.localConfiguration?.uri?.toString()) {
            connectAndPlay(intent)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && playing && error == null && !locked) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) controls = false
    }

    private fun connectAndPlay(source: android.content.Intent) {
        val url = source.getStringExtra(PlayerContract.EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        error = null
        buffering = true
        ContextCompat.startForegroundService(this, android.content.Intent(this, PlaybackService::class.java))
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            mediaController = controller
            playerView?.player = controller
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    window.decorView.keepScreenOn = isPlaying
                    updatePipRatio(controller.videoSize)
                    if (isPlaying) scheduleHide()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                    if (playbackState == Player.STATE_READY) {
                        ready = true
                        duration = controller.duration.coerceAtLeast(0L)
                        buffering = false
                    }
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = "Не удалось открыть поток"
                    buffering = false
                    controls = true
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    updatePipRatio(videoSize)
                }
            })
            val headers = Bundle().apply {
                putString(PlayerContract.EXTRA_URL, url)
                putString(PlayerContract.EXTRA_TITLE, source.getStringExtra(PlayerContract.EXTRA_TITLE))
                putString(PlayerContract.EXTRA_REFERER, source.getStringExtra(PlayerContract.EXTRA_REFERER))
                putString(PlayerContract.EXTRA_ORIGIN, source.getStringExtra(PlayerContract.EXTRA_ORIGIN))
                putString(PlayerContract.EXTRA_COOKIE, source.getStringExtra(PlayerContract.EXTRA_COOKIE))
                putString(PlayerContract.EXTRA_UA, source.getStringExtra(PlayerContract.EXTRA_UA))
            }
            val command = controller.sendCustomCommand(SessionCommand(PlayerContract.ACTION_HEADERS, Bundle.EMPTY), headers)
            command.addListener({
                val kind = source.getStringExtra(PlayerContract.EXTRA_KIND)
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(source.getStringExtra(PlayerContract.EXTRA_TITLE) ?: url)
                            .setArtist("Vidro")
                            .build(),
                    )
                    .apply {
                        mimeFor(kind)?.let { setMimeType(it) }
                    }
                    .build()
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
                ready = true
                startTicker()
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun mimeFor(kind: String?): String? = when (kind) {
        "HLS" -> MimeTypes.APPLICATION_M3U8
        "DASH" -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (true) {
                val controller = mediaController
                if (controller != null && !seeking) {
                    position = controller.currentPosition.coerceAtLeast(0L)
                    val d = controller.duration
                    if (d != C.TIME_UNSET && d > 0) duration = d
                    speed = controller.playbackParameters.speed
                }
                delay(400)
            }
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(3200)
            if (playing && error == null && !speedMenu && !locked) controls = false
        }
    }

    private fun toggleControls() {
        if (locked) {
            unlockHint = true
            hintJob?.cancel()
            hintJob = lifecycleScope.launch {
                delay(1600)
                unlockHint = false
            }
            return
        }
        controls = !controls
        if (controls) scheduleHide()
    }

    private fun togglePlay() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
        controls = true
        scheduleHide()
    }

    private fun seekBy(deltaMs: Long) {
        val controller = mediaController ?: return
        val length = controller.duration.takeIf { it > 0 } ?: duration
        val target = (controller.currentPosition + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        controller.seekTo(target)
        position = target
        seekHint = if (deltaMs < 0) "−10 с" else "+10 с"
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(700)
            seekHint = null
        }
    }

    private fun closePlayer() {
        val controller = mediaController
        if (controller != null) {
            runCatching {
                controller.sendCustomCommand(SessionCommand(PlayerContract.ACTION_STOP, Bundle.EMPTY), Bundle.EMPTY)
            }
            controller.pause()
        }
        finish()
    }

    private fun cycleOrientation() {
        orientationMode = (orientationMode + 1) % 3
        requestedOrientation = when (orientationMode) {
            0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val params = pipParams(mediaController?.videoSize)
        enterPictureInPictureMode(params)
    }

    private fun updatePipRatio(videoSize: VideoSize) {
        if (Build.VERSION.SDK_INT < 26) return
        setPictureInPictureParams(pipParams(videoSize))
    }

    private fun pipParams(videoSize: VideoSize?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val width = videoSize?.width ?: 16
        val height = videoSize?.height ?: 9
        if (width > 0 && height > 0) {
            val ratio = (width.toFloat() / height.toFloat()).coerceIn(0.42f, 2.39f)
            builder.setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(playing && error == null && !locked)
            builder.setSeamlessResizeEnabled(true)
        }
        val hint = android.graphics.Rect()
        window?.decorView?.getGlobalVisibleRect(hint)
        if (!hint.isEmpty) builder.setSourceRectHint(hint)
        return builder.build()
    }

    private fun systemBrightness(): Float {
        return try {
            val value = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            (value / 255f).coerceIn(0.05f, 1f)
        } catch (_: Exception) {
            0.5f
        }
    }

    @Composable
    private fun PlayerScreen() {
        val speeds = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f) }
        BackHandler {
            if (locked) toggleControls() else closePlayer()
        }
        VidroTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = { toggleControls() },
                            onDoubleTap = { offset ->
                                if (locked) return@detectTapGestures
                                when {
                                    offset.x < size.width * 0.36f -> seekBy(-10_000)
                                    offset.x > size.width * 0.64f -> seekBy(10_000)
                                    else -> togglePlay()
                                }
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var accumX = 0f
                        var accumY = 0f
                        var startX = 0f
                        var mode = 0
                        var origin = 0L
                        var brightnessStart = 0.5f
                        var volumeStart = 0f
                        detectDragGestures(
                            onDragStart = { offset ->
                                accumX = 0f
                                accumY = 0f
                                startX = offset.x
                                mode = 0
                                origin = mediaController?.currentPosition ?: position
                                val current = window.attributes.screenBrightness
                                brightnessStart = if (current >= 0f) current else systemBrightness()
                                volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                            },
                            onDragCancel = { gesture = null },
                            onDragEnd = { gesture = null },
                            onDrag = { change, amount ->
                                change.consume()
                                accumX += amount.x
                                accumY += amount.y
                                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                val heightPx = size.height.toFloat().coerceAtLeast(1f)
                                if (mode == 0 && (abs(accumX) > 28f || abs(accumY) > 28f)) {
                                    mode = if (abs(accumX) > abs(accumY)) 1 else if (startX < widthPx / 2f) 2 else 3
                                }
                                when (mode) {
                                    1 -> {
                                        val length = duration.takeIf { it > 0 } ?: (mediaController?.duration ?: 0L)
                                        val windowMs = if (length > 0) minOf(length, 8 * 60_000L) else 8 * 60_000L
                                        val delta = (accumX / widthPx * windowMs).toLong()
                                        val target = (origin + delta).let { value ->
                                            if (length > 0) value.coerceIn(0L, length) else value.coerceAtLeast(0L)
                                        }
                                        mediaController?.seekTo(target)
                                        position = target
                                        seekPreview = target
                                        gesture = "seek"
                                        gestureText = formatClock(target)
                                    }
                                    2 -> {
                                        val next = (brightnessStart - accumY / heightPx).coerceIn(0.01f, 1f)
                                        val attrs = window.attributes
                                        attrs.screenBrightness = next
                                        window.attributes = attrs
                                        gesture = "brightness"
                                        gestureText = "${(next * 100).roundToInt()}%"
                                    }
                                    3 -> {
                                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
                                        val next = (volumeStart - accumY / heightPx * max).coerceIn(0f, max)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next.roundToInt(), 0)
                                        gesture = "volume"
                                        gestureText = "${(next / max * 100).roundToInt()}%"
                                    }
                                }
                            },
                        )
                    },
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            player = mediaController
                            playerView = this
                        }
                    },
                    update = { it.player = mediaController },
                )
                if (buffering && error == null) {
                    CircularProgressIndicator(
                        color = VidroPalette.Lime,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                AnimatedVisibility(visible = gesture != null && !inPip, enter = fadeIn(), exit = fadeOut()) {
                    GestureBadge(gesture, gestureText, Modifier.align(Alignment.Center))
                }
                if (seekHint != null && !inPip) {
                    Text(
                        seekHint.orEmpty(),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (!inPip && (controls || error != null)) {
                    Controls(speeds)
                }
                if (locked && unlockHint && !inPip) {
                    IconButton(
                        onClick = { locked = false; controls = true },
                        modifier = Modifier.align(Alignment.Center).size(64.dp).background(Color(0xCC10120E), CircleShape),
                    ) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "Разблокировать", tint = VidroPalette.Lime)
                    }
                }
                error?.let { message ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(message, color = Color.White, fontSize = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            TextButton(onClick = {
                                error = null
                                mediaController?.prepare()
                                mediaController?.play()
                            }) { Text("Ещё раз", color = VidroPalette.Lime) }
                            TextButton(onClick = { closePlayer() }) { Text("На страницу", color = Color.White) }
                        }
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                playerView?.player = null
                mediaController?.release()
                mediaController = null
            }
        }
    }

    @Composable
    private fun Controls(speeds: List<Float>) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { closePlayer() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
                Text(
                    title.ifBlank { "Видео" },
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                )
                IconButton(onClick = { enterPip() }) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Картинка в картинке", tint = Color.White)
                }
                IconButton(onClick = { cycleOrientation() }) {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = "Поворот", tint = Color.White)
                }
                IconButton(onClick = { locked = true; controls = false; unlockHint = true }) {
                    Icon(Icons.Filled.Lock, contentDescription = "Заблокировать экран", tint = Color.White)
                }
            }
            IconButton(
                onClick = { togglePlay() },
                modifier = Modifier.align(Alignment.Center).size(72.dp).background(Color(0x9910120E), CircleShape),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Пауза" else "Воспроизвести",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (speedMenu) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        speeds.forEach { value ->
                            val selected = abs(speed - value) < 0.01f
                            TextButton(
                                onClick = {
                                    mediaController?.setPlaybackSpeed(value)
                                    speed = value
                                    speedMenu = false
                                },
                                modifier = Modifier.background(
                                    if (selected) VidroPalette.Lime else Color(0xFF2C3124),
                                    RoundedCornerShape(12.dp),
                                ),
                            ) {
                                Text(
                                    speedLabel(value),
                                    color = if (selected) VidroPalette.OnLime else Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
                val sliderValue = when {
                    duration <= 0L -> 0f
                    seeking -> seekPreview.toFloat() / duration
                    else -> position.toFloat() / duration
                }.coerceIn(0f, 1f)
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        seeking = true
                        seekPreview = (it * duration).toLong()
                        position = seekPreview
                    },
                    onValueChangeFinished = {
                        mediaController?.seekTo(seekPreview)
                        seeking = false
                        scheduleHide()
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = VidroPalette.Lime,
                        activeTrackColor = VidroPalette.Lime,
                        inactiveTrackColor = Color(0xFF3A4030),
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(if (seeking) seekPreview else position), color = Color.White, fontSize = 12.sp)
                    Text("  /  ", color = VidroPalette.Muted, fontSize = 12.sp)
                    Text(formatClock(duration), color = VidroPalette.Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { speedMenu = !speedMenu; controls = true }) {
                        Icon(Icons.Filled.Speed, contentDescription = null, tint = VidroPalette.Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(speedLabel(speed), color = VidroPalette.Lime)
                    }
                }
            }
        }
    }

    @Composable
    private fun GestureBadge(kind: String?, text: String, modifier: Modifier) {
        Column(
            modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCC10120E)).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon = when (kind) {
                "brightness" -> Icons.Filled.LightMode
                "volume" -> if (text.startsWith("0")) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp
                else -> Icons.Filled.PlayArrow
            }
            Icon(icon, contentDescription = null, tint = VidroPalette.Lime)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }

    private fun speedLabel(value: Float): String {
        val rounded = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        return "$rounded×"
    }

    override fun onDestroy() {
        hideJob?.cancel()
        window.decorView.keepScreenOn = false
        super.onDestroy()
    }
}
