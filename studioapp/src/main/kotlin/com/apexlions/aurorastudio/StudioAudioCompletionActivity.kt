package com.apexlions.aurorastudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CompletionBackground = Color(0xFF08090D)
private val CompletionSurface = Color(0xFF171922)
private val CompletionAccent = Color(0xFF9C5CFF)
private val CompletionMuted = Color(0xFFA7A8B3)
private val CompletionError = Color(0xFFFF7084)

class StudioAudioCompletionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CompletionAccent,
                    background = CompletionBackground,
                    surface = CompletionSurface,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    error = CompletionError,
                ),
            ) {
                AudioCompletionApp(back = ::finish)
            }
        }
    }
}

private enum class CompletionMode(val title: String) {
    AUDIO("Ses Dosyaları"),
    LYRICS("Söz Dosyaları"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCompletionApp(back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { SecureSettings.load(context) }
    val manager = remember(config) { AudioCompletionManager(context, config) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var tracks by remember { mutableStateOf<List<PendingTrack>>(emptyList()) }
    val selectedFiles = remember { mutableStateListOf<AssetDraft>() }
    val assignments = remember { mutableStateMapOf<String, Int?>() }
    var mode by remember { mutableStateOf(CompletionMode.AUDIO) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Yakında yayınlar yükleniyor…") }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun asset(uri: Uri): AssetDraft {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return AssetDraft(uri, contentDisplayName(context.contentResolver, uri), contentSize(context.contentResolver, uri))
    }

    fun autoMatch() {
        val result = MediaMatcher.match(tracks.map(PendingTrack::title), selectedFiles.map(AssetDraft::displayName))
        assignments.clear()
        result.forEach { assignments[tracks[it.targetIndex].id] = it.fileIndex }
        status = "Eşleştirme hazır: isim benzerliği kullanıldı, kalanlar albüm sırasına göre dolduruldu."
    }

    fun tracksForMode(value: CatalogSnapshot?): List<PendingTrack> = value?.let {
        if (mode == CompletionMode.AUDIO) manager.pendingTracks(it) else manager.allTracks(it)
    }.orEmpty()

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedFiles.clear()
        selectedFiles.addAll(uris.map(::asset))
        mode = CompletionMode.AUDIO
        autoMatch()
    }
    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedFiles.clear()
        selectedFiles.addAll(uris.map(::asset))
        mode = CompletionMode.LYRICS
        autoMatch()
    }

    fun reload() {
        if (busy) return
        busy = true
        error = null
        status = "GitHub kataloğu yükleniyor…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.load() } }
                .onSuccess {
                    snapshot = it
                    tracks = tracksForMode(it)
                    assignments.clear()
                    selectedFiles.clear()
                    status = if (tracks.isEmpty()) "Ses bekleyen Yakında şarkı yok." else "${tracks.size} şarkı ses dosyası bekliyor."
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "Katalog yüklenemedi"
                }
            busy = false
        }
    }

