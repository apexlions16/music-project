package com.apexlions.music

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlayerController(context: Context) {
    private val appContext = context.applicationContext
    val player: ExoPlayer = AuroraPlaybackEngine.player(appContext)

    var currentTrack: Track?
        get() = AuroraPlaybackEngine.currentTrack
        private set(value) { AuroraPlaybackEngine.currentTrack = value }
    var currentRelease: Release?
        get() = AuroraPlaybackEngine.currentRelease
        private set(value) { AuroraPlaybackEngine.currentRelease = value }
    var currentCover: String
        get() = AuroraPlaybackEngine.currentCover
        private set(value) { AuroraPlaybackEngine.currentCover = value }
    var currentSource: AudioSource?
        get() = AuroraPlaybackEngine.currentSource
        private set(value) { AuroraPlaybackEngine.currentSource = value }
    var currentArtistLine: String
        get() = AuroraPlaybackEngine.currentArtistLine
        private set(value) { AuroraPlaybackEngine.currentArtistLine = value }
    var queue: List<Track>
        get() = AuroraPlaybackEngine.queue
        private set(value) { AuroraPlaybackEngine.queue = value }
    var isPlaying: Boolean
        get() = AuroraPlaybackEngine.isPlaying
        private set(value) { AuroraPlaybackEngine.isPlaying = value }
    var positionMs: Long
        get() = AuroraPlaybackEngine.positionMs
        set(value) { AuroraPlaybackEngine.positionMs = value }
    var durationMs: Long
        get() = AuroraPlaybackEngine.durationMs
        set(value) { AuroraPlaybackEngine.durationMs = value }
    var preferredQuality: String
        get() = AuroraPlaybackEngine.preferredQuality
        private set(value) { AuroraPlaybackEngine.preferredQuality = value }
    var shuffle: Boolean
        get() = AuroraPlaybackEngine.shuffle
        private set(value) { AuroraPlaybackEngine.shuffle = value }
    var playbackError: String?
        get() = AuroraPlaybackEngine.playbackError
        private set(value) { AuroraPlaybackEngine.playbackError = value }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) {
            isPlaying = value
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val reported = player.duration.takeIf { it > 0 }
                ?: currentTrack?.durationSeconds?.times(1000L)
                ?: 0L
            durationMs = reported
            if (playbackState == Player.STATE_READY) playbackError = null
            if (playbackState == Player.STATE_ENDED) isPlaying = false
        }

        override fun onPlayerError(error: PlaybackException) {
            isPlaying = false
            playbackError = when {
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Hugging Face erişimi reddedildi. Ayarlar bölümünden bu depo için yetkili Read token girin."
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
                currentArtistLine = mediaItem.mediaMetadata.artist?.toString().orEmpty()
                    .ifBlank { currentArtistLine }
                currentCover = mediaItem.mediaMetadata.artworkUri?.toString().orEmpty()
                    .ifBlank { currentCover }
                durationMs = track.durationSeconds * 1000L
            }
        }
    }

    init {
        applyAuthorizationHeader(AppSettings.huggingFaceToken(appContext))
        player.addListener(listener)
        isPlaying = player.isPlaying
        player.currentMediaItem?.mediaId?.let { id ->
            queue.firstOrNull { it.id == id }?.let { currentTrack = it }
        }
    }

    fun setHuggingFaceToken(token: String) {
        AppSettings.setHuggingFaceToken(appContext, token)
        applyAuthorizationHeader(token)
        playbackError = null
        val track = currentTrack ?: return
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.coerceAtLeast(0L)
        rebuildQueue(track.id, position, wasPlaying)
    }

    private fun applyAuthorizationHeader(token: String) {
        AuroraPlaybackEngine.setAuthorizationToken(appContext, token)
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

        val requestedQueue = tracks.ifEmpty { listOf(track) }
        val playableQueue = requestedQueue.filter { chooseSource(it) != null }
        val selected = chooseSource(track) ?: run {
            playbackError = "Bu şarkı için oynatılabilir bir ses kaynağı yok."
            return
        }
        if (playableQueue.none { it.id == track.id }) {
            playbackError = "Seçilen şarkı oynatma kuyruğuna eklenemedi."
            return
        }
        AuroraPlaybackEngine.startService(appContext)
        queue = playableQueue

        val same = currentTrack?.id == track.id
        if (same && player.mediaItemCount > 0) {
            runCatching {
                if (player.isPlaying) player.pause() else player.play()
            }.onFailure(::recordPlaybackFailure)
            return
        }

        playbackError = null
        currentTrack = track
        currentSource = selected
        currentArtistLine = artistLine(track)
        durationMs = track.durationSeconds * 1000L

        val items = playlistItems(artistLine, cover, release?.title.orEmpty())
        val index = items.indexOfFirst { it.mediaId == track.id }.coerceAtLeast(0)
        runCatching {
            player.setMediaItems(items, index, 0L)
            player.shuffleModeEnabled = shuffle
            player.prepare()
            player.playWhenReady = true
        }.onFailure(::recordPlaybackFailure)
    }

    private fun playlistItems(
        artistLine: (Track) -> String,
        cover: String,
        albumTitle: String,
    ): List<MediaItem> = queue.mapNotNull { item ->
        chooseSource(item)?.let { source ->
            mediaItem(item, source, artistLine(item), cover, albumTitle)
        }
    }

    private fun mediaItem(
        track: Track,
        source: AudioSource,
        artistLine: String,
        cover: String,
        albumTitle: String,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artistLine)
            .setAlbumTitle(albumTitle.takeIf(String::isNotBlank))
            .setArtworkUri(cover.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(source.url)
            .setMediaMetadata(metadata)
            .build()
    }

    fun toggle() {
        runCatching {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.mediaItemCount == 0) return
                AuroraPlaybackEngine.startService(appContext)
                player.play()
            }
        }.onFailure(::recordPlaybackFailure)
    }

    private fun recordPlaybackFailure(error: Throwable) {
        isPlaying = false
        playbackError = "Oynatıcı işlemi güvenli biçimde durduruldu: ${error.message ?: error.javaClass.simpleName}"
    }

    fun seekTo(value: Long) = player.seekTo(value.coerceAtLeast(0L))

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target)
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        if (player.currentPosition > 4_000L) player.seekTo(0L)
        else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
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
        val position = player.currentPosition.coerceAtLeast(0L)
        currentSource = newSource
        rebuildQueue(track.id, position, wasPlaying)
    }

    private fun rebuildQueue(currentTrackId: String, position: Long, playWhenReady: Boolean) {
        if (queue.isEmpty()) return
        AuroraPlaybackEngine.startService(appContext)
        val items = queue.mapNotNull { item ->
            chooseSource(item)?.let { source ->
                mediaItem(
                    item,
                    source,
                    if (item.id == currentTrackId) currentArtistLine else currentArtistLine,
                    currentCover,
                    currentRelease?.title.orEmpty(),
                )
            }
        }
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.mediaId == currentTrackId }.coerceAtLeast(0)
        runCatching {
            player.setMediaItems(items, index, position)
            player.shuffleModeEnabled = shuffle
            player.prepare()
            player.playWhenReady = playWhenReady
        }.onFailure(::recordPlaybackFailure)
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
            Toast.makeText(appContext, "Bu sürüm indirilemiyor.", Toast.LENGTH_SHORT).show()
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
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_MUSIC, "$safeTitle-${source.label}.$extension")
                .setAllowedOverMetered(true)
            AppSettings.huggingFaceToken(appContext).takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Authorization", "Bearer $it")
            }
            val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(appContext, "Uygulamaya özel indirme başlatıldı.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(appContext, "İndirme başlatılamadı: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Activity kapanınca yalnız UI dinleyicisi ayrılır; arka plan oynatıcısı durdurulmaz. */
    fun release() {
        player.removeListener(listener)
    }
}
