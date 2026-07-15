package com.apexlions.aurorastudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LibraryBackground = Color(0xFF08090D)
private val LibrarySurface = Color(0xFF171922)
private val LibraryAccent = Color(0xFF9C5CFF)
private val LibraryMuted = Color(0xFFA7A8B3)
private val LibraryError = Color(0xFFFF7084)

class StudioLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = LibraryAccent,
                    background = LibraryBackground,
                    surface = LibrarySurface,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    error = LibraryError,
                ),
            ) {
                StudioLibraryApp(
                    back = ::finish,
                    openAudioCompletion = { startActivity(Intent(this, StudioAudioCompletionActivity::class.java)) },
                )
            }
        }
    }
}

private sealed interface LibraryDeleteAction {
    data class RemoveTrack(val releaseId: String, val trackId: String, val title: String) : LibraryDeleteAction
    data class DeleteTrack(val trackId: String, val title: String) : LibraryDeleteAction
    data class DeleteRelease(val releaseId: String, val title: String, val deleteOrphans: Boolean) : LibraryDeleteAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioLibraryApp(back: () -> Unit, openAudioCompletion: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { SecureSettings.load(context) }
    val manager = remember(config) { StudioLibraryManager(context, config) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var releases by remember { mutableStateOf<List<LibraryRelease>>(emptyList()) }
    var selectedReleaseId by remember { mutableStateOf("") }
    var selectedTrackId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Yayın kütüphanesi yükleniyor…") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteAction by remember { mutableStateOf<LibraryDeleteAction?>(null) }
    var search by remember { mutableStateOf("") }

    fun refreshState(newSnapshot: CatalogSnapshot) {
        snapshot = newSnapshot
        releases = manager.parse(newSnapshot)
        if (selectedReleaseId !in releases.map(LibraryRelease::id)) selectedReleaseId = releases.firstOrNull()?.id.orEmpty()
        val selectedRelease = releases.firstOrNull { it.id == selectedReleaseId }
        if (selectedTrackId !in selectedRelease?.tracks.orEmpty().map(LibraryTrack::id)) selectedTrackId = ""
    }

    fun reload() {
        if (busy) return
        busy = true
        error = null
        status = "GitHub kataloğu yükleniyor…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.load() } }
                .onSuccess {
                    refreshState(it)
                    status = "${releases.size} yayın tek kütüphanede hazır."
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "Kütüphane yüklenemedi"
                }
            busy = false
        }
    }

    fun perform(label: String, action: (CatalogSnapshot) -> CatalogSnapshot) {
        val current = snapshot ?: return
        if (busy) return
        busy = true
        error = null
        status = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action(current) } }
                .onSuccess {
                    refreshState(it)
                    status = "$label tamamlandı."
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "$label başarısız"
                }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val normalizedSearch = search.trim()
    val visibleReleases = if (normalizedSearch.isBlank()) releases else releases.filter { release ->
        release.title.contains(normalizedSearch, true) || release.tracks.any { track ->
            track.title.contains(normalizedSearch, true) ||
                track.isrc.contains(normalizedSearch, true) ||
                track.primaryArtist.contains(normalizedSearch, true) ||
                track.featuredArtists.contains(normalizedSearch, true)
        }
    }
    val selectedRelease = releases.firstOrNull { it.id == selectedReleaseId }
    val selectedTrack = selectedRelease?.tracks?.firstOrNull { it.id == selectedTrackId }
    val visibleTracks = selectedRelease?.tracks.orEmpty().filter { track ->
        normalizedSearch.isBlank() ||
            track.title.contains(normalizedSearch, true) ||
            track.isrc.contains(normalizedSearch, true) ||
            track.primaryArtist.contains(normalizedSearch, true) ||
            track.featuredArtists.contains(normalizedSearch, true)
    }

