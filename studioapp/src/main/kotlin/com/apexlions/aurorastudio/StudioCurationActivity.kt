package com.apexlions.aurorastudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

private val CurationBackground = Color(0xFF08090D)
private val CurationSurface = Color(0xFF171922)
private val CurationAccent = Color(0xFF9C5CFF)
private val CurationMuted = Color(0xFFA7A8B3)
private val CurationError = Color(0xFFFF7084)

class StudioCurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CurationAccent,
                    background = CurationBackground,
                    surface = CurationSurface,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    error = CurationError,
                ),
            ) {
                CurationApp(
                    back = ::finish,
                    openSettings = { startActivity(Intent(this, StudioV2Activity::class.java)) },
                )
            }
        }
    }
}

private enum class CurationScreen(val title: String) {
    POPULAR("Popülerler"),
    LISTS("Sanatçı Listeleri"),
    HOME("Ana Sayfa"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurationApp(back: () -> Unit, openSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(SecureSettings.load(context)) }
    val manager = remember(config) { CurationManager(config) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var data by remember { mutableStateOf<CurationData?>(null) }
    var screen by remember { mutableStateOf(CurationScreen.POPULAR) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Katalog yükleniyor…") }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        if (busy) return
        config = SecureSettings.load(context)
        busy = true
        error = null
        status = "GitHub kataloğu yükleniyor…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.load() } }
                .onSuccess {
                    snapshot = it
                    data = manager.parse(it)
                    status = "Sunum kataloğu hazır"
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "Katalog yüklenemedi"
                }
            busy = false
        }
    }

    fun perform(label: String, action: (CatalogSnapshot) -> CatalogSnapshot) {
        val current = snapshot ?: run {
            error = "Önce kataloğu yükleyin."
            return
        }
        if (busy) return
        busy = true
        error = null
        status = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action(current) } }
                .onSuccess {
                    snapshot = it
                    data = manager.parse(it)
                    status = "$label tamamlandı"
                }
                .onFailure {
                    error = it.message ?: it.toString()
                    status = "$label başarısız"
                }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CurationBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sunum ve Listeler", fontWeight = FontWeight.Bold)
                        Text("Studio Mobile v0.3.0 • ${screen.title}", color = CurationMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "Geri") } },
                actions = {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(10.dp).size(23.dp), strokeWidth = 2.dp)
                    IconButton(onClick = ::reload, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Yenile") }
                    IconButton(onClick = openSettings) { Icon(Icons.Rounded.Settings, "Ayarlar") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CurationSurface) {
                CurationScreen.entries.forEach { item ->
                    val icon = when (item) {
                        CurationScreen.POPULAR -> Icons.Rounded.People
                        CurationScreen.LISTS -> Icons.Rounded.LibraryMusic
                        CurationScreen.HOME -> Icons.Rounded.Home
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(status, color = if (error == null) CurationMuted else CurationError, fontSize = 12.sp)
                error?.let { Text(it, color = CurationError, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
            when {
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (busy) CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = openSettings) { Text("Token ve repo ayarlarını aç") }
                    }
                }
                screen == CurationScreen.POPULAR -> PopularScreen(
                    data = data!!,
                    busy = busy,
                    save = { artistId, ids -> perform("Popüler sırası kaydediliyor") { manager.savePopular(it, artistId, ids) } },
                )
                screen == CurationScreen.LISTS -> ArtistListsScreen(
                    data = data!!,
                    busy = busy,
                    save = { value -> perform("Sanatçı listesi kaydediliyor") { manager.saveArtistList(it, value) } },
                    delete = { id -> perform("Sanatçı listesi siliniyor") { manager.deleteArtistList(it, id) } },
                )
                else -> HomeSectionsScreen(
                    data = data!!,
                    busy = busy,
                    save = { value -> perform("Ana sayfa bölümü kaydediliyor") { manager.saveHomeSection(it, value) } },
                    delete = { id -> perform("Ana sayfa bölümü siliniyor") { manager.deleteHomeSection(it, id) } },
                    move = { id, direction -> perform("Ana sayfa sırası değiştiriliyor") { manager.moveHomeSection(it, id, direction) } },
                )
            }
        }
    }
}

