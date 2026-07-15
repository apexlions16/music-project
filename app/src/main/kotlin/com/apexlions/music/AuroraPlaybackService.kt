package com.apexlions.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Oynatıcıyı Activity yaşam döngüsünden ayırır.
 * Aynı process içinde Activity kapanıp açılsa da tek ExoPlayer örneği ve kuyruğu korunur.
 */
internal object AuroraPlaybackEngine {
    private val lock = Any()
    private var internalPlayer: ExoPlayer? = null
    private var internalHttpFactory: DefaultHttpDataSource.Factory? = null

    var currentTrack by mutableStateOf<Track?>(null)
    var currentRelease by mutableStateOf<Release?>(null)
    var currentCover by mutableStateOf("")
    var currentSource by mutableStateOf<AudioSource?>(null)
    var currentArtistLine by mutableStateOf("")
    var queue by mutableStateOf<List<Track>>(emptyList())
    var preferredQuality by mutableStateOf("hires")
    var shuffle by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playbackError by mutableStateOf<String?>(null)

    fun player(context: Context): ExoPlayer = synchronized(lock) {
        internalPlayer ?: buildPlayer(context.applicationContext).also { internalPlayer = it }
    }

    fun setAuthorizationToken(context: Context, token: String) {
        player(context)
        val headers = token.trim().takeIf(String::isNotBlank)
            ?.let { mapOf("Authorization" to "Bearer $it") }
            ?: emptyMap()
        internalHttpFactory?.setDefaultRequestProperties(headers)
    }

    fun startService(context: Context) {
        val intent = Intent(context.applicationContext, AuroraPlaybackService::class.java)
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AuroraMusic/0.5.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
        internalHttpFactory = httpFactory

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { player ->
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
    }
}

class AuroraPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = AuroraPlaybackEngine.player(this)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AuroraV4Activity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Kullanıcı uygulamayı son uygulamalar ekranından kapatsa bile çalan müzik devam eder.
        // MediaSessionService, oynatma durduğunda kendi foreground yaşam döngüsünü yönetir.
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        // ExoPlayer process genelinde tutulur. Servis sistem tarafından yeniden oluşturulursa
        // aynı kuyruk ve oynatma konumu yeni MediaSession'a tekrar bağlanır.
        super.onDestroy()
    }
}
