package com.apexlions.aurorastudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class StudioV2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StudioV2Theme { StudioV2App() } }
    }
}

@Composable
private fun StudioV2Theme(content: @Composable () -> Unit) {
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

private enum class V2Screen(val title: String) {
    RELEASE("Yeni Yayın"),
    CATALOG("Katalog ve Düzenle"),
    SETTINGS("Ayarlar"),
}

private sealed interface AudioTarget {
    data class NewTrack(val index: Int) : AudioTarget
    data object ExistingTrack : AudioTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioV2App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(V2Screen.RELEASE) }
    var config by remember { mutableStateOf(SecureSettings.load(context)) }
    var providerConfig by remember { mutableStateOf(ProviderSettings.load(context)) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ayarlar korunuyor. Kataloğu yükleyerek başlayın.") }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    var metadataQuery by remember { mutableStateOf("") }
    var releaseTitle by remember { mutableStateOf("") }
    var releaseType by remember { mutableStateOf("album") }
    var releaseDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var mainArtist by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var copyright by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var coverUrl by remember { mutableStateOf("") }
    var coverAsset by remember { mutableStateOf<AssetDraft?>(null) }
    var animatedCoverUrl by remember { mutableStateOf("") }
    var featured by remember { mutableStateOf(false) }
    var metadataSource by remember { mutableStateOf("manual") }
    var metadataSourceId by remember { mutableStateOf("") }
    val releaseTracks = remember { mutableStateListOf<V2TrackDraft>() }

    var selectedEdit by remember { mutableStateOf<ExistingTrackDraft?>(null) }
    var editAudio by remember { mutableStateOf<AssetDraft?>(null) }
    var audioTarget by remember { mutableStateOf<AudioTarget?>(null) }

    fun persist(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun asset(uri: Uri): AssetDraft {
        persist(uri)
        return AssetDraft(uri, contentDisplayName(context.contentResolver, uri), contentSize(context.contentResolver, uri))
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { coverAsset = asset(it) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val picked = asset(uri)
        when (val target = audioTarget) {
            is AudioTarget.NewTrack -> if (target.index in releaseTracks.indices) {
                releaseTracks[target.index] = releaseTracks[target.index].copy(audio = picked)
            }
            AudioTarget.ExistingTrack -> editAudio = picked
            null -> Unit
        }
        audioTarget = null
    }

    fun loadCatalog() {
        if (busy) return
        SecureSettings.save(context, config)
        ProviderSettings.save(context, providerConfig)
        busy = true
        error = null
        progress = .08f
        status = "GitHub kataloğu yükleniyor…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CatalogV2Manager(context, config).loadCatalog() } }
                .onSuccess {
                    snapshot = it
                    status = "Katalog hazır: ${it.json.optJSONArray("tracks")?.length() ?: 0} şarkı"
                    progress = 1f
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "Katalog yüklenemedi"
                    progress = 0f
                }
            busy = false
        }
    }

    fun importMetadata() {
        if (busy) return
        busy = true
        error = null
        progress = .05f
        status = "Metadata aranıyor…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UnifiedMetadataClient(providerConfig).importRelease(metadataQuery, includeLyrics = true) { text ->
                        scope.launch { status = text }
                    }
                }
            }.onSuccess { imported ->
                releaseTitle = imported.title
                releaseType = imported.type
                releaseDate = imported.releaseDate.ifBlank { releaseDate }
                mainArtist = imported.mainArtist
                label = imported.label
                copyright = imported.copyright
                coverUrl = imported.coverUrl
                coverAsset = null
                metadataSource = imported.source
                metadataSourceId = imported.sourceId
                releaseTracks.clear()
                releaseTracks.addAll(imported.tracks)
                status = "Metadata hazır: ${imported.tracks.size} parça. Ses dosyalarını istediğiniz zaman ekleyebilirsiniz."
                progress = 1f
            }.onFailure {
                error = it.message ?: it.toString()
                status = "Metadata alınamadı"
                progress = 0f
            }
            busy = false
        }
    }

    fun fetchCover() {
        if (busy || coverUrl.isBlank()) return
        busy = true
        error = null
        status = "Kapak indiriliyor…"
        progress = .15f
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CatalogV2Manager(context, config).fetchRemoteCover(coverUrl) } }
                .onSuccess {
                    coverAsset = it
                    status = "Kapak indirildi; yayın sırasında Hugging Face'e kalıcı olarak yüklenecek."
                    progress = 1f
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "Kapak indirilemedi"
                    progress = 0f
                }
            busy = false
        }
    }

    fun publishRelease() {
        val current = snapshot ?: run {
            error = "Önce kataloğu yükleyin."
            return
        }
        if (busy) return
        busy = true
        error = null
        progress = 0f
        status = "Yayın hazırlanıyor…"
        val draft = V2ReleaseDraft(
            title = releaseTitle,
            type = releaseType,
            releaseDate = releaseDate,
            mainArtist = mainArtist,
            label = label,
            copyright = copyright,
            description = description,
            coverUrl = coverUrl,
            coverAsset = coverAsset,
            animatedCoverUrl = animatedCoverUrl,
            featured = featured,
            tracks = releaseTracks.toList(),
            metadataSource = metadataSource,
            metadataSourceId = metadataSourceId,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val manager = CatalogV2Manager(context, config)
                    val result = manager.publishRelease(current, draft) { text, value ->
                        scope.launch { status = text; progress = value.coerceIn(0f, 1f) }
                    }
                    result to manager.loadCatalog()
                }
            }.onSuccess { (result, refreshed) ->
                snapshot = refreshed
                status = "Yayın kaydedildi: ${result.newTracks} yeni, ${result.reusedTracks} ISRC ile tekrar kullanılan parça."
                progress = 1f
                releaseTitle = ""
                description = ""
                coverUrl = ""
                coverAsset = null
                animatedCoverUrl = ""
                metadataSource = "manual"
                metadataSourceId = ""
                releaseTracks.clear()
            }.onFailure {
                error = it.message ?: it.toString()
                status = "Yayın başarısız"
                progress = 0f
            }
            busy = false
        }
    }

    fun saveTrackEdit() {
        val current = snapshot ?: return
        val edit = selectedEdit ?: return
        if (busy) return
        busy = true
        error = null
        progress = 0f
        status = "Şarkı güncelleniyor…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val manager = CatalogV2Manager(context, config)
                    manager.updateTrack(current, edit, editAudio) { text, value ->
                        scope.launch { status = text; progress = value.coerceIn(0f, 1f) }
                    }
                    manager.loadCatalog()
                }
            }.onSuccess {
                snapshot = it
                selectedEdit = null
                editAudio = null
                status = "Şarkı düzenlendi. Yeni ses seçildiyse kalite işleme kuyruğa alındı."
                progress = 1f
            }.onFailure {
                error = it.message ?: it.toString()
                status = "Şarkı güncellenemedi"
                progress = 0f
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (config.githubToken.isNotBlank()) loadCatalog()
    }

    Scaffold(
        containerColor = StudioBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aurora Studio Mobile", fontWeight = FontWeight.Bold)
                        Text("v0.2.0 • ${screen.title}", color = StudioMuted, fontSize = 11.sp)
                    }
                },
                actions = {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(10.dp).size(24.dp), strokeWidth = 2.dp)
                    IconButton(onClick = ::loadCatalog, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Kataloğu yenile") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = StudioSurface) {
                V2Screen.entries.forEach { item ->
                    val icon = when (item) {
                        V2Screen.RELEASE -> Icons.Rounded.CloudUpload
                        V2Screen.CATALOG -> Icons.Rounded.LibraryMusic
                        V2Screen.SETTINGS -> Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Icon(icon, null) },
                        label = { Text(item.title, fontSize = 10.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in .001f..0.999f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            V2Status(status, error)
            when (screen) {
                V2Screen.RELEASE -> V2ReleaseScreen(
                    metadataQuery = metadataQuery,
                    onMetadataQuery = { metadataQuery = it },
                    importMetadata = ::importMetadata,
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
                    coverUrl = coverUrl,
                    onCoverUrl = { coverUrl = it },
                    coverAsset = coverAsset,
                    pickCover = { coverPicker.launch(arrayOf("image/*")) },
                    fetchCover = ::fetchCover,
                    animatedCoverUrl = animatedCoverUrl,
                    onAnimatedCoverUrl = { animatedCoverUrl = it },
                    featured = featured,
                    onFeatured = { featured = it },
                    tracks = releaseTracks,
                    addTrack = { releaseTracks += V2TrackDraft(primaryArtist = mainArtist) },
                    updateTrack = { index, value -> releaseTracks[index] = value },
                    removeTrack = { releaseTracks.removeAt(it) },
                    pickTrackAudio = { index -> audioTarget = AudioTarget.NewTrack(index); audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    publish = ::publishRelease,
                    canPublish = snapshot != null && !busy,
                    busy = busy,
                    metadataSource = metadataSource,
                )
                V2Screen.CATALOG -> V2CatalogScreen(
                    snapshot = snapshot,
                    selected = selectedEdit,
                    onSelected = { selectedEdit = it; editAudio = null },
                    editAudio = editAudio,
                    pickAudio = { audioTarget = AudioTarget.ExistingTrack; audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    save = ::saveTrackEdit,
                    reload = ::loadCatalog,
                    busy = busy,
                )
                V2Screen.SETTINGS -> V2SettingsScreen(
                    config = config,
                    onConfig = { config = it },
                    providers = providerConfig,
                    onProviders = { providerConfig = it },
                    busy = busy,
                    save = {
                        SecureSettings.save(context, config)
                        ProviderSettings.save(context, providerConfig)
                        status = "GitHub, Hugging Face ve metadata anahtarları cihazda şifreli kaydedildi."
                        error = null
                    },
                )
            }
        }
    }
}

@Composable
private fun V2Status(status: String, error: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(status, color = if (error == null) StudioMuted else StudioError, fontSize = 12.sp)
        error?.let { Text(it, color = StudioError, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun V2ReleaseScreen(
    metadataQuery: String,
    onMetadataQuery: (String) -> Unit,
    importMetadata: () -> Unit,
    releaseTitle: String,
    onReleaseTitle: (String) -> Unit,
    releaseType: String,
    onReleaseType: (String) -> Unit,
    releaseDate: String,
    onReleaseDate: (String) -> Unit,
    mainArtist: String,
    onMainArtist: (String) -> Unit,
    label: String,
    onLabel: (String) -> Unit,
    copyright: String,
    onCopyright: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    coverUrl: String,
    onCoverUrl: (String) -> Unit,
    coverAsset: AssetDraft?,
    pickCover: () -> Unit,
    fetchCover: () -> Unit,
    animatedCoverUrl: String,
    onAnimatedCoverUrl: (String) -> Unit,
    featured: Boolean,
    onFeatured: (Boolean) -> Unit,
    tracks: List<V2TrackDraft>,
    addTrack: () -> Unit,
    updateTrack: (Int, V2TrackDraft) -> Unit,
    removeTrack: (Int) -> Unit,
    pickTrackAudio: (Int) -> Unit,
    publish: () -> Unit,
    canPublish: Boolean,
    busy: Boolean,
    metadataSource: String,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 42.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            V2Card("Tek dokunuşla metadata") {
                OutlinedTextField(metadataQuery, onMetadataQuery, label = { Text("Albüm / single adı veya Spotify bağlantısı") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = importMetadata, enabled = !busy && metadataQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.size(7.dp))
                    Text("Spotify ile eşleştir, MusicBrainz + CAA + LRCLIB'den doldur")
                }
                Text("Kaydedilen metadata kaynağı: $metadataSource", color = StudioMuted, fontSize = 11.sp)
            }
        }
        item {
            V2Card("Yayın bilgileri") {
                OutlinedTextField(releaseTitle, onReleaseTitle, label = { Text("Yayın adı") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mainArtist, onMainArtist, label = { Text("Ana sanatçı") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("single" to "Single", "ep" to "EP", "album" to "Albüm").forEach { (value, labelText) ->
                        FilterChip(selected = releaseType == value, onClick = { onReleaseType(value) }, label = { Text(labelText) })
                    }
                }
                OutlinedTextField(releaseDate, onReleaseDate, label = { Text("Yayın tarihi") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, onLabel, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(copyright, onCopyright, label = { Text("Telif") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, onDescription, label = { Text("Açıklama") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ana sayfada öne çıkar", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(featured, onFeatured)
                }
            }
        }
        item {
            V2Card("Kapak ve hareketli arka plan") {
                OutlinedTextField(coverUrl, onCoverUrl, label = { Text("Kapak URL'si") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pickCover, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Image, null); Text(" Dosya seç") }
                    OutlinedButton(onClick = fetchCover, enabled = coverUrl.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Text("Görsel Fetch") }
                }
                Text(coverAsset?.let { "Kalıcı yükleme hazır: ${it.displayName} • ${sizeLabel(it.size)}" } ?: "URL geçicidir; Görsel Fetch ile Hugging Face'e kalıcı aktarım hazırlayın.", color = StudioMuted, fontSize = 11.sp)
                OutlinedTextField(animatedCoverUrl, onAnimatedCoverUrl, label = { Text("Hareketli kapak / arka plan video URL'si") }, modifier = Modifier.fillMaxWidth())
                Text("Hareketli medya yalnızca URL olarak tutulur.", color = StudioMuted, fontSize = 11.sp)
            }
        }
        item {
            V2Card("Parçalar") {
                Text("Ses dosyası olmadan yayınlayabilirsiniz. Parça yakında olarak görünür; ses eklenince açılır.", color = StudioMuted, fontSize = 11.sp)
                Button(onClick = addTrack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Text(" Metadata parçası ekle")
                }
            }
        }
        itemsIndexed(tracks, key = { _, item -> item.localId }) { index, track ->
            V2TrackCard(index, track, { updateTrack(index, it) }, { pickTrackAudio(index) }, { removeTrack(index) }, busy)
        }
        item {
            Button(onClick = publish, enabled = canPublish && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CloudUpload, null)
                Text(" Hugging Face'e yükle ve GitHub'a yayınla")
            }
        }
    }
}

@Composable
private fun V2TrackCard(
    index: Int,
    value: V2TrackDraft,
    onValue: (V2TrackDraft) -> Unit,
    pickAudio: () -> Unit,
    remove: () -> Unit,
    busy: Boolean,
) {
    V2Card("${index + 1}. ${value.title.ifBlank { "İsimsiz parça" }}") {
        OutlinedTextField(value.title, { onValue(value.copy(title = it)) }, label = { Text("Şarkı adı") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.primaryArtist, { onValue(value.copy(primaryArtist = it)) }, label = { Text("Ana sanatçı") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.featuredArtists, { onValue(value.copy(featuredArtists = it)) }, label = { Text("Feat isimleri") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.isrc, { onValue(value.copy(isrc = it)) }, label = { Text("ISRC") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.lyrics, { onValue(value.copy(lyrics = it)) }, label = { Text("Düz sözler") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.syncedLyrics, { onValue(value.copy(syncedLyrics = it)) }, label = { Text("Senkronize LRC") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.creditsText, { onValue(value.copy(creditsText = it)) }, label = { Text("Künye — her satır Rol: İsim") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Explicit", modifier = Modifier.weight(1f))
            Switch(value.explicit, { onValue(value.copy(explicit = it)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = pickAudio, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.UploadFile, null)
                Text(if (value.audio == null) " Ses ekle" else " Sesi değiştir")
            }
            TextButton(onClick = remove, enabled = !busy) { Text("Sil", color = StudioError) }
        }
        Text(value.audio?.let { "${it.displayName} • ${sizeLabel(it.size)} • diğer kaliteler otomatik üretilecek" } ?: "Ses yok • uygulamada yakında / kilitli görünür", color = StudioMuted, fontSize = 11.sp)
    }
}

@Composable
private fun V2CatalogScreen(
    snapshot: CatalogSnapshot?,
    selected: ExistingTrackDraft?,
    onSelected: (ExistingTrackDraft?) -> Unit,
    editAudio: AssetDraft?,
    pickAudio: () -> Unit,
    save: () -> Unit,
    reload: () -> Unit,
    busy: Boolean,
) {
    if (snapshot == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = reload, enabled = !busy) { Text("Kataloğu yükle") }
        }
        return
    }
    val manager = remember(snapshot.sha) { CatalogV2ManagerPlaceholder }
    if (selected != null) {
        var value by remember(selected.id) { mutableStateOf(selected) }
        LaunchedEffect(value) { if (value != selected) onSelected(value) }
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 42.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSelected(null) }) { Icon(Icons.Rounded.ArrowBack, "Geri") }
                    Text("Şarkıyı düzenle", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
            item {
                V2Card("Metadata") {
                    OutlinedTextField(value.title, { value = value.copy(title = it) }, label = { Text("Şarkı adı") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.primaryArtist, { value = value.copy(primaryArtist = it) }, label = { Text("Ana sanatçı") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.featuredArtists, { value = value.copy(featuredArtists = it) }, label = { Text("Feat isimleri") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.isrc, { value = value.copy(isrc = it) }, label = { Text("ISRC") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.lyrics, { value = value.copy(lyrics = it) }, label = { Text("Düz sözler") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.syncedLyrics, { value = value.copy(syncedLyrics = it) }, label = { Text("Senkronize LRC") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.creditsText, { value = value.copy(creditsText = it) }, label = { Text("Künye") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Explicit", modifier = Modifier.weight(1f))
                        Switch(value.explicit, { value = value.copy(explicit = it) })
                    }
                }
            }
            item {
                V2Card("Ses ve kalite") {
                    OutlinedButton(onClick = pickAudio, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.UploadFile, null)
                        Text(if (editAudio == null) " Yeni kaynak ses seç" else " Kaynak sesi değiştir")
                    }
                    Text(editAudio?.let { "${it.displayName} • ${sizeLabel(it.size)}" } ?: if (value.playable) "Mevcut ses korunacak" else "Henüz ses yok; seçtiğinizde parça açılacak" , color = StudioMuted, fontSize = 11.sp)
                    Text("Tek kaynak yüklendikten sonra standart, yüksek ve lossless kaliteler GitHub Actions tarafından üretilir.", color = StudioMuted, fontSize = 11.sp)
                }
            }
            item {
                Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Save, null)
                    Text(" Değişiklikleri kaydet")
                }
            }
        }
        return
    }

    val rows = snapshot.json.optJSONArray("tracks") ?: JSONArray()
    val artists = snapshot.json.optJSONArray("artists") ?: JSONArray()
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 42.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            V2Card("Katalog") {
                Text("${rows.length()} şarkı • düzenlemek için karta dokunun")
                Text("Soluk/yakında parçaya ses eklediğinizde yalnız o parça açılır.", color = StudioMuted, fontSize = 11.sp)
            }
        }
        items(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            val playable = row.optBoolean("playable", false) || (row.optJSONArray("sources")?.length() ?: 0) > 0
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                shape = RoundedCornerShape(16.dp),
                onClick = { onSelected(trackDraftWithoutManager(snapshot.json, row, artists)) },
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.optString("title", "İsimsiz"), fontWeight = FontWeight.SemiBold)
                        Text("${row.optString("isrc", "ISRC yok")} • ${if (playable) "Çalınabilir" else "Yakında"} • ${row.optString("qualityState", "hazır")}", color = if (playable) StudioMuted else StudioAccent, fontSize = 11.sp)
                    }
                    Icon(Icons.Rounded.Edit, "Düzenle")
                }
            }
        }
    }
}

private object CatalogV2ManagerPlaceholder

private fun trackDraftWithoutManager(catalog: JSONObject, track: JSONObject, artists: JSONArray): ExistingTrackDraft {
    fun artistName(id: String): String {
        for (i in 0 until artists.length()) {
            val row = artists.optJSONObject(i) ?: continue
            if (row.optString("id") == id) return row.optString("name")
        }
        return ""
    }
    val primaryIds = track.optJSONArray("primaryArtistIds") ?: track.optJSONArray("artistIds") ?: JSONArray()
    val featureNames = buildList {
        val ids = track.optJSONArray("featuredArtistIds") ?: JSONArray()
        for (i in 0 until ids.length()) artistName(ids.optString(i)).takeIf(String::isNotBlank)?.let(::add)
        val free = track.optJSONArray("featuredArtistNames") ?: JSONArray()
        for (i in 0 until free.length()) free.optString(i).takeIf(String::isNotBlank)?.let(::add)
    }
    val playable = track.optBoolean("playable", false) || (track.optJSONArray("sources")?.length() ?: 0) > 0
    return ExistingTrackDraft(
        id = track.optString("id"),
        title = track.optString("title"),
        isrc = track.optString("isrc"),
        primaryArtist = artistName(primaryIds.optString(0)),
        featuredArtists = featureNames.distinct().joinToString(", "),
        explicit = track.optBoolean("explicit"),
        lyrics = track.optString("lyrics"),
        syncedLyrics = track.optString("syncedLyrics"),
        creditsText = creditsToText(track.optJSONArray("credits")),
        playable = playable,
    )
}

@Composable
private fun V2SettingsScreen(
    config: StudioConfig,
    onConfig: (StudioConfig) -> Unit,
    providers: ProviderConfig,
    onProviders: (ProviderConfig) -> Unit,
    busy: Boolean,
    save: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 42.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            V2Card("GitHub kataloğu") {
                OutlinedTextField(config.githubRepo, { onConfig(config.copy(githubRepo = it)) }, label = { Text("owner/repo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(config.githubBranch, { onConfig(config.copy(githubBranch = it)) }, label = { Text("Dal") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(config.catalogPath, { onConfig(config.copy(catalogPath = it)) }, label = { Text("Katalog yolu") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(config.githubToken, { onConfig(config.copy(githubToken = it)) }, label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            V2Card("Hugging Face") {
                OutlinedTextField(config.hfRepo, { onConfig(config.copy(hfRepo = it)) }, label = { Text("Dataset repo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(config.hfToken, { onConfig(config.copy(hfToken = it)) }, label = { Text("Write token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            V2Card("Metadata sağlayıcıları") {
                OutlinedTextField(providers.spotifyClientId, { onProviders(providers.copy(spotifyClientId = it)) }, label = { Text("Spotify Client ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(providers.spotifyClientSecret, { onProviders(providers.copy(spotifyClientSecret = it)) }, label = { Text("Spotify Client Secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(providers.musicBrainzContact, { onProviders(providers.copy(musicBrainzContact = it)) }, label = { Text("MusicBrainz iletişim e-postası veya URL") }, modifier = Modifier.fillMaxWidth())
                Text("Spotify yalnızca doğru albümü eşleştirmeye yardım eder. Kalıcı katalog verisi MusicBrainz, Cover Art Archive ve LRCLIB kaynaklarından oluşturulur.", color = StudioMuted, fontSize = 11.sp)
            }
        }
        item {
            V2Card("Tokenların korunması") {
                Text("Tokenlar EncryptedSharedPreferences içinde saklanır. Aynı package adı ve aynı Android imzasıyla yapılan güncellemede korunur. Uygulamayı kaldırmak veriyi siler.", color = StudioMuted, fontSize = 12.sp)
                Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Save, null)
                    Text(" Şifreli kaydet")
                }
            }
        }
    }
}

@Composable
private fun V2Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = StudioSurface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            content()
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
