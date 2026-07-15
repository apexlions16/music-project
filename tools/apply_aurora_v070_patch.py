from __future__ import annotations

from pathlib import Path


def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Beklenen metin bulunamadı: {path}\n{old[:240]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Beklenen metin bulunamadı: {path}\n{old[:240]}")
    path.write_text(text.replace(old, new), encoding="utf-8")


# Aurora Music: senkronize söz görünümünü yeni Apple Music tarzı bileşene bağla.
activity = Path("app/src/main/kotlin/com/apexlions/music/AuroraV4Activity.kt")
replace_required(
    activity,
    '''            if (lyrics) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 20.dp)) {
                    item {
                        Text(track.lyrics.ifBlank { "Bu şarkı için söz bulunmuyor." }, fontSize = 25.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                        if (track.credits.isNotEmpty()) {
                            Spacer(Modifier.height(28.dp)); Text("Künye", color = V4Accent, fontWeight = FontWeight.Bold)
                            track.credits.forEach { Text("${it.role}: ${it.names.joinToString(", ")}", color = Color.White.copy(alpha = .72f)) }
                        }
                    }
                }
            } else {''',
    '''            if (lyrics) {
                SyncedLyricsPane(
                    lrcText = track.syncedLyrics,
                    plainLyrics = track.lyrics,
                    positionMs = controller.positionMs,
                    credits = track.credits,
                    onSeek = controller::seekTo,
                    modifier = Modifier.weight(1f),
                )
            } else {''',
)
replace_all_required(activity, 'AuroraMusic/0.6.0', 'AuroraMusic/0.7.0')

# Studio Library: arama ve tek dosyalık LRC seçimi.
library = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioLibraryActivity.kt")
replace_required(
    library,
    '''import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent''',
    '''import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts''',
)
replace_required(
    library,
    'import androidx.compose.ui.Modifier\n',
    'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\n',
)
replace_required(
    library,
    '    var deleteAction by remember { mutableStateOf<LibraryDeleteAction?>(null) }\n',
    '    var deleteAction by remember { mutableStateOf<LibraryDeleteAction?>(null) }\n    var search by remember { mutableStateOf("") }\n',
)
replace_required(
    library,
    '''    val selectedRelease = releases.firstOrNull { it.id == selectedReleaseId }
    val selectedTrack = selectedRelease?.tracks?.firstOrNull { it.id == selectedTrackId }
''',
    '''    val normalizedSearch = search.trim()
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
''',
)
replace_required(
    library,
    '''            item {
                LibraryCard("Yayın Seç") {''',
    '''            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Şarkı, ISRC, sanatçı veya yayın ara") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                LibraryCard("Yayın Seç") {''',
)
replace_required(library, 'items(releases, key = LibraryRelease::id)', 'items(visibleReleases, key = LibraryRelease::id)')
replace_required(library, 'if (releases.isEmpty()) Text("Katalogda yayın bulunmuyor.", color = LibraryMuted)', 'if (visibleReleases.isEmpty()) Text("Aramayla eşleşen yayın veya şarkı bulunamadı.", color = LibraryMuted)')
replace_required(library, 'itemsIndexed(release.tracks, key = { _, track -> track.id })', 'itemsIndexed(visibleTracks, key = { _, track -> track.id })')
replace_required(
    library,
    '''    var synced by remember(track.id, track.syncedLyrics) { mutableStateOf(track.syncedLyrics) }
    var credits by remember(track.id, track.creditsText) { mutableStateOf(track.creditsText) }

    LibraryCard("Şarkıyı Düzenle") {''',
    '''    var synced by remember(track.id, track.syncedLyrics) { mutableStateOf(track.syncedLyrics) }
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

    LibraryCard("Şarkıyı Düzenle") {''',
)
replace_required(
    library,
    '''        OutlinedTextField(synced, { synced = it }, label = { Text("Senkronize LRC") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(credits, { credits = it }, label = { Text("Künye • Rol: İsimler") }, minLines = 2, modifier = Modifier.fillMaxWidth())''',
    '''        OutlinedTextField(synced, { synced = it }, label = { Text("Senkronize LRC") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { lrcPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.UploadFile, null)
            Text(" LRC Dosyası Seç ve Doğrula")
        }
        lrcMessage?.let { Text(it, color = if (it.startsWith("LRC doğrulandı")) LibraryMuted else LibraryError, fontSize = 11.sp) }
        OutlinedTextField(credits, { credits = it }, label = { Text("Künye • Rol: İsimler") }, minLines = 2, modifier = Modifier.fillMaxWidth())''',
)
replace_required(
    library,
    '''            onClick = { onSave(track.copy(title = title, isrc = isrc, primaryArtist = primary, featuredArtists = featured, explicit = explicit, lyrics = lyrics, syncedLyrics = synced, creditsText = credits)) },''',
    '''            onClick = {
                val normalized = if (synced.isBlank()) "" else runCatching { StudioLrcSupport.normalize(synced) }.getOrElse {
                    lrcMessage = it.message ?: "Senkronize LRC geçersiz."
                    ""
                }
                if (synced.isBlank() || normalized.isNotBlank()) {
                    onSave(track.copy(title = title, isrc = isrc, primaryArtist = primary, featuredArtists = featured, explicit = explicit, lyrics = lyrics, syncedLyrics = normalized, creditsText = credits))
                }
            },''',
)

# Studio Library manager: elle yapıştırılan LRC'yi de normalize et.
library_manager = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioLibraryManager.kt")
replace_required(
    library_manager,
    '            syncedLyrics = value.syncedLyrics,\n',
    '            syncedLyrics = value.syncedLyrics.takeIf(String::isNotBlank)?.let(StudioLrcSupport::normalize).orEmpty(),\n',
)

# Toplu söz yöneticisi: LRC dosyalarını standart satırlara dönüştür ve tüm şarkıları listeleyebil.
completion_manager = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/AudioCompletionManager.kt")
replace_required(
    completion_manager,
    '''    fun attachAudioBatch(
''',
    '''    fun allTracks(snapshot: CatalogSnapshot): List<PendingTrack> {
        val root = snapshot.json
        val trackMap = buildMap<String, JSONObject> {
            val rows = root.optJSONArray("tracks") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                put(row.optString("id"), row)
            }
        }
        val result = mutableListOf<PendingTrack>()
        val releases = root.optJSONArray("releases") ?: JSONArray()
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.optJSONObject(releaseIndex) ?: continue
            val refs = release.optJSONArray("tracks") ?: JSONArray()
            for (refIndex in 0 until refs.length()) {
                val ref = refs.optJSONObject(refIndex) ?: continue
                val track = trackMap[ref.optString("trackId")] ?: continue
                result += PendingTrack(
                    id = track.optString("id"),
                    title = track.optString("title", "İsimsiz"),
                    isrc = track.optString("isrc"),
                    releaseTitle = release.optString("title", "Yayın"),
                    disc = ref.optInt("disc", 1),
                    position = ref.optInt("position", refIndex + 1),
                )
            }
        }
        return result.distinctBy(PendingTrack::id)
            .sortedWith(compareBy<PendingTrack> { it.releaseTitle.lowercase() }.thenBy { it.disc }.thenBy { it.position })
    }

    fun attachAudioBatch(
''',
)
replace_required(
    completion_manager,
    '            if (content.second) track.put("syncedLyrics", content.first) else track.put("lyrics", content.first)\n',
    '            if (content.second) track.put("syncedLyrics", StudioLrcSupport.normalize(content.first)) else track.put("lyrics", content.first.trim())\n',
)

# Toplu LRC ekranı: söz modunda bütün şarkıları göster.
completion_activity = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioAudioCompletionActivity.kt")
replace_required(
    completion_activity,
    '''    fun autoMatch() {
        val result = MediaMatcher.match(tracks.map(PendingTrack::title), selectedFiles.map(AssetDraft::displayName))
        assignments.clear()
        result.forEach { assignments[tracks[it.targetIndex].id] = it.fileIndex }
        status = "Eşleştirme hazır: isim benzerliği kullanıldı, kalanlar albüm sırasına göre dolduruldu."
    }
''',
    '''    fun autoMatch() {
        val result = MediaMatcher.match(tracks.map(PendingTrack::title), selectedFiles.map(AssetDraft::displayName))
        assignments.clear()
        result.forEach { assignments[tracks[it.targetIndex].id] = it.fileIndex }
        status = "Eşleştirme hazır: isim benzerliği kullanıldı, kalanlar albüm sırasına göre dolduruldu."
    }

    fun tracksForMode(value: CatalogSnapshot?): List<PendingTrack> = value?.let {
        if (mode == CompletionMode.AUDIO) manager.pendingTracks(it) else manager.allTracks(it)
    }.orEmpty()
''',
)
replace_required(completion_activity, '                    tracks = manager.pendingTracks(it)\n', '                    tracks = tracksForMode(it)\n')
replace_required(
    completion_activity,
    'FilterChip(selected = mode == option, onClick = { mode = option; selectedFiles.clear(); assignments.clear() }, label = { Text(option.title) })',
    'FilterChip(selected = mode == option, onClick = { mode = option; selectedFiles.clear(); assignments.clear(); tracks = tracksForMode(snapshot) }, label = { Text(option.title) })',
)
replace_required(
    completion_activity,
    'else lyricsPicker.launch(arrayOf("text/plain", "application/x-subrip", "application/octet-stream"))',
    'else lyricsPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream"))',
)
replace_required(
    completion_activity,
    '                        manager.attachLyricsBatch(current, textAssignments)\n',
    '                        manager.attachLyricsBatch(current, textAssignments)\n',
)
replace_required(
    completion_activity,
    '''                }.onSuccess {
                    snapshot = it
                    selectedFiles.clear()
                    assignments.clear()
                    status = "Söz dosyaları mevcut şarkılara bağlandı."''',
    '''                }.onSuccess {
                    snapshot = it
                    tracks = manager.allTracks(it)
                    selectedFiles.clear()
                    assignments.clear()
                    status = "Söz dosyaları mevcut şarkılara bağlandı ve LRC zamanları doğrulandı."''',
)
replace_all_required(completion_activity, 'Text("Yakında Ses Tamamlama", fontWeight = FontWeight.Bold)', 'Text("Ses ve LRC Tamamlama", fontWeight = FontWeight.Bold)')

# Yeni Yayın: her parça kartında LRC dosyası seç.
studio_v2 = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt")
replace_required(
    studio_v2,
    '    var audioTarget by remember { mutableStateOf<AudioTarget?>(null) }\n',
    '    var audioTarget by remember { mutableStateOf<AudioTarget?>(null) }\n    var lrcTargetIndex by remember { mutableStateOf<Int?>(null) }\n',
)
replace_required(
    studio_v2,
    '''        audioTarget = null
    }


    val bulkAudioPicker''',
    '''        audioTarget = null
    }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = lrcTargetIndex
        lrcTargetIndex = null
        if (uri != null && index != null && index in releaseTracks.indices) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("LRC dosyası okunamadı.")
                StudioLrcSupport.normalize(raw)
            }.onSuccess { normalized ->
                releaseTracks[index] = releaseTracks[index].copy(syncedLyrics = normalized)
                status = "${releaseTracks[index].title}: LRC doğrulandı ve yüklendi."
                error = null
            }.onFailure {
                error = it.message ?: "LRC dosyası geçersiz."
            }
        }
    }

    val bulkAudioPicker''',
)
replace_all_required(studio_v2, 'Text("v0.6.0 • ${screen.title}"', 'Text("v0.7.0 • ${screen.title}"')
replace_required(
    studio_v2,
    '''                    pickTrackAudio = { index -> audioTarget = AudioTarget.NewTrack(index); audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    publish = ::publishRelease,''',
    '''                    pickTrackAudio = { index -> audioTarget = AudioTarget.NewTrack(index); audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    pickTrackLrc = { index -> lrcTargetIndex = index; lrcPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream")) },
                    publish = ::publishRelease,''',
)
replace_required(
    studio_v2,
    '''    pickTrackAudio: (Int) -> Unit,
    publish: () -> Unit,''',
    '''    pickTrackAudio: (Int) -> Unit,
    pickTrackLrc: (Int) -> Unit,
    publish: () -> Unit,''',
)
replace_required(
    studio_v2,
    'V2TrackCard(index, track, { updateTrack(index, it) }, { pickTrackAudio(index) }, { removeTrack(index) }, busy)',
    'V2TrackCard(index, track, { updateTrack(index, it) }, { pickTrackAudio(index) }, { pickTrackLrc(index) }, { removeTrack(index) }, busy)',
)
replace_required(
    studio_v2,
    '''    pickAudio: () -> Unit,
    remove: () -> Unit,''',
    '''    pickAudio: () -> Unit,
    pickLrc: () -> Unit,
    remove: () -> Unit,''',
)
replace_required(
    studio_v2,
    '''        OutlinedTextField(value.syncedLyrics, { onValue(value.copy(syncedLyrics = it)) }, label = { Text("Senkronize LRC") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.creditsText,''',
    '''        OutlinedTextField(value.syncedLyrics, { onValue(value.copy(syncedLyrics = it)) }, label = { Text("Senkronize LRC") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = pickLrc, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.UploadFile, null)
            Text(" LRC Dosyası Seç")
        }
        OutlinedTextField(value.creditsText,''',
)

print("Aurora v0.7 kaynak yaması tamamlandı.")
