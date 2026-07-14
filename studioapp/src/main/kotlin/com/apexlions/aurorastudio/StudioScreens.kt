package com.apexlions.aurorastudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun StatusArea(status: String, error: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(StudioSurface.copy(alpha = .65f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(status, color = if (error == null) StudioMuted else StudioError, fontSize = 12.sp)
        error?.let {
            Text(it, color = StudioError, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ReleaseEditor(
    snapshotLoaded: Boolean,
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
    featured: Boolean,
    onFeatured: (Boolean) -> Unit,
    coverUrl: String,
    onCoverUrl: (String) -> Unit,
    videoUrl: String,
    onVideoUrl: (String) -> Unit,
    coverAsset: AssetDraft?,
    videoAsset: AssetDraft?,
    tracks: List<TrackDraft>,
    busy: Boolean,
    pickCover: () -> Unit,
    clearCover: () -> Unit,
    pickVideo: () -> Unit,
    clearVideo: () -> Unit,
    pickAudio: () -> Unit,
    updateTrack: (Int, TrackDraft) -> Unit,
    removeTrack: (Int) -> Unit,
    moveTrack: (Int, Int) -> Unit,
    publish: () -> Unit,
    loadCatalog: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 42.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard("Yayın Bilgileri") {
                OutlinedTextField(releaseTitle, onReleaseTitle, label = { Text("Yayın adı") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mainArtist, onMainArtist, label = { Text("Ana sanatçı") }, modifier = Modifier.fillMaxWidth())
                Text("Yayın türü", color = StudioMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("single" to "Single", "maxi_single" to "Maxi", "ep" to "EP", "album" to "Albüm").forEach { (value, title) ->
                        FilterChip(
                            selected = releaseType == value,
                            onClick = { onReleaseType(value) },
                            label = { Text(title, fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(releaseDate, onReleaseDate, label = { Text("Yayın tarihi (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, onLabel, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(copyright, onCopyright, label = { Text("Telif") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, onDescription, label = { Text("Açıklama") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ana sayfada öne çıkar", fontWeight = FontWeight.SemiBold)
                        Text("Yayın featuredReleaseIds sırasının başına eklenir.", color = StudioMuted, fontSize = 11.sp)
                    }
                    Switch(checked = featured, onCheckedChange = onFeatured)
                }
            }
        }

        item {
            SectionCard("Kapak ve Hareketli Video") {
                OutlinedTextField(coverUrl, onCoverUrl, label = { Text("Kapak URL'si") }, modifier = Modifier.fillMaxWidth())
                AssetRow("Kapak dosyası", coverAsset, pickCover, clearCover)
                OutlinedTextField(videoUrl, onVideoUrl, label = { Text("Hareketli video URL'si") }, modifier = Modifier.fillMaxWidth())
                AssetRow("Hareketli video", videoAsset, pickVideo, clearVideo)
            }
        }

        item {
            SectionCard("Şarkılar") {
                Button(onClick = pickAudio, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Sıralı Ses Dosyaları Seç")
                }
                Text(
                    "Mobil Studio dosyayı olduğu formatta yükler. Çoklu kalite dönüşümü PC Studio'da yapılır.",
                    color = StudioMuted,
                    fontSize = 11.sp,
                )
            }
        }

        itemsIndexed(tracks, key = { index, item -> "${item.uri}-$index" }) { index, item ->
            TrackDraftCard(
                number = index + 1,
                value = item,
                onValue = { updateTrack(index, it) },
                moveUp = { moveTrack(index, -1) },
                moveDown = { moveTrack(index, 1) },
                remove = { removeTrack(index) },
                canMoveUp = index > 0,
                canMoveDown = index < tracks.lastIndex,
            )
        }

        item {
            if (!snapshotLoaded) {
                Button(onClick = loadCatalog, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Önce Kataloğu Yükle")
                }
            } else {
                Button(onClick = publish, enabled = !busy && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CloudUpload, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Hugging Face'e Yükle ve Yayınla")
                }
            }
        }
    }
}

@Composable
private fun AssetRow(title: String, asset: AssetDraft?, pick: () -> Unit, clear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                asset?.let { "${it.displayName} • ${humanSize(it.size)}" } ?: "Dosya seçilmedi",
                color = StudioMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = pick) { Text("Seç") }
        if (asset != null) IconButton(onClick = clear) { Icon(Icons.Rounded.Delete, "Temizle") }
    }
}

@Composable
private fun TrackDraftCard(
    number: Int,
    value: TrackDraft,
    onValue: (TrackDraft) -> Unit,
    moveUp: () -> Unit,
    moveDown: () -> Unit,
    remove: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number", color = StudioAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(value.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(humanSize(value.size), color = StudioMuted, fontSize = 11.sp)
                }
                IconButton(onClick = moveUp, enabled = canMoveUp) { Icon(Icons.Rounded.ArrowUpward, "Yukarı") }
                IconButton(onClick = moveDown, enabled = canMoveDown) { Icon(Icons.Rounded.ArrowDownward, "Aşağı") }
                IconButton(onClick = remove) { Icon(Icons.Rounded.Delete, "Sil", tint = StudioError) }
            }
            OutlinedTextField(
                value.title,
                { onValue(value.copy(title = it)) },
                label = { Text("Şarkı adı") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value.primaryArtist,
                { onValue(value.copy(primaryArtist = it)) },
                label = { Text("Ana sanatçı") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value.featuredArtists,
                { onValue(value.copy(featuredArtists = it)) },
                label = { Text("Feat isimleri") },
                supportingText = { Text("Sanatçı profili olmadan isim yazılabilir. Virgülle ayırın.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value.isrc,
                { onValue(value.copy(isrc = it)) },
                label = { Text("ISRC") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value.lyrics,
                { onValue(value.copy(lyrics = it)) },
                label = { Text("Şarkı sözleri") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Explicit", fontWeight = FontWeight.SemiBold)
                    Text("Müzik uygulamasında şarkının yanında E gösterilir.", color = StudioMuted, fontSize = 11.sp)
                }
                Switch(checked = value.explicit, onCheckedChange = { onValue(value.copy(explicit = it)) })
            }
        }
    }
}

@Composable
internal fun CatalogScreen(snapshot: CatalogSnapshot?, busy: Boolean, reload: () -> Unit) {
    if (snapshot == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Katalog henüz yüklenmedi", color = StudioMuted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = reload, enabled = !busy) { Text("GitHub'dan Yükle") }
            }
        }
        return
    }
    val catalog = snapshot.json
    val tracks = catalog.optJSONArray("tracks")
    val releases = catalog.optJSONArray("releases")
    val artists = catalog.optJSONArray("artists")
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 42.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Katalog Özeti") {
                Text("${tracks?.length() ?: 0} şarkı")
                Text("${releases?.length() ?: 0} yayın")
                Text("${artists?.length() ?: 0} sanatçı")
                Text("GitHub SHA: ${snapshot.sha.take(12)}…", color = StudioMuted, fontSize = 11.sp)
            }
        }
        if (releases != null) {
            items(releases.length()) { index ->
                val release = releases.optJSONObject(index) ?: JSONObject()
                Card(colors = CardDefaults.cardColors(containerColor = StudioSurface), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(release.optString("title", "İsimsiz yayın"), fontWeight = FontWeight.SemiBold)
                        Text("${release.optString("type")} • ${release.optString("releaseDate")}", color = StudioMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(config: StudioConfig, onConfig: (StudioConfig) -> Unit, busy: Boolean, save: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 42.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard("GitHub") {
                OutlinedTextField(config.githubRepo, { onConfig(config.copy(githubRepo = it)) }, label = { Text("owner/repo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(config.githubBranch, { onConfig(config.copy(githubBranch = it)) }, label = { Text("Dal") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(config.catalogPath, { onConfig(config.copy(catalogPath = it)) }, label = { Text("Katalog yolu") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    config.githubToken,
                    { onConfig(config.copy(githubToken = it)) },
                    label = { Text("GitHub token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SectionCard("Hugging Face / Xet") {
                OutlinedTextField(config.hfRepo, { onConfig(config.copy(hfRepo = it)) }, label = { Text("Dataset repo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    config.hfToken,
                    { onConfig(config.copy(hfToken = it)) },
                    label = { Text("Hugging Face Write token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Xet kapatılmaz. Mobil sürüm Hugging Face'in Git LFS uyumluluk katmanından Xet-backed depoya yükler. Her klasör 9.000 öğede bölünür ve güvenli sınır 120 commit/saattir.",
                    color = StudioMuted,
                    fontSize = 11.sp,
                )
            }
        }
        item {
            Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Save, null)
                Spacer(Modifier.size(6.dp))
                Text("Şifreli Kaydet")
            }
        }
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable Column.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

internal fun humanSize(bytes: Long): String {
    var value = bytes.toDouble().coerceAtLeast(0.0)
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var unit = units.first()
    for (candidate in units) {
        unit = candidate
        if (value < 1024.0 || candidate == units.last()) break
        value /= 1024.0
    }
    return if (unit == "B") "${value.toLong()} B" else "%.1f %s".format(value, unit)
}
