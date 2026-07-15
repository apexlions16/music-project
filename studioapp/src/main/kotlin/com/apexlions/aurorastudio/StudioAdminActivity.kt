package com.apexlions.aurorastudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val AdminBackground = Color(0xFF08090D)
private val AdminSurface = Color(0xFF171922)
private val AdminAccent = Color(0xFF9C5CFF)
private val AdminMuted = Color(0xFFA7A8B3)
private val AdminError = Color(0xFFFF7084)

internal enum class StudioAdminMode(val key: String, val title: String) {
    OVERVIEW("overview", "Genel Bakış"),
    ARTISTS("artists", "Sanatçılar"),
    JSON("json", "Katalog JSON"),
    SETTINGS("settings", "Ayarlar");

    companion object {
        fun from(value: String?): StudioAdminMode = entries.firstOrNull { it.key == value } ?: OVERVIEW
    }
}

internal data class AdminArtist(
    val id: String,
    val name: String,
    val image: String,
    val heroImage: String,
    val backgroundImage: String,
    val backgroundVideoUrl: String,
    val bio: String,
)

internal class StudioAdminManager(private val config: StudioConfig) {
    private val github = GitHubCatalogClient(config, AuroraHttp())

    fun load(): CatalogSnapshot = github.loadCatalog()