@Composable
private fun PopularScreen(
    data: CurationData,
    busy: Boolean,
    save: (String, List<String>) -> Unit,
) {
    var artistId by remember(data) { mutableStateOf(data.artists.firstOrNull()?.id.orEmpty()) }
    val selected = remember { mutableStateListOf<String>() }
    val artist = data.artists.firstOrNull { it.id == artistId }
    val tracks = data.tracks.filter { artistId in it.artistIds }

    LaunchedEffect(data, artistId) {
        selected.clear()
        selected.addAll(artist?.popularTrackIds.orEmpty().filter { id -> tracks.any { it.id == id } }.take(5))
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 6.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CurationCard("Sanatçı") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data.artists, key = { it.id }) { row ->
                        FilterChip(selected = artistId == row.id, onClick = { artistId = row.id }, label = { Text(row.name) })
                    }
                }
                Text("En fazla beş şarkı gösterilir. Hiç seçim yapmazsan müzik uygulaması en yeni beş hazır parçayı kullanır.", color = CurationMuted, fontSize = 11.sp)
            }
        }
        item {
            CurationCard("Uygulamadaki Popüler Sırası") {
                if (selected.isEmpty()) Text("Otomatik: en yeni beş çalınabilir şarkı", color = CurationMuted)
                selected.forEachIndexed { index, id ->
                    val track = data.tracks.firstOrNull { it.id == id } ?: return@forEachIndexed
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = CurationAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                        Text(track.title, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = {
                            if (index > 0) {
                                val value = selected.removeAt(index)
                                selected.add(index - 1, value)
                            }
                        }) { Icon(Icons.Rounded.ArrowUpward, "Yukarı") }
                        IconButton(onClick = {
                            if (index < selected.lastIndex) {
                                val value = selected.removeAt(index)
                                selected.add(index + 1, value)
                            }
                        }) { Icon(Icons.Rounded.ArrowDownward, "Aşağı") }
                        IconButton(onClick = { selected.remove(id) }) { Icon(Icons.Rounded.Delete, "Çıkar", tint = CurationError) }
                    }
                }
            }
        }
        item {
            CurationCard("Sanatçının Şarkıları") {
                tracks.forEach { track ->
                    val checked = track.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !checked && selected.size < 5) { selected += track.id }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { value ->
                                if (value && selected.size < 5) selected += track.id else if (!value) selected.remove(track.id)
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(track.title, fontWeight = FontWeight.SemiBold)
                            Text(if (track.playable) "Çalınabilir" else "Yakında", color = if (track.playable) CurationMuted else CurationAccent, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = { save(artistId, selected.toList()) }, enabled = artistId.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Save, null)
                Text(" Popüler Sırasını Kaydet")
            }
        }
    }
}

