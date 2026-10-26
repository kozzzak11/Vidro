package app.vidro.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.vidro.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var httpFactory: DefaultHttpDataSource.Factory

    override fun onCreate() {
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.media_channel)
            .build()
        provider.setSmallIcon(R.drawable.ic_stat_vidro)
        setMediaNotificationProvider(provider)
        super.onCreate()

        httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val sourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .build()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName) {
                        return MediaSession.ConnectionResult.reject()
                    }
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(PlayerContract.ACTION_HEADERS, Bundle.EMPTY))
                        .add(SessionCommand(PlayerContract.ACTION_STOP, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        PlayerContract.ACTION_HEADERS -> applyHeaders(session, args)
                        PlayerContract.ACTION_STOP -> stopPlayback()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    private fun applyHeaders(session: MediaSession, args: Bundle) {
        val headers = LinkedHashMap<String, String>()
        args.getString(PlayerContract.EXTRA_UA)?.takeIf { it.isNotBlank() }?.let { httpFactory.setUserAgent(it) }
        args.getString(PlayerContract.EXTRA_REFERER)?.takeIf { it.startsWith("http") }?.let { headers["Referer"] = it }
        args.getString(PlayerContract.EXTRA_ORIGIN)?.takeIf { it.startsWith("http") }?.let { headers["Origin"] = it }
        args.getString(PlayerContract.EXTRA_COOKIE)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        httpFactory.setDefaultRequestProperties(headers)
        val url = args.getString(PlayerContract.EXTRA_URL)
        if (!url.isNullOrBlank()) {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerContract.EXTRA_URL, url)
                putExtra(PlayerContract.EXTRA_TITLE, args.getString(PlayerContract.EXTRA_TITLE))
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            session.setSessionActivity(pending)
        }
    }

    private fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.pause()
        player?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "vidro_playback"
    }
}