    fun submit() {
        val current = snapshot ?: return
        val mapped = assignments.mapNotNull { (trackId, fileIndex) ->
            fileIndex?.takeIf { it in selectedFiles.indices }?.let { trackId to selectedFiles[it] }
        }.toMap()
        if (mapped.isEmpty()) {
            error = "En az bir dosyayı bir şarkıyla eşleyin."
            return
        }
        if (mapped.values.map(AssetDraft::uri).distinct().size != mapped.size) {
            error = "Aynı dosya birden fazla şarkıya atanamaz. Panelden eşleştirmeyi düzeltin."
            return
        }
        busy = true
        error = null
        progress = 0f
        scope.launch {
            if (mode == CompletionMode.AUDIO) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        manager.attachAudioBatch(current, mapped) { text, value ->
                            scope.launch { status = text; progress = value.coerceIn(0f, 1f) }
                        }
                    }
                }.onSuccess {
                    snapshot = it
                    tracks = manager.pendingTracks(it)
                    selectedFiles.clear()
                    assignments.clear()
                    status = "Ses dosyaları mevcut şarkılara bağlandı; yeni release oluşturulmadı."
                    progress = 1f
                }.onFailure {
                    error = it.message ?: it.toString()
                    status = "Ses tamamlama başarısız"
                    progress = 0f
                }
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val textAssignments = mapped.mapValues { (_, value) ->
                            val text = context.contentResolver.openInputStream(value.uri)?.bufferedReader()?.use { it.readText() }
                                ?: error("Söz dosyası okunamadı: ${value.displayName}")
                            text to value.displayName.lowercase().endsWith(".lrc")
                        }
                        manager.attachLyricsBatch(current, textAssignments)
                    }
                }.onSuccess {
                    snapshot = it
                    tracks = manager.allTracks(it)
                    selectedFiles.clear()
                    assignments.clear()
                    status = "Söz dosyaları mevcut şarkılara bağlandı ve LRC zamanları doğrulandı."
                    progress = 1f
                }.onFailure {
                    error = it.message ?: it.toString()
                    status = "Söz eşleştirme başarısız"
                    progress = 0f
                }
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CompletionBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ses ve LRC Tamamlama", fontWeight = FontWeight.Bold)
                        Text("Yeni release oluşturmadan mevcut şarkıları aç", color = CompletionMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "Geri") } },
                actions = {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(10.dp).size(23.dp), strokeWidth = 2.dp)
                    IconButton(onClick = ::reload, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Yenile") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in .001f..0.999f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(status, color = if (error == null) CompletionMuted else CompletionError, fontSize = 12.sp)
                error?.let { Text(it, color = CompletionError, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CompletionCard("Dosya türü") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompletionMode.entries.forEach { option ->
                                FilterChip(selected = mode == option, onClick = { mode = option; selectedFiles.clear(); assignments.clear(); tracks = tracksForMode(snapshot) }, label = { Text(option.title) })
                            }
                        }
                        Button(
                            onClick = {
                                if (mode == CompletionMode.AUDIO) audioPicker.launch(arrayOf("audio/*", "application/octet-stream"))
                                else lyricsPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream"))
                            },
                            enabled = tracks.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.UploadFile, null)
                            Text(if (mode == CompletionMode.AUDIO) " Birden Fazla Ses Dosyası Seç" else " Birden Fazla TXT/LRC Seç")
                        }
                        Text("Dosya isimleri otomatik karşılaştırılır. Güvenli olmayan eşleşmeler albüm sırasına bırakılır ve aşağıdaki panelden değiştirilebilir.", color = CompletionMuted, fontSize = 11.sp)
                    }
                }
                if (selectedFiles.isNotEmpty()) {
                    item {
                        OutlinedButton(onClick = ::autoMatch, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.AutoFixHigh, null)
                            Text(" İsimle Eşleştir + Sıra Fallback")
                        }
                    }
                }
                itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                    MatchRow(
                        number = index + 1,
                        track = track,
                        files = selectedFiles,
                        selectedIndex = assignments[track.id],
                        onCycle = {
                            val current = assignments[track.id]
                            assignments[track.id] = when {
                                selectedFiles.isEmpty() -> null
                                current == null -> 0
                                current >= selectedFiles.lastIndex -> null
                                else -> current + 1
                            }
                        },
                    )
                }
                if (tracks.isNotEmpty()) {
                    item {
                        Button(onClick = ::submit, enabled = selectedFiles.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.LibraryMusic, null)
                            Text(if (mode == CompletionMode.AUDIO) " Eşleşen Sesleri Mevcut Şarkılara Yükle" else " Eşleşen Sözleri Kaydet")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(
    number: Int,
    track: PendingTrack,
    files: List<AssetDraft>,
    selectedIndex: Int?,
    onCycle: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CompletionSurface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString(), color = CompletionAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.Bold)
                    Text("${track.releaseTitle} • Disk ${track.disc} / Sıra ${track.position}", color = CompletionMuted, fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            OutlinedButton(onClick = onCycle, enabled = files.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(selectedIndex?.takeIf { it in files.indices }?.let { files[it].displayName } ?: "Dosya eşlenmedi")
            }
            Text("Butona dokundukça seçilen dosyalar arasında geçiş yapar.", color = CompletionMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CompletionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CompletionSurface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            content()
        }
    }
}