@Composable
private fun ArtistListsScreen(
    data: CurationData,
    busy: Boolean,
    save: (CurationArtistList) -> Unit,
    delete: (String) -> Unit,
) {
    var listId by remember(data) { mutableStateOf(data.artistLists.firstOrNull()?.id.orEmpty()) }
    var artistId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf("") }
    val trackIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(data, listId) {
        val row = data.artistLists.firstOrNull { it.id == listId }
        artistId = row?.artistId ?: data.artists.firstOrNull()?.id.orEmpty()
        title = row?.title.orEmpty()
        description = row?.description.orEmpty()
        cover = row?.cover.orEmpty()
        trackIds.clear()
        trackIds.addAll(row?.trackIds.orEmpty())
    }

    val tracks = data.tracks.filter { artistId in it.artistIds }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 6.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CurationCard("Listeler") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = listId.isBlank(),
                            onClick = { listId = "" },
                            label = { Text("+ Yeni") },
                            leadingIcon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    items(data.artistLists, key = { it.id }) { row ->
                        FilterChip(selected = listId == row.id, onClick = { listId = row.id }, label = { Text(row.title) })
                    }
                }
            }
        }
        item {
            CurationCard("Liste Bilgileri") {
                Text("Sanatçı", color = CurationMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data.artists, key = { it.id }) { row ->
                        FilterChip(selected = artistId == row.id, onClick = { artistId = row.id; trackIds.retainAll(data.tracks.filter { artistId in it.artistIds }.map { it.id }.toSet()) }, label = { Text(row.name) })
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Liste başlığı") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Açıklama") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cover, { cover = it }, label = { Text("Kapak URL'si") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            CurationCard("Şarkılar") {
                tracks.forEach { track ->
                    val checked = track.id in trackIds
                    Row(Modifier.fillMaxWidth().clickable { if (checked) trackIds.remove(track.id) else trackIds.add(track.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { if (it) trackIds.add(track.id) else trackIds.remove(track.id) })
                        Column(Modifier.weight(1f)) {
                            Text(track.title)
                            Text(if (track.playable) "Hazır" else "Yakında", color = CurationMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (listId.isNotBlank()) {
                    OutlinedButton(onClick = { delete(listId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Delete, null, tint = CurationError)
                        Text(" Sil", color = CurationError)
                    }
                }
                Button(
                    onClick = { save(CurationArtistList(listId, artistId, title, description, cover, trackIds.toList())) },
                    enabled = artistId.isNotBlank() && title.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Save, null)
                    Text(" Kaydet")
                }
            }
        }
    }
}

@Composable
private fun HomeSectionsScreen(
    data: CurationData,
    busy: Boolean,
    save: (CurationHomeSection) -> Unit,
    delete: (String) -> Unit,
    move: (String, Int) -> Unit,
) {
    var sectionId by remember(data) { mutableStateOf(data.homeSections.firstOrNull()?.id.orEmpty()) }
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("releases") }
    var layout by remember { mutableStateOf("horizontal") }
    val itemIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(data, sectionId) {
        val row = data.homeSections.firstOrNull { it.id == sectionId }
        title = row?.title.orEmpty()
        subtitle = row?.subtitle.orEmpty()
        type = row?.type ?: "releases"
        layout = row?.layout ?: "horizontal"
        itemIds.clear()
        itemIds.addAll(row?.itemIds.orEmpty())
    }

    val choices: List<Pair<String, String>> = when (type) {
        "artists" -> data.artists.map { it.id to it.name }
        "tracks" -> data.tracks.map { it.id to it.title }
        "lists" -> data.artistLists.map { it.id to it.title }
        else -> data.releases
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 6.dp, 16.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CurationCard("Bölümler ve Sıra") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = sectionId.isBlank(),
                            onClick = { sectionId = "" },
                            label = { Text("+ Yeni") },
                            leadingIcon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    items(data.homeSections, key = { it.id }) { row ->
                        FilterChip(selected = sectionId == row.id, onClick = { sectionId = row.id }, label = { Text(row.title) })
                    }
                }
                if (sectionId.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { move(sectionId, -1) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ArrowUpward, null)
                            Text(" Yukarı")
                        }
                        OutlinedButton(onClick = { move(sectionId, 1) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ArrowDownward, null)
                            Text(" Aşağı")
                        }
                    }
                }
            }
        }
        item {
            CurationCard("Bölüm Bilgileri") {
                OutlinedTextField(title, { title = it }, label = { Text("Başlık") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subtitle, { subtitle = it }, label = { Text("Alt başlık") }, modifier = Modifier.fillMaxWidth())
                Text("İçerik türü", color = CurationMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("releases" to "Yayınlar", "artists" to "Sanatçılar", "tracks" to "Şarkılar", "lists" to "Listeler")) { option ->
                        FilterChip(
                            selected = type == option.first,
                            onClick = { type = option.first; itemIds.clear() },
                            label = { Text(option.second) },
                        )
                    }
                }
                Text("Görünüm", color = CurationMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = layout == "horizontal", onClick = { layout = "horizontal" }, label = { Text("Yatay Raf") })
                    FilterChip(selected = layout == "hero", onClick = { layout = "hero" }, label = { Text("Büyük Öne Çıkan") })
                }
            }
        }
        item {
            CurationCard("Gösterilecek Öğeler") {
                choices.forEach { choice ->
                    val checked = choice.first in itemIds
                    Row(Modifier.fillMaxWidth().clickable { if (checked) itemIds.remove(choice.first) else itemIds.add(choice.first) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { if (it) itemIds.add(choice.first) else itemIds.remove(choice.first) })
                        Text(choice.second, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sectionId.isNotBlank()) {
                    OutlinedButton(onClick = { delete(sectionId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Delete, null, tint = CurationError)
                        Text(" Sil", color = CurationError)
                    }
                }
                Button(
                    onClick = { save(CurationHomeSection(sectionId, title, subtitle, type, layout, itemIds.toList())) },
                    enabled = title.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Save, null)
                    Text(" Kaydet")
                }
            }
        }
    }
}

@Composable
private fun CurationCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CurationSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            content()
        }
    }
}
