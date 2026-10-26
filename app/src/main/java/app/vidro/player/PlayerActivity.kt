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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.vidro.engine.UrlSniffer
import app.vidro.ui.formatClock
import app.vidro.ui.theme.VidroPalette
import app.vidro.ui.theme.VidroTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var playerView: PlayerView? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var orientationMode = 2
    private var orientationTouched = false
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
    private var holdLabel by mutableStateOf<String?>(null)
    private var unlockHint by mutableStateOf(false)
    private var scaleLabel by mutableStateOf(ScaleSession.label())
    private var qualityMenu by mutableStateOf(false)
    private var sleepMenu by mutableStateOf(false)
    private var qualities by mutableStateOf<List<QualityChoice>>(listOf(QualityChoice.auto()))
    private var qualityId by mutableStateOf("auto")
    private var sleepLeft by mutableStateOf(0)
    private var maxHeightCap = 0
    private var variants = emptyList<PlayVariant>()
    private var currentUrl = ""
    private var currentKind = ""
    private var speedBeforeHold = 1f
    private var dragOrigin = 0L
    private var dragBrightness = 0.5f
    private var dragVolume = 0f
    private var sleepJob: Job? = null
    private val holdSeek by lazy {
        HoldSeekController(
            scope = lifecycleScope,
            onFast = { enable ->
                val controller = mediaController ?: return@HoldSeekController
                if (enable) {
                    speedBeforeHold = controller.playbackParameters.speed
                    controller.setPlaybackSpeed(2f)
                } else {
                    controller.setPlaybackSpeed(speedBeforeHold)
                    speed = speedBeforeHold
                }
            },
            onSeekBy = { delta -> seekBy(delta, hint = false) },
            onLabel = { holdLabel = it },
        )
    }

    private val gestureHandler = object : PlayerGestureHandler {
        override fun onTap() = toggleControls()
        override fun onDouble(zone: Int) {
            when (zone) {
                -1 -> seekBy(-10_000)
                1 -> seekBy(10_000)
                else -> togglePlay()
            }
        }

        override fun onPinch(zoom: Float) {
            ScaleSession.addPinch(zoom)
            scaleLabel = ScaleSession.label()
            playerView?.let { applyPlayerScale(it, mediaController?.videoSize?.width ?: 0) }
        }

        override fun onHoldStart(forward: Boolean) {
            gesture = null
            holdSeek.start(forward)
        }

        override fun onHoldEnd() = holdSeek.stop()

        override fun onDragStart() {
            holdSeek.stop()
            dragOrigin = mediaController?.currentPosition ?: position
            val current = window.attributes.screenBrightness
            dragBrightness = if (current >= 0f) current else systemBrightness()
            dragVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        }

        override fun onDrag(mode: Int, totalX: Float, totalY: Float, width: Float, height: Float) {
            when (mode) {
                1 -> {
                    val length = duration.takeIf { it > 0 } ?: (mediaController?.duration ?: 0L)
                    val windowMs = if (length > 0) minOf(length, 8 * 60_000L) else 8 * 60_000L
                    val delta = (totalX / width * windowMs).toLong()
                    val target = (dragOrigin + delta).let { value ->
                        if (length > 0) value.coerceIn(0L, length) else value.coerceAtLeast(0L)
                    }
                    mediaController?.seekTo(target)
                    position = target
                    seekPreview = target
                    gesture = "seek"
                    gestureText = formatClock(target)
                }
                2 -> {
                    val next = (dragBrightness - totalY / height).coerceIn(0.01f, 1f)
                    val attrs = window.attributes
                    attrs.screenBrightness = next
                    window.attributes = attrs
                    gesture = "brightness"
                    gestureText = "${(next * 100).roundToInt()}%"
                }
                3 -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
                    val next = (dragVolume - totalY / height * max).coerceIn(0f, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next.roundToInt(), 0)
                    gesture = "volume"
                    gestureText = "${(next / max * 100).roundToInt()}%"
                }
            }
        }

        override fun onDragEnd() {
            gesture = null
        }

        override fun onPip() {
            if (!locked) enterPip()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        title = intent.getStringExtra(PlayerContract.EXTRA_TITLE).orEmpty()
        enterImmersive()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
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
                        publishQualities(controller.currentTracks)
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    publishQualities(tracks)
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = "Не удалось открыть поток"
                    buffering = false
                    controls = true
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    updatePipRatio(videoSize)
                    playerView?.let { applyPlayerScale(it, videoSize.width) }
                    if (!orientationTouched && videoSize.width > 0 && videoSize.height > 0) {
                        requestedOrientation = if (videoSize.width >= videoSize.height) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        }
                    }
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
                currentKind = kind.orEmpty()
                currentUrl = url
                variants = parseVariants(source.getStringExtra(PlayerContract.EXTRA_VARIANTS))
                maxHeightCap = source.getIntExtra(PlayerContract.EXTRA_MAX_HEIGHT, 0).coerceAtLeast(0)
                var playUrl = url
                var playKind = kind
                if (maxHeightCap > 0 && kind != "HLS" && kind != "DASH") {
                    val known = variants.firstOrNull { it.url == url }?.height ?: UrlSniffer.guessHeight(null, url)
                    if (known > maxHeightCap) {
                        val lower = variants.filter { it.height in 1..maxHeightCap }.maxByOrNull { it.height }
                        if (lower != null) {
                            playUrl = lower.url
                            playKind = lower.kind
                        }
                    }
                }
                currentUrl = playUrl
                currentKind = playKind.orEmpty()
                applyAutoQuality()
                val item = MediaItem.Builder()
                    .setUri(playUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(source.getStringExtra(PlayerContract.EXTRA_TITLE) ?: url)
                            .setArtist("Vidro")
                            .build(),
                    )
                    .apply {
                        mimeFor(playKind)?.let { setMimeType(it) }
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
            if (playing && error == null && !speedMenu && !qualityMenu && !sleepMenu && !locked) controls = false
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

    private fun seekBy(deltaMs: Long, hint: Boolean = true) {
        val controller = mediaController ?: return
        val length = controller.duration.takeIf { it > 0 } ?: duration
        val target = (controller.currentPosition + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        controller.seekTo(target)
        position = target
        if (!hint) return
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
        orientationTouched = true
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
                    .vidroPlayerGestures(locked, gestureHandler),
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
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                applyPlayerScale(this, mediaController?.videoSize?.width ?: 0)
                            }
                        }
                    },
                    update = {
                        it.player = mediaController
                        applyPlayerScale(it, mediaController?.videoSize?.width ?: 0)
                    },
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
                if ((seekHint != null || holdLabel != null) && !inPip) {
                    Text(
                        holdLabel ?: seekHint.orEmpty(),
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
                if (qualityMenu) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        qualities.forEach { choice ->
                            val selected = choice.id == qualityId
                            TextButton(
                                onClick = { selectQuality(choice); qualityMenu = false },
                                modifier = Modifier.background(
                                    if (selected) VidroPalette.Lime else Color(0xFF2C3124),
                                    RoundedCornerShape(12.dp),
                                ),
                            ) {
                                Text(choice.label, color = if (selected) VidroPalette.OnLime else Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
                if (sleepMenu) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 15, 30, 45, 60).forEach { minutes ->
                            TextButton(onClick = { startSleep(minutes); sleepMenu = false }) {
                                Text(if (minutes == 0) "Выкл" else "$minutes мин", color = VidroPalette.Lime, fontSize = 12.sp)
                            }
                        }
                    }
                }
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
                    TextButton(onClick = {
                        qualityMenu = !qualityMenu
                        speedMenu = false
                        sleepMenu = false
                        controls = true
                    }) {
                        Icon(Icons.Filled.HighQuality, contentDescription = "Качество", tint = VidroPalette.Lime, modifier = Modifier.size(18.dp))
                    }
                    TextButton(onClick = { cycleScale() }) {
                        Icon(Icons.Filled.AspectRatio, contentDescription = scaleLabel, tint = VidroPalette.Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(scaleLabel, color = VidroPalette.Lime, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        sleepMenu = !sleepMenu
                        qualityMenu = false
                        speedMenu = false
                        controls = true
                    }) {
                        Icon(Icons.Filled.Bedtime, contentDescription = "Таймер сна", tint = VidroPalette.Lime, modifier = Modifier.size(18.dp))
                        if (sleepLeft > 0) {
                            Spacer(Modifier.width(2.dp))
                            Text("${sleepLeft}м", color = VidroPalette.Lime, fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { speedMenu = !speedMenu; qualityMenu = false; sleepMenu = false; controls = true }) {
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

    private fun cycleScale() {
        ScaleSession.cycle()
        scaleLabel = ScaleSession.label()
        playerView?.let { applyPlayerScale(it, mediaController?.videoSize?.width ?: 0) }
        controls = true
        scheduleHide()
    }

    private fun startSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            sleepLeft = 0
            return
        }
        val ends = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = lifecycleScope.launch {
            while (true) {
                val left = ends - System.currentTimeMillis()
                if (left <= 0) {
                    sleepLeft = 0
                    mediaController?.pause()
                    break
                }
                sleepLeft = ((left + 59_999) / 60_000L).toInt()
                delay(1000)
            }
        }
    }

    private fun applyAutoQuality() {
        val controller = mediaController ?: return
        qualityId = "auto"
        val builder = controller.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (maxHeightCap > 0) builder.setMaxVideoSize(Int.MAX_VALUE, maxHeightCap)
        else builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        controller.trackSelectionParameters = builder.build()
    }

    private fun publishQualities(tracks: Tracks) {
        val options = ArrayList<QualityChoice>()
        options += QualityChoice.auto()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val height = format.height
                val label = when {
                    height > 0 -> "${height}p"
                    format.bitrate > 0 -> "${format.bitrate / 1000} kbps"
                    else -> "Дорожка ${index + 1}"
                }
                options += QualityChoice(
                    id = "track-$index-${format.bitrate}-${format.height}",
                    label = label,
                    height = height,
                    trackGroup = group.mediaTrackGroup,
                    trackIndex = index,
                )
            }
        }
        variants.forEach { variant ->
            if (variant.url == currentUrl) return@forEach
            options += QualityChoice(
                id = "url-${variant.url.hashCode()}",
                label = variant.label.ifBlank { if (variant.height > 0) "${variant.height}p" else variant.kind },
                height = variant.height,
                variant = variant,
            )
        }
        qualities = options.distinctBy { it.id }
    }

    private fun selectQuality(choice: QualityChoice) {
        val controller = mediaController ?: return
        qualityId = choice.id
        val variant = choice.variant
        if (variant != null) {
            val positionNow = controller.currentPosition.coerceAtLeast(0L)
            val item = MediaItem.Builder()
                .setUri(variant.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title.ifBlank { variant.label }).setArtist("Vidro").build())
                .apply { mimeFor(variant.kind)?.let { setMimeType(it) } }
                .build()
            currentUrl = variant.url
            currentKind = variant.kind
            controller.setMediaItem(item, positionNow)
            controller.prepare()
            controller.play()
            return
        }
        val group = choice.trackGroup
        val builder = controller.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (group != null && choice.trackIndex >= 0) {
            builder.setOverrideForType(TrackSelectionOverride(group, listOf(choice.trackIndex)))
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else if (maxHeightCap > 0) {
            builder.setMaxVideoSize(Int.MAX_VALUE, maxHeightCap)
        } else {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        controller.trackSelectionParameters = builder.build()
    }

    private fun parseVariants(raw: String?): List<PlayVariant> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("u")
                    if (!url.startsWith("http")) continue
                    add(PlayVariant(url, item.optString("l"), item.optInt("h"), item.optString("k")))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun speedLabel(value: Float): String {
        val rounded = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        return "$rounded×"
    }

    override fun onDestroy() {
        hideJob?.cancel()
        sleepJob?.cancel()
        holdSeek.stop()
        window.decorView.keepScreenOn = false
        super.onDestroy()
    }
}

private data class PlayVariant(val url: String, val label: String, val height: Int, val kind: String)

@OptIn(UnstableApi::class)
private data class QualityChoice(
    val id: String,
    val label: String,
    val height: Int = 0,
    val trackGroup: androidx.media3.common.TrackGroup? = null,
    val trackIndex: Int = -1,
    val variant: PlayVariant? = null,
) {
    companion object {
        fun auto() = QualityChoice("auto", "Авто")
    }
}
