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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlayerController(private val context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var currentTrack by mutableStateOf<Track?>(null)
        private set
    var currentRelease by mutableStateOf<Release?>(null)
        private set
    var currentCover by mutableStateOf("")
        private set
    var currentSource by mutableStateOf<AudioSource?>(null)
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

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                queue.firstOrNull { it.id == id }?.let { track ->
                    currentTrack = track
                    currentSource = chooseSource(track)
                }
            }
        })
    }

    fun play(track: Track, release: Release?, cover: String, tracks: List<Track>) {
        currentRelease = release
        currentCover = cover
        queue = tracks.ifEmpty { listOf(track) }
        val selected = chooseSource(track) ?: return
        val same = currentTrack?.id == track.id
        if (same && player.mediaItemCount > 0) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        currentTrack = track
        currentSource = selected
        val items = queue.map { item ->
            val source = chooseSource(item) ?: item.sources.maxByOrNull { qualityRank(it.kind) }
            MediaItem.Builder().setMediaId(item.id).setUri(source?.url.orEmpty()).build()
        }
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.playWhenReady = true
    }

    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun seekTo(value: Long) { player.seekTo(value.coerceAtLeast(0L)) }
    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
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
        val wasPlaying = player.isPlaying
        val position = player.currentPosition
        currentSource = newSource
        player.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(newSource.url).build())
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
            val extension = source.codec.substringBefore(' ').lowercase().let {
                when {
                    it.contains("flac") -> "flac"
                    it.contains("alac") || it.contains("aac") -> "m4a"
                    else -> "mp3"
                }
            }
            val fileName = "${track.title.replace(Regex("[^a-zA-Z0-9ğüşöçıİĞÜŞÖÇ _-]"), "")}-${source.label}.$extension"
            val request = DownloadManager.Request(Uri.parse(source.downloadUrl.ifBlank { source.url }))
                .setTitle(track.title)
                .setDescription("${source.label} • ${source.codec}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "MuzikProjesi/$fileName")
                .setAllowedOverMetered(true)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "İndirme başlatıldı.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "İndirme başlatılamadı: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun release() { player.release() }
}