    fun artists(snapshot: CatalogSnapshot): List<AdminArtist> {
        val rows = snapshot.json.optJSONArray("artists") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                add(
                    AdminArtist(
                        id = row.optString("id"),
                        name = row.optString("name", "Adsız"),
                        image = row.optString("image"),
                        heroImage = row.optString("heroImage"),
                        backgroundImage = row.optString("backgroundImage"),
                        backgroundVideoUrl = row.optString("backgroundVideoUrl"),
                        bio = row.optString("bio"),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun saveArtist(snapshot: CatalogSnapshot, value: AdminArtist): CatalogSnapshot {
        require(value.name.isNotBlank()) { "Sanatçı adı gereklidir." }
        val root = JSONObject(snapshot.json.toString())
        val rows = root.optJSONArray("artists") ?: error("Sanatçı dizisi bulunamadı.")
        var target: JSONObject? = null
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("id") == value.id) target = row
        }
        val artist = target ?: error("Sanatçı bulunamadı.")
        artist
            .put("name", value.name.trim())
            .put("slug", slugify(value.name))
            .put("image", value.image.trim())
            .put("heroImage", value.heroImage.trim())
            .put("backgroundImage", value.backgroundImage.trim())
            .put("backgroundVideoUrl", value.backgroundVideoUrl.trim())
            .put("bio", value.bio)
        github.commitCatalog(root, snapshot.sha, "Aurora Music: ${value.name} sanatçısını güncelle")
        return github.loadCatalog()
    }

    fun deleteArtist(snapshot: CatalogSnapshot, artistId: String): CatalogSnapshot {
        val root = JSONObject(snapshot.json.toString())
        val usedByTrack = root.optJSONArray("tracks")?.let { rows ->
            (0 until rows.length()).any { index ->
                val row = rows.optJSONObject(index) ?: return@any false
                val ids = row.optJSONArray("artistIds") ?: JSONArray()
                (0 until ids.length()).any { ids.optString(it) == artistId }
            }
        } ?: false
        val usedByRelease = root.optJSONArray("releases")?.let { rows ->
            (0 until rows.length()).any { index ->
                val row = rows.optJSONObject(index) ?: return@any false
                val ids = row.optJSONArray("artistIds") ?: JSONArray()
                (0 until ids.length()).any { ids.optString(it) == artistId }
            }
        } ?: false
        require(!usedByTrack && !usedByRelease) { "Bu sanatçı yayın veya şarkılarda kullanılıyor; önce bağlantıları değiştirin." }
        val artists = root.optJSONArray("artists") ?: JSONArray()
        root.put("artists", JSONArray(buildList {
            for (index in 0 until artists.length()) {
                val row = artists.optJSONObject(index) ?: continue
                if (row.optString("id") != artistId) add(row)
            }
        }))
        github.commitCatalog(root, snapshot.sha, "Aurora Music: kullanılmayan sanatçıyı sil")
        return github.loadCatalog()
    }

    fun commitJson(snapshot: CatalogSnapshot, text: String): CatalogSnapshot {
        val root = JSONObject(text)
        require(root.optJSONArray("tracks") != null) { "tracks dizisi bulunamadı." }
        require(root.optJSONArray("releases") != null) { "releases dizisi bulunamadı." }
        require(root.optJSONArray("artists") != null) { "artists dizisi bulunamadı." }
        github.commitCatalog(root, snapshot.sha, "Aurora Studio Mobile: katalog JSON güncellemesi")
        return github.loadCatalog()
    }
}

class StudioAdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = StudioAdminMode.from(intent.getStringExtra("mode"))
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AdminAccent,
                    background = AdminBackground,
                    surface = AdminSurface,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    error = AdminError,
                ),
            ) {
                StudioAdminApp(mode, ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioAdminApp(initialMode: StudioAdminMode, back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initialMode) }
    var config by remember { mutableStateOf(SecureSettings.load(context)) }
    var providers by remember { mutableStateOf(ProviderSettings.load(context)) }
    val manager = remember(config) { StudioAdminManager(config) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Hazır") }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        if (busy || mode == StudioAdminMode.SETTINGS) return
        busy = true
        error = null
        status = "GitHub kataloğu yükleniyor…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.load() } }
                .onSuccess { snapshot = it; status = "Katalog hazır" }
                .onFailure { error = it.message ?: it.toString(); status = "Katalog yüklenemedi" }
            busy = false
        }
    }

    fun perform(label: String, block: (CatalogSnapshot) -> CatalogSnapshot) {
        val current = snapshot ?: return
        if (busy) return
        busy = true
        error = null
        status = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block(current) } }
                .onSuccess { snapshot = it; status = "$label tamamlandı" }
                .onFailure { error = it.message ?: it.toString(); status = "$label başarısız" }
            busy = false
        }
    }

    LaunchedEffect(mode) { reload() }

    Scaffold(
        containerColor = AdminBackground,
        topBar = {
            TopAppBar(
                title = { Column { Text(mode.title, fontWeight = FontWeight.Bold); Text("Studio Mobile v0.7.0", color = AdminMuted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "Geri") } },
                actions = {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                    if (mode != StudioAdminMode.SETTINGS) IconButton(onClick = ::reload, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Yenile") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioAdminMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option.title, fontSize = 10.sp) })
                }
            }
            Text(status, color = if (error == null) AdminMuted else AdminError, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            error?.let { Text(it, color = AdminError, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            when (mode) {
                StudioAdminMode.OVERVIEW -> OverviewScreen(snapshot)
                StudioAdminMode.ARTISTS -> ArtistsScreen(snapshot, manager, busy, ::perform)
                StudioAdminMode.JSON -> JsonScreen(snapshot, busy) { text -> perform("Katalog JSON kaydediliyor") { manager.commitJson(it, text) } }
                StudioAdminMode.SETTINGS -> SettingsScreen(config, providers, { config = it }, { providers = it }) {
                    SecureSettings.save(context, config)
                    ProviderSettings.save(context, providers)
                    status = "Ayarlar şifreli olarak kaydedildi"
                    error = null
                }
            }
        }
    }
}

@Composable
private fun OverviewScreen(snapshot: CatalogSnapshot?) {
    val root = snapshot?.json
    val artists = root?.optJSONArray("artists")?.length() ?: 0
    val releases = root?.optJSONArray("releases")?.length() ?: 0
    val tracks = root?.optJSONArray("tracks")?.length() ?: 0
    val playable = root?.optJSONArray("tracks")?.let { rows -> (0 until rows.length()).count { rows.optJSONObject(it)?.optBoolean("playable", false) == true } } ?: 0
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AdminCard("Katalog Özeti") { Text("$artists sanatçı"); Text("$releases yayın"); Text("$tracks şarkı"); Text("$playable çalınabilir • ${tracks - playable} yakında", color = AdminMuted) } }
        item { AdminCard("Windows Studio ile eşit") { Text("Yeni Yayın, Yayın Kütüphanesi, Yakında Tamamlama, Sanatçılar, Sunum ve Listeler, Katalog JSON ve Ayarlar aynı katalog üzerinde çalışır.", color = AdminMuted) } }
    }
}

@Composable
private fun ArtistsScreen(snapshot: CatalogSnapshot?, manager: StudioAdminManager, busy: Boolean, perform: (String, (CatalogSnapshot) -> CatalogSnapshot) -> Unit) {
    val artists = remember(snapshot?.sha) { snapshot?.let(manager::artists).orEmpty() }
    var query by remember { mutableStateOf("") }
    var selectedId by remember(artists) { mutableStateOf(artists.firstOrNull()?.id.orEmpty()) }
    val selected = artists.firstOrNull { it.id == selectedId }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(query, { query = it }, label = { Text("Sanatçı ara") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        items(artists.filter { it.name.contains(query, true) }, key = { it.id }) { artist ->
            FilterChip(selected = artist.id == selectedId, onClick = { selectedId = artist.id }, label = { Text(artist.name) })
        }
        selected?.let { artist ->
            item { ArtistEditor(artist, busy, onSave = { value -> perform("Sanatçı güncelleniyor") { manager.saveArtist(it, value) } }, onDelete = { perform("Sanatçı siliniyor") { manager.deleteArtist(it, artist.id) } }) }
        }
    }
}

@Composable
private fun ArtistEditor(artist: AdminArtist, busy: Boolean, onSave: (AdminArtist) -> Unit, onDelete: () -> Unit) {
    var name by remember(artist) { mutableStateOf(artist.name) }
    var image by remember(artist) { mutableStateOf(artist.image) }
    var hero by remember(artist) { mutableStateOf(artist.heroImage) }
    var background by remember(artist) { mutableStateOf(artist.backgroundImage) }
    var video by remember(artist) { mutableStateOf(artist.backgroundVideoUrl) }
    var bio by remember(artist) { mutableStateOf(artist.bio) }
    AdminCard("Sanatçıyı Düzenle") {
        OutlinedTextField(name, { name = it }, label = { Text("Ad") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(image, { image = it }, label = { Text("Profil görseli") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hero, { hero = it }, label = { Text("Hero görseli") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(background, { background = it }, label = { Text("Arka plan görseli") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(video, { video = it }, label = { Text("Arka plan video URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bio, { bio = it }, label = { Text("Biyografi") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(artist.copy(name = name, image = image, heroImage = hero, backgroundImage = background, backgroundVideoUrl = video, bio = bio)) }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, null); Text(" Kaydet") }
        OutlinedButton(onClick = onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Delete, null, tint = AdminError); Text(" Kullanılmayan Sanatçıyı Sil", color = AdminError) }
    }
}

@Composable
private fun JsonScreen(snapshot: CatalogSnapshot?, busy: Boolean, save: (String) -> Unit) {
    var text by remember(snapshot?.sha) { mutableStateOf(snapshot?.json?.toString(2).orEmpty()) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp)) {
        item {
            AdminCard("Ham Katalog") {
                OutlinedTextField(text, { text = it }, label = { Text("catalog.json") }, minLines = 22, modifier = Modifier.fillMaxWidth())
                Button(onClick = { save(text) }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, null); Text(" JSON'u Doğrula ve Commit Et") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(config: StudioConfig, providers: ProviderConfig, onConfig: (StudioConfig) -> Unit, onProviders: (ProviderConfig) -> Unit, save: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AdminCard("GitHub") {
            OutlinedTextField(config.githubRepo, { onConfig(config.copy(githubRepo = it)) }, label = { Text("owner/repo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.githubBranch, { onConfig(config.copy(githubBranch = it)) }, label = { Text("Dal") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.catalogPath, { onConfig(config.copy(catalogPath = it)) }, label = { Text("Katalog yolu") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.githubToken, { onConfig(config.copy(githubToken = it)) }, label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        } }
        item { AdminCard("Hugging Face") {
            OutlinedTextField(config.hfRepo, { onConfig(config.copy(hfRepo = it)) }, label = { Text("Dataset repo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.hfToken, { onConfig(config.copy(hfToken = it)) }, label = { Text("Write token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        } }
        item { AdminCard("Spotify") {
            OutlinedTextField(providers.spotifyClientId, { onProviders(providers.copy(spotifyClientId = it)) }, label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(providers.spotifyClientSecret, { onProviders(providers.copy(spotifyClientSecret = it)) }, label = { Text("Client Secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = save, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, null); Text(" Şifreli Kaydet") }
        } }
    }
}

@Composable
private fun AdminCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AdminSurface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            content()
        }
    }
}
