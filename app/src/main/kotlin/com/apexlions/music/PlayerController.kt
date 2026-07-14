package com.apexlions.music

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

class PlayerController(private val context: Context) {
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("AuroraMusic/0.3.0")
        .setAllowCrossProtocolRedirects(true)
    private val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    var currentTrack by mutableStateOf<Track?>(null)
        private set
    var currentRelease by mutableStateOf<Release?>(null)
        private set
    var currentCover by mutableStateOf("")
        private set
    var currentSource by mutableStateOf<AudioSource?>(null)
        private set
    var currentArtistLine by mutableStateOf("")
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var preferredQuality by mutableStateOf("hires")
        private set
    var shuffle by mutableStateOf(false)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    init {
        applyAuthorizationHeader(AppSettings.huggingFaceToken(context))
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val reported = player.duration.takeIf { it > 0 } ?: currentTrack?.durationSeconds?.times(1000L) ?: 0L
                durationMs = reported
                if (playbackState == Player.STATE_READY) playbackError = null
            }

            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                playbackError = when {
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Hugging Face erişimi reddedildi. Ayarlar bölümünden bu gated depo için yetkili Read token girin."
                    error.errorCodeName.contains("HTTP", ignoreCase = true) ->
                        "Ses kaynağına HTTP üzerinden ulaşılamadı: ${error.errorCodeName}"
                    else -> "Şarkı açılamadı: ${error.errorCodeName}"
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                queue.firstOrNull { it.id == id }?.let { track ->
                    currentTrack = track
                    currentSource = chooseSource(track)
                    durationMs = track.durationSeconds * 1000L
                }
            }
        })
    }

    fun setHuggingFaceToken(token: String) {
        AppSettings.setHuggingFaceToken(context, token)
        applyAuthorizationHeader(token)
        playbackError = null
        currentTrack?.let { track ->
            val wasPlaying = player.playWhenReady
            val position = player.currentPosition.coerceAtLeast(0)
            val source = currentSource ?: chooseSource(track) ?: return@let
            player.setMediaItem(mediaItem(track, source, currentArtistLine, currentCover))
            player.prepare()
            player.seekTo(position)
            player.playWhenReady = wasPlaying
        }
    }

    private fun applyAuthorizationHeader(token: String) {
        val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")
        httpFactory.setDefaultRequestProperties(headers)
    }

    fun play(
        track: Track,
        release: Release?,
        cover: String,
        tracks: List<Track>,
        artistLine: (Track) -> String,
    ) {
        currentRelease = release
        currentCover = cover
        queue = tracks.ifEmpty { listOf(track) }
        val selected = chooseSource(track) ?: run {
            playbackError = "Bu şarkı için oynatılabilir bir ses kaynağı yok."
            return
        }
        val same = currentTrack?.id == track.id
        if (same && player.mediaItemCount > 0) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        playbackError = null
        currentTrack = track
        currentSource = selected
        currentArtistLine = artistLine(track)
        durationMs = track.durationSeconds * 1000L
        val items = queue.mapNotNull { item ->
            val source = chooseSource(item) ?: item.sources.maxByOrNull { qualityRank(it.kind) }
            source?.let { mediaItem(item, it, artistLine(item), cover) }
        }
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(items, index.coerceAtMost((items.size - 1).coerceAtLeast(0)), 0L)
        player.prepare()
        player.playWhenReady = true
    }

    private fun mediaItem(track: Track, source: AudioSource, artistLine: String, cover: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artistLine)
            .setArtworkUri(cover.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(source.url)
            .setMediaMetadata(metadata)
            .build()
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(value: Long) = player.seekTo(value.coerceAtLeast(0L))
    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L)))
    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }
    fun previous() {
        if (player.currentPosition > 4_000) player.seekTo(0) else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    @JvmName("setShufflePlayback")
    fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        player.shuffleModeEnabled = enabled
    }

    fun setQuality(kind: String) {
        preferredQuality = kind
        val track = currentTrack ?: return
        val newSource = chooseSource(track) ?: return
        if (newSource.id == currentSource?.id) return
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition
        currentSource = newSource
        player.setMediaItem(mediaItem(track, newSource, currentArtistLine, currentCover))
        player.prepare()
        player.seekTo(position)
        player.playWhenReady = wasPlaying
    }

    private fun chooseSource(track: Track): AudioSource? {
        val preferredRank = qualityRank(preferredQuality)
        return track.sources.firstOrNull { it.kind == preferredQuality }
            ?: track.sources.filter { qualityRank(it.kind) <= preferredRank }.maxByOrNull { qualityRank(it.kind) }
            ?: track.sources.maxByOrNull { qualityRank(it.kind) }
    }

    fun downloadCurrent() {
        val track = currentTrack ?: return
        val source = currentSource ?: chooseSource(track) ?: return
        if (!source.downloadable) {
            Toast.makeText(context, "Bu sürüm indirilemiyor.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val extension = when {
                source.codec.contains("flac", true) -> "flac"
                source.codec.contains("aac", true) || source.codec.contains("alac", true) -> "m4a"
                else -> "mp3"
            }
            val safeTitle = track.title.replace(Regex("[^a-zA-Z0-9ğüşöçıİĞÜŞÖÇ _-]"), "")
            val request = DownloadManager.Request(Uri.parse(source.downloadUrl.ifBlank { source.url }))
                .setTitle(track.title)
                .setDescription("${source.label} • ${source.codec}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, "$safeTitle-${source.label}.$extension")
                .setAllowedOverMetered(true)
            AppSettings.huggingFaceToken(context).takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Authorization", "Bearer $it")
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "Uygulamaya özel indirme başlatıldı.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "İndirme başlatılamadı: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun release() = player.release()
}