    Scaffold(
        containerColor = LibraryBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Yayın Kütüphanesi", fontWeight = FontWeight.Bold)
                        Text("Albüm, single ve şarkı yönetimi tek yerde", color = LibraryMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "Geri") } },
                actions = {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                    IconButton(onClick = ::reload, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Yenile") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(status, color = if (error == null) LibraryMuted else LibraryError, fontSize = 12.sp)
                error?.let { Text(it, color = LibraryError, fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Şarkı, ISRC, sanatçı veya yayın ara") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                LibraryCard("Yayın Seç") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleReleases, key = LibraryRelease::id) { release ->
                            FilterChip(
                                selected = selectedReleaseId == release.id,
                                onClick = { selectedReleaseId = release.id; selectedTrackId = "" },
                                label = { Text(release.title) },
                            )
                        }
                    }
                    if (visibleReleases.isEmpty()) Text("Aramayla eşleşen yayın veya şarkı bulunamadı.", color = LibraryMuted)
                }
            }
            selectedRelease?.let { release ->
                item {
                    ReleaseEditor(
                        release = release,
                        busy = busy,
                        onSave = { value -> perform("Yayın güncelleniyor") { manager.saveRelease(it, value) } },
                        onDeleteKeep = { deleteAction = LibraryDeleteAction.DeleteRelease(release.id, release.title, false) },
                        onDeleteAll = { deleteAction = LibraryDeleteAction.DeleteRelease(release.id, release.title, true) },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Şarkılar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Bir şarkıya dokunarak düzenleme panelini açın.", color = LibraryMuted, fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = openAudioCompletion) {
                            Icon(Icons.Rounded.UploadFile, null)
                            Text(" Yakında Ses Ekle")
                        }
                    }
                }
                itemsIndexed(visibleTracks, key = { _, track -> track.id }) { index, track ->
                    TrackSummaryCard(
                        number = index + 1,
                        track = track,
                        selected = selectedTrackId == track.id,
                        onClick = { selectedTrackId = if (selectedTrackId == track.id) "" else track.id },
                        onRemove = { deleteAction = LibraryDeleteAction.RemoveTrack(release.id, track.id, track.title) },
                        onDelete = { deleteAction = LibraryDeleteAction.DeleteTrack(track.id, track.title) },
                    )
                    if (selectedTrack?.id == track.id) {
                        Spacer(Modifier.height(8.dp))
                        TrackEditor(
                            track = track,
                            busy = busy,
                            onSave = { value -> perform("Şarkı güncelleniyor") { manager.saveTrack(it, value) } },
                            openAudioCompletion = openAudioCompletion,
                        )
                    }
                }
            }
        }
    }

    deleteAction?.let { action ->
        val title = when (action) {
            is LibraryDeleteAction.RemoveTrack -> "Şarkı yalnız bu yayından çıkarılsın mı?"
            is LibraryDeleteAction.DeleteTrack -> "Şarkı tamamen silinsin mi?"
            is LibraryDeleteAction.DeleteRelease -> if (action.deleteOrphans) "Yayın ve kullanılmayan şarkıları sil?" else "Yayını sil, şarkıları koru?"
        }
        val description = when (action) {
            is LibraryDeleteAction.RemoveTrack -> "${action.title} katalogda kalır; yalnız seçili albüm/single içinden çıkarılır."
            is LibraryDeleteAction.DeleteTrack -> "${action.title} bütün yayınlardan, listelerden ve katalogdan kaldırılır. Hugging Face geçmiş dosyaları otomatik silinmez."
            is LibraryDeleteAction.DeleteRelease -> if (action.deleteOrphans) {
                "${action.title} silinir. Başka yayında kullanılmayan şarkılar da katalogdan kaldırılır."
            } else {
                "${action.title} silinir; içindeki şarkı kayıtları katalogda korunur."
            }
        }
        AlertDialog(
            onDismissRequest = { deleteAction = null },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        is LibraryDeleteAction.RemoveTrack -> perform("Şarkı yayından çıkarılıyor") { manager.removeTrackFromRelease(it, action.releaseId, action.trackId) }
                        is LibraryDeleteAction.DeleteTrack -> perform("Şarkı tamamen siliniyor") { manager.deleteTrackCompletely(it, action.trackId) }
                        is LibraryDeleteAction.DeleteRelease -> perform("Yayın siliniyor") { manager.deleteRelease(it, action.releaseId, action.deleteOrphans) }
                    }
                    deleteAction = null
                }) { Text("Onayla", color = LibraryError) }
            },
            dismissButton = { TextButton(onClick = { deleteAction = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun ReleaseEditor(
    release: LibraryRelease,
    busy: Boolean,
    onSave: (LibraryRelease) -> Unit,
    onDeleteKeep: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var title by remember(release.id, release.title) { mutableStateOf(release.title) }
    var type by remember(release.id, release.type) { mutableStateOf(release.type) }
    var date by remember(release.id, release.releaseDate) { mutableStateOf(release.releaseDate) }
    var cover by remember(release.id, release.cover) { mutableStateOf(release.cover) }
    var animated by remember(release.id, release.animatedCoverUrl) { mutableStateOf(release.animatedCoverUrl) }
    var label by remember(release.id, release.label) { mutableStateOf(release.label) }
    var copyright by remember(release.id, release.copyright) { mutableStateOf(release.copyright) }
    var description by remember(release.id, release.description) { mutableStateOf(release.description) }

    LibraryCard("${release.title} • ${statusLabel(release.status)}") {
        OutlinedTextField(title, { title = it }, label = { Text("Yayın adı") }, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("single" to "Single", "maxi_single" to "Maxi Single", "ep" to "EP", "album" to "Albüm")) { option ->
                FilterChip(selected = type == option.first, onClick = { type = option.first }, label = { Text(option.second) })
            }
        }
        OutlinedTextField(date, { date = it }, label = { Text("Yayın tarihi YYYY-AA-GG") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cover, { cover = it }, label = { Text("Normal kapak URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(animated, { animated = it }, label = { Text("Hareketli kapak URL • isteğe bağlı") }, modifier = Modifier.fillMaxWidth())
        Text("Hareketli URL boşsa normal kapak gösterilir.", color = LibraryMuted, fontSize = 10.sp)
        OutlinedTextField(label, { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(copyright, { copyright = it }, label = { Text("Telif") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("Açıklama") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { onSave(release.copy(title = title, type = type, releaseDate = date, cover = cover, animatedCoverUrl = animated, label = label, copyright = copyright, description = description)) },
            enabled = title.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Save, null)
            Text(" Yayını Güncelle")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDeleteKeep, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Delete, null, tint = LibraryError)
                Text(" Yayını Sil\nŞarkıları Koru", color = LibraryError, fontSize = 10.sp)
            }
            OutlinedButton(onClick = onDeleteAll, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Delete, null, tint = LibraryError)
                Text(" Yayın + Yetim\nŞarkıları Sil", color = LibraryError, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TrackSummaryCard(
    number: Int,
    track: LibraryTrack,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) LibraryAccent.copy(alpha = .18f) else LibrarySurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString(), color = LibraryAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.Bold)
                    Text(if (track.playable) "Yayında" else "Yakında • ses bekliyor", color = if (track.playable) LibraryMuted else LibraryAccent, fontSize = 11.sp)
                }
                IconButton(onClick = onClick) { Icon(Icons.Rounded.Edit, "Düzenle") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Yalnız Albümden Çıkar", fontSize = 10.sp) }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Tamamen Sil", color = LibraryError, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun TrackEditor(
    track: LibraryTrack,
    busy: Boolean,
    onSave: (LibraryTrack) -> Unit,
    openAudioCompletion: () -> Unit,
) {
    var title by remember(track.id, track.title) { mutableStateOf(track.title) }
    var isrc by remember(track.id, track.isrc) { mutableStateOf(track.isrc) }
    var primary by remember(track.id, track.primaryArtist) { mutableStateOf(track.primaryArtist) }
    var featured by remember(track.id, track.featuredArtists) { mutableStateOf(track.featuredArtists) }
    var explicit by remember(track.id, track.explicit) { mutableStateOf(track.explicit) }
    var lyrics by remember(track.id, track.lyrics) { mutableStateOf(track.lyrics) }
    var synced by remember(track.id, track.syncedLyrics) { mutableStateOf(track.syncedLyrics) }
    var credits by remember(track.id, track.creditsText) { mutableStateOf(track.creditsText) }
    var lrcMessage by remember(track.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("LRC dosyası okunamadı.")
                StudioLrcSupport.normalize(raw)
            }.onSuccess {
                synced = it
                lrcMessage = "LRC doğrulandı: ${it.lineSequence().count()} zamanlı satır"
            }.onFailure { lrcMessage = it.message ?: "LRC dosyası geçersiz." }
        }
    }

    LibraryCard("Şarkıyı Düzenle") {
        OutlinedTextField(title, { title = it }, label = { Text("Şarkı adı") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(isrc, { isrc = it }, label = { Text("ISRC") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(primary, { primary = it }, label = { Text("Ana sanatçı") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(featured, { featured = it }, label = { Text("Feat sanatçılar") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Explicit", modifier = Modifier.weight(1f))
            Switch(explicit, { explicit = it })
        }
        OutlinedTextField(lyrics, { lyrics = it }, label = { Text("Düz şarkı sözleri") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(synced, { synced = it }, label = { Text("Senkronize LRC") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { lrcPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.UploadFile, null)
            Text(" LRC Dosyası Seç ve Doğrula")
        }
        lrcMessage?.let { Text(it, color = if (it.startsWith("LRC doğrulandı")) LibraryMuted else LibraryError, fontSize = 11.sp) }
        OutlinedTextField(credits, { credits = it }, label = { Text("Künye • Rol: İsimler") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (!track.playable) {
            OutlinedButton(onClick = openAudioCompletion, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.UploadFile, null)
                Text(" Bu Yakında Şarkıya Ses Ekle")
            }
        }
        Button(
            onClick = {
                val normalized = if (synced.isBlank()) "" else runCatching { StudioLrcSupport.normalize(synced) }.getOrElse {
                    lrcMessage = it.message ?: "Senkronize LRC geçersiz."
                    ""
                }
                if (synced.isBlank() || normalized.isNotBlank()) {
                    onSave(track.copy(title = title, isrc = isrc, primaryArtist = primary, featuredArtists = featured, explicit = explicit, lyrics = lyrics, syncedLyrics = normalized, creditsText = credits))
                }
            },
            enabled = title.isNotBlank() && primary.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Save, null)
            Text(" Şarkıyı Güncelle")
        }
    }
}

@Composable
private fun LibraryCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = LibrarySurface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LibraryMusic, null, tint = LibraryAccent, modifier = Modifier.padding(end = 8.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            content()
        }
    }
}

private fun statusLabel(value: String): String = when (value) {
    "published" -> "Yayında"
    "partial" -> "Kısmen Yayında"
    else -> "Yakında"
}
