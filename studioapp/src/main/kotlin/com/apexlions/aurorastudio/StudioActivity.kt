package com.apexlions.aurorastudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal val StudioBackground = Color(0xFF09090B)
internal val StudioSurface = Color(0xFF18181C)
internal val StudioAccent = Color(0xFF9C5CFF)
internal val StudioMuted = Color(0xFFA8A8B1)
internal val StudioError = Color(0xFFFF6B7D)

class StudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StudioTheme { StudioApp() } }
    }
}

@Composable
private fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = StudioAccent,
            background = StudioBackground,
            surface = StudioSurface,
            onBackground = Color.White,
            onSurface = Color.White,
            error = StudioError,
        ),
        content = content,
    )
}

internal enum class StudioScreen(val label: String) {
    RELEASE("Yeni Yayın"),
    CATALOG("Katalog"),
    SETTINGS("Ayarlar"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(StudioScreen.RELEASE) }
    var config by remember { mutableStateOf(SecureSettings.load(context)) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ayarları kaydedip kataloğu yükleyin.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var lastError by remember { mutableStateOf<String?>(null) }

    var releaseTitle by remember { mutableStateOf("") }
    var releaseType by remember { mutableStateOf("single") }
    var releaseDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var mainArtist by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("Bağımsız") }
    var copyright by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var featured by remember { mutableStateOf(false) }
    var coverUrl by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf("") }
    var coverAsset by remember { mutableStateOf<AssetDraft?>(null) }
    var videoAsset by remember { mutableStateOf<AssetDraft?>(null) }
    val tracks = remember { mutableStateListOf<TrackDraft>() }

    fun persist(uri: android.net.Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persist(it)
            coverAsset = AssetDraft(it, contentDisplayName(context.contentResolver, it), contentSize(context.contentResolver, it))
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persist(it)
            videoAsset = AssetDraft(it, contentDisplayName(context.contentResolver, it), contentSize(context.contentResolver, it))
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            persist(uri)
            val name = contentDisplayName(context.contentResolver, uri)
            tracks += TrackDraft(
                uri = uri,
                displayName = name,
                size = contentSize(context.contentResolver, uri),
                title = name.substringBeforeLast('.'),
                primaryArtist = mainArtist,
            )
        }
    }

    fun loadCatalog() {
        if (busy) return
        SecureSettings.save(context, config)
        busy = true
        lastError = null
        progress = .08f
        status = "GitHub kataloğu yükleniyor…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { MobilePublisher(context, config).loadCatalog() }
            }.onSuccess {
                snapshot = it
                progress = 1f
                status = "Katalog hazır: ${it.json.optJSONArray("tracks")?.length() ?: 0} şarkı"
            }.onFailure {
                lastError = it.message ?: it.toString()
                status = "Katalog yüklenemedi"
                progress = 0f
            }
            busy = false
        }
    }

    fun publish() {
        val current = snapshot
        if (current == null) {
            lastError = "Önce GitHub kataloğunu yükleyin."
            return
        }
        if (busy) return
        SecureSettings.save(context, config)
        busy = true
        lastError = null
        progress = 0f
        status = "Yayın hazırlanıyor…"
        val request = PublishRequest(
            releaseTitle = releaseTitle,
            releaseType = releaseType,
            releaseDate = releaseDate,
            mainArtist = mainArtist,
            label = label,
            copyright = copyright,
            description = description,
            featured = featured,
            coverUrl = coverUrl,
            coverAsset = coverAsset,
            videoUrl = videoUrl,
            videoAsset = videoAsset,
            tracks = tracks.toList(),
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val publisher = MobilePublisher(context, config)
                    val result = publisher.publish(current, request) { text, value ->
                        scope.launch {
                            status = text
                            progress = value.coerceIn(0f, 1f)
                        }
                    }
                    result to publisher.loadCatalog()
                }
            }.onSuccess { (result, refreshed) ->
                snapshot = refreshed
                progress = 1f
                status = "Tamamlandı: ${result.newTracks} yeni, ${result.reusedTracks} ISRC'den kullanılan şarkı"
                releaseTitle = ""
                coverUrl = ""
                videoUrl = ""
                coverAsset = null
                videoAsset = null
                tracks.clear()
            }.onFailure {
                lastError = it.message ?: it.toString()
                status = "Yayın başarısız"
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = StudioBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aurora Studio Mobile", fontWeight = FontWeight.Bold)
                        Text("v0.1.0 • ${screen.label}", color = StudioMuted, fontSize = 11.sp)
                    }
                },
                actions = {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                    IconButton(onClick = ::loadCatalog, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Kataloğu yenile") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = StudioSurface) {
                StudioScreen.entries.forEach { item ->
                    val icon = when (item) {
                        StudioScreen.RELEASE -> Icons.Rounded.CloudUpload
                        StudioScreen.CATALOG -> Icons.Rounded.LibraryMusic
                        StudioScreen.SETTINGS -> Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Icon(icon, null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in .001f..0.999f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            StatusArea(status, lastError)
            when (screen) {
                StudioScreen.RELEASE -> ReleaseEditor(
                    snapshotLoaded = snapshot != null,
                    releaseTitle = releaseTitle,
                    onReleaseTitle = { releaseTitle = it },
                    releaseType = releaseType,
                    onReleaseType = { releaseType = it },
                    releaseDate = releaseDate,
                    onReleaseDate = { releaseDate = it },
                    mainArtist = mainArtist,
                    onMainArtist = { mainArtist = it },
                    label = label,
                    onLabel = { label = it },
                    copyright = copyright,
                    onCopyright = { copyright = it },
                    description = description,
                    onDescription = { description = it },
                    featured = featured,
                    onFeatured = { featured = it },
                    coverUrl = coverUrl,
                    onCoverUrl = { coverUrl = it },
                    videoUrl = videoUrl,
                    onVideoUrl = { videoUrl = it },
                    coverAsset = coverAsset,
                    videoAsset = videoAsset,
                    tracks = tracks,
                    busy = busy,
                    pickCover = { coverPicker.launch(arrayOf("image/*")) },
                    clearCover = { coverAsset = null },
                    pickVideo = { videoPicker.launch(arrayOf("video/*")) },
                    clearVideo = { videoAsset = null },
                    pickAudio = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    updateTrack = { index, value -> tracks[index] = value },
                    removeTrack = { tracks.removeAt(it) },
                    moveTrack = { index, delta ->
                        val target = index + delta
                        if (target in tracks.indices) {
                            val item = tracks.removeAt(index)
                            tracks.add(target, item)
                        }
                    },
                    publish = ::publish,
                    loadCatalog = ::loadCatalog,
                )
                StudioScreen.CATALOG -> CatalogScreen(snapshot, busy, ::loadCatalog)
                StudioScreen.SETTINGS -> SettingsScreen(config, { config = it }, busy) {
                    SecureSettings.save(context, config)
                    status = "Ayarlar cihazda şifreli olarak kaydedildi."
                    lastError = null
                }
            }
        }
    }
}
