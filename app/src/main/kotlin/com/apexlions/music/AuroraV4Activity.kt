package com.apexlions.music

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val V4Background = Color(0xFF08080A)
private val V4Surface = Color(0xFF17171B)
private val V4SurfaceLight = Color(0xFF24242A)
private val V4Accent = Color(0xFF9C5CFF)
private val V4Muted = Color(0xFFA8A8B2)
private val V4Error = Color(0xFFFF6B7D)

class AuroraV4Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { V4Theme { AuroraV4App() } }
    }
}

@Composable
private fun V4Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = V4Accent,
            background = V4Background,
            surface = V4Surface,
            surfaceVariant = V4SurfaceLight,
            onBackground = Color.White,
            onSurface = Color.White,
            error = V4Error,
        ),
        content = content,
    )
}

private enum class V4Tab(val title: String) {
    HOME("Ana Sayfa"),
    RELEASES("Yayınlar"),
    ARTISTS("Sanatçılar"),
    SEARCH("Ara"),
}

private enum class V4Sort(val title: String) {
    NEWEST("Yeni → Eski"),
    OLDEST("Eski → Yeni"),
    TITLE("A → Z"),
}

private sealed interface V4Page {
    data class ReleasePage(val id: String) : V4Page
    data class ArtistPage(val id: String) : V4Page
    data class ArtistListPage(val id: String) : V4Page
    data class DiscographyPage(val artistId: String) : V4Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuroraV4App() {
    val context = LocalContext.current
    val controller = remember { PlayerController(context) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(V4Tab.HOME) }
    var page by remember { mutableStateOf<V4Page?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var animated by remember { mutableStateOf(AppSettings.animatedCovers(context)) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        runCatching { CatalogRepository.load(context, forceRemote = refresh > 0) }
            .onSuccess { catalog = it }
            .onFailure { error = it.message ?: "Katalog yüklenemedi." }
        loading = false
    }

    LaunchedEffect(controller.player) {
        while (true) {
            controller.positionMs = controller.player.currentPosition.coerceAtLeast(0)
            controller.durationMs = controller.player.duration.takeIf { it > 0 }
                ?: controller.currentTrack?.durationSeconds?.times(1000L)
                ?: 0L
            delay(400)
        }
    }

    DisposableEffect(Unit) { onDispose(controller::release) }
    BackHandler(page != null) { page = null }

    Scaffold(
        containerColor = V4Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(catalog?.brand?.name ?: "Aurora Music", fontWeight = FontWeight.Bold)
                        Text(pageTitle(page, tab, catalog), color = V4Muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    if (page != null) IconButton(onClick = { page = null }) { Icon(Icons.Rounded.ArrowBack, "Geri") }
                },
                actions = {
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "Yenile") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Ayarlar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = V4Background.copy(alpha = .97f)),
            )
        },
        bottomBar = {
            Column(Modifier.background(V4Background.copy(alpha = .98f))) {
                AnimatedVisibility(controller.currentTrack != null) {
                    V4MiniPlayer(controller) { showPlayer = true }
                }
                NavigationBar(containerColor = V4Surface, tonalElevation = 0.dp) {
                    V4Tab.entries.forEach { item ->
                        val icon = when (item) {
                            V4Tab.HOME -> Icons.Rounded.Home
                            V4Tab.RELEASES -> Icons.Rounded.Album
                            V4Tab.ARTISTS -> Icons.Rounded.Groups
                            V4Tab.SEARCH -> Icons.Rounded.Search
                        }
                        NavigationBarItem(
                            selected = tab == item && page == null,
                            onClick = { tab = item; page = null },
                            icon = { Icon(icon, null) },
                            label = { Text(item.title, maxLines = 1, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = V4Accent.copy(alpha = .18f),
                                selectedIconColor = V4Accent,
                                selectedTextColor = V4Accent,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && catalog == null -> V4Loading()
                error != null && catalog == null -> V4ErrorState(error.orEmpty()) { refresh++ }
                catalog != null -> {
                    val data = catalog!!
                    when (val target = page) {
                        is V4Page.ReleasePage -> data.release(target.id)?.let {
                            V4ReleaseDetail(data, it, controller) { artistId -> page = V4Page.ArtistPage(artistId) }
                        }
                        is V4Page.ArtistPage -> data.artist(target.id)?.let {
                            V4ArtistDetail(
                                catalog = data,
                                artist = it,
                                controller = controller,
                                openRelease = { id -> page = V4Page.ReleasePage(id) },
                                openList = { id -> page = V4Page.ArtistListPage(id) },
                                openDiscography = { page = V4Page.DiscographyPage(it.id) },
                            )
                        }
                        is V4Page.ArtistListPage -> data.artistList(target.id)?.let {
                            V4ArtistListDetail(data, it, controller)
                        }
                        is V4Page.DiscographyPage -> data.artist(target.artistId)?.let {
                            V4Discography(data, it) { id -> page = V4Page.ReleasePage(id) }
                        }
                        null -> when (tab) {
                            V4Tab.HOME -> V4Home(
                                data,
                                openRelease = { page = V4Page.ReleasePage(it) },
                                openArtist = { page = V4Page.ArtistPage(it) },
                                openList = { page = V4Page.ArtistListPage(it) },
                                controller = controller,
                            )
                            V4Tab.RELEASES -> V4AllReleases(data) { page = V4Page.ReleasePage(it) }
                            V4Tab.ARTISTS -> V4Artists(data) { page = V4Page.ArtistPage(it) }
                            V4Tab.SEARCH -> V4Search(
                                data,
                                openRelease = { page = V4Page.ReleasePage(it) },
                                openArtist = { page = V4Page.ArtistPage(it) },
                                controller = controller,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPlayer && controller.currentTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { showPlayer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = V4Background,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .4f)) },
        ) {
            V4NowPlaying(controller, animated)
        }
    }

    if (showSettings) {
        V4Settings(controller, animated, { animated = it }) { showSettings = false }
    }
}

private fun pageTitle(page: V4Page?, tab: V4Tab, catalog: Catalog?): String = when (page) {
    is V4Page.ReleasePage -> catalog?.release(page.id)?.title ?: "Yayın"
    is V4Page.ArtistPage -> catalog?.artist(page.id)?.name ?: "Sanatçı"
    is V4Page.ArtistListPage -> catalog?.artistList(page.id)?.title ?: "Liste"
    is V4Page.DiscographyPage -> "Tüm Diskografi"
    null -> tab.title
}

@Composable
private fun V4Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = V4Accent)
}

@Composable
private fun V4ErrorState(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(Icons.Rounded.CloudOff, null, tint = V4Accent, modifier = Modifier.size(54.dp))
        Text("Katalog açılamadı", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(message, color = V4Muted)
        Spacer(Modifier.height(14.dp))
        Button(onClick = retry) { Text("Tekrar Dene") }
    }
}

@Composable
private fun V4Home(
    catalog: Catalog,
    openRelease: (String) -> Unit,
    openArtist: (String) -> Unit,
    openList: (String) -> Unit,
    controller: PlayerController,
) {
    val sections = catalog.effectiveHomeSections()
    LazyColumn(
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("Günaydın", color = V4Muted, fontWeight = FontWeight.SemiBold)
                Text("Şimdi Dinle", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text(catalog.brand.subtitle, color = V4Muted)
            }
        }
        sections.forEach { section ->
            item(key = section.id) {
                V4HomeSection(catalog, section, openRelease, openArtist, openList, controller)
            }
        }
    }
}

@Composable
private fun V4HomeSection(
    catalog: Catalog,
    section: HomeSection,
    openRelease: (String) -> Unit,
    openArtist: (String) -> Unit,
    openList: (String) -> Unit,
    controller: PlayerController,
) {
    val releases = section.releaseIds.mapNotNull(catalog::release)
    val artists = section.artistIds.mapNotNull(catalog::artist)
    val tracks = section.trackIds.mapNotNull(catalog::track)
    val lists = section.listIds.mapNotNull(catalog::artistList)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (section.layout == "hero" && releases.isNotEmpty()) {
            V4Hero(catalog, releases.first()) { openRelease(releases.first().id) }
            return@Column
        }
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(section.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            if (section.subtitle.isNotBlank()) Text(section.subtitle, color = V4Muted, fontSize = 13.sp)
        }
        when (section.type.lowercase()) {
            "artists" -> LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                items(artists, key = { it.id }) { V4ArtistTile(it) { openArtist(it.id) } }
            }
            "tracks" -> Column(Modifier.padding(horizontal = 18.dp)) {
                tracks.forEachIndexed { index, track ->
                    V4TrackRow(catalog, track, index + 1, catalog.isPlayable(track), false) {
                        controller.play(track, null, "", tracks.filter(catalog::isPlayable), catalog::trackArtistLine)
                    }
                }
            }
            "lists" -> LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                items(lists, key = { it.id }) { V4ListTile(catalog, it) { openList(it.id) } }
            }
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                items(releases, key = { it.id }) { V4ReleaseTile(catalog, it) { openRelease(it.id) } }
            }
        }
    }
}

@Composable
private fun V4Hero(catalog: Catalog, release: Release, open: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(430.dp)
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(30.dp))
            .clickable(onClick = open),
    ) {
        AsyncImage(
            model = release.heroImage.ifBlank { release.cover },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .18f), Color.Black.copy(alpha = .95f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            V4StatusPill(catalog.releaseState(release))
            Spacer(Modifier.height(8.dp))
            Text(release.title, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
            Text(catalog.releaseArtistLine(release), color = Color.White.copy(alpha = .80f), fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = open, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(Icons.Rounded.PlayArrow, null)
                Text(" Aç")
            }
        }
    }
}

@Composable
private fun V4ReleaseTile(catalog: Catalog, release: Release, open: () -> Unit) {
    Column(Modifier.width(168.dp).clickable(onClick = open)) {
        Box {
            AsyncImage(
                model = release.cover,
                contentDescription = release.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(V4SurfaceLight),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.align(Alignment.TopStart).padding(7.dp)) { V4StatusPill(catalog.releaseState(release), compact = true) }
        }
        Spacer(Modifier.height(9.dp))
        Text(release.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(catalog.releaseArtistLine(release), color = V4Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun V4ArtistTile(artist: Artist, open: () -> Unit) {
    Column(Modifier.width(138.dp).clickable(onClick = open), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = artist.image,
            contentDescription = artist.name,
            modifier = Modifier.size(132.dp).clip(CircleShape).background(V4SurfaceLight),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(9.dp))
        Text(artist.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Sanatçı", color = V4Muted, fontSize = 12.sp)
    }
}

@Composable
private fun V4ListTile(catalog: Catalog, list: ArtistList, open: () -> Unit) {
    val fallback = list.trackIds.firstNotNullOfOrNull { id ->
        catalog.releases.firstOrNull { release -> release.tracks.any { it.trackId == id } }?.cover
    }.orEmpty()
    Column(Modifier.width(168.dp).clickable(onClick = open)) {
        AsyncImage(
            model = list.cover.ifBlank { fallback },
            contentDescription = list.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(V4SurfaceLight),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(9.dp))
        Text(list.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(list.description.ifBlank { "Sanatçı seçkisi" }, color = V4Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun V4AllReleases(catalog: Catalog, open: (String) -> Unit) {
    val releases = catalog.releases.sortedByDescending { it.releaseDate }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 36.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        gridItems(releases, key = { it.id }) { release -> V4ReleaseTile(catalog, release) { open(release.id) } }
    }
}

@Composable
private fun V4Artists(catalog: Catalog, open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Tüm Sanatçılar", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text("Profiller, popülerler, seçkiler ve diskografi", color = V4Muted)
        }
        items(catalog.artists.sortedBy { it.name }, key = { it.id }) { artist ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = V4Surface,
                modifier = Modifier.fillMaxWidth().clickable { open(artist.id) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(artist.image, artist.name, Modifier.size(78.dp).clip(CircleShape).background(V4SurfaceLight), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(artist.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${catalog.popularTracks(artist).size} popüler • ${catalog.listsForArtist(artist).size} liste", color = V4Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun V4ArtistDetail(
    catalog: Catalog,
    artist: Artist,
    controller: PlayerController,
    openRelease: (String) -> Unit,
    openList: (String) -> Unit,
    openDiscography: () -> Unit,
) {
    val popular = catalog.popularTracks(artist)
    val lists = catalog.listsForArtist(artist)
    val releases = catalog.releases
        .filter { artist.id in (it.primaryArtistIds.ifEmpty { it.artistIds }) }
        .sortedByDescending { it.releaseDate }

    LazyColumn(contentPadding = PaddingValues(bottom = 38.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(350.dp)) {
                AsyncImage(
                    artist.backgroundImage.ifBlank { artist.heroImage.ifBlank { artist.image } },
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, V4Background))))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text("SANATÇI", color = V4Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(artist.name, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
                    if (artist.bio.isNotBlank()) Text(artist.bio, color = Color.White.copy(alpha = .75f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (popular.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text("Popüler", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                popular.forEachIndexed { index, track ->
                    val release = catalog.releases.firstOrNull { row -> row.tracks.any { it.trackId == track.id } }
                    V4TrackRow(catalog, track, index + 1, true, controller.currentTrack?.id == track.id) {
                        controller.play(track, release, release?.cover.orEmpty(), popular, catalog::trackArtistLine)
                    }
                }
            }
        }
        if (lists.isNotEmpty()) item {
            Column {
                V4SectionHeader("${artist.name} Seçkileri", "Sanatçıya özel listeler")
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(lists, key = { it.id }) { list -> V4ListTile(catalog, list) { openList(list.id) } }
                }
            }
        }
        item {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Diskografi", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("En yeni üç yayın", color = V4Muted, fontSize = 13.sp)
                    }
                    if (releases.size > 3) TextButton(onClick = openDiscography) { Text("Tümünü Gör") }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(releases.take(3), key = { it.id }) { release -> V4ReleaseTile(catalog, release) { openRelease(release.id) } }
                }
            }
        }
    }
}

@Composable
private fun V4Discography(catalog: Catalog, artist: Artist, open: (String) -> Unit) {
    var sort by remember(artist.id) { mutableStateOf(V4Sort.NEWEST) }
    val source = catalog.releases.filter { artist.id in (it.primaryArtistIds.ifEmpty { it.artistIds }) }
    val releases = when (sort) {
        V4Sort.NEWEST -> source.sortedByDescending { it.releaseDate }
        V4Sort.OLDEST -> source.sortedBy { it.releaseDate }
        V4Sort.TITLE -> source.sortedBy { it.title.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        Text("${artist.name} Diskografisi", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(V4Sort.entries) { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { sort = option },
                    label = { Text(option.title) },
                    leadingIcon = if (sort == option) ({ Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }) else null,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 36.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            gridItems(releases, key = { it.id }) { release -> V4ReleaseTile(catalog, release) { open(release.id) } }
        }
    }
}

@Composable
private fun V4ArtistListDetail(catalog: Catalog, list: ArtistList, controller: PlayerController) {
    val artist = catalog.artist(list.artistId)
    val tracks = list.trackIds.mapNotNull(catalog::track)
    val playable = tracks.filter(catalog::isPlayable)
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 38.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    list.cover.ifBlank { artist?.image.orEmpty() },
                    list.title,
                    Modifier.fillMaxWidth(.78f).aspectRatio(1f).clip(RoundedCornerShape(25.dp)).background(V4SurfaceLight),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(16.dp))
                Text(list.title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(artist?.name.orEmpty(), color = V4Accent)
                if (list.description.isNotBlank()) Text(list.description, color = V4Muted)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { playable.firstOrNull()?.let { controller.play(it, null, list.cover, playable, catalog::trackArtistLine) } },
                    enabled = playable.isNotEmpty(),
                ) { Icon(Icons.Rounded.PlayArrow, null); Text(" Listeyi Çal") }
            }
        }
        itemsIndexed(tracks, key = { _, it -> it.id }) { index, track ->
            V4TrackRow(catalog, track, index + 1, catalog.isPlayable(track), controller.currentTrack?.id == track.id) {
                controller.play(track, null, list.cover, playable, catalog::trackArtistLine)
            }
        }
    }
}

@Composable
private fun V4ReleaseDetail(catalog: Catalog, release: Release, controller: PlayerController, openArtist: (String) -> Unit) {
    val allTracks = catalog.releaseTracks(release)
    val playable = allTracks.filter(catalog::isPlayable)
    val state = catalog.releaseState(release)
    LazyColumn(contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 38.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    release.cover,
                    release.title,
                    Modifier.fillMaxWidth(.82f).aspectRatio(1f).clip(RoundedCornerShape(25.dp)).background(V4SurfaceLight),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(18.dp))
                V4StatusPill(state)
                Spacer(Modifier.height(8.dp))
                Text(release.title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    catalog.releaseArtistLine(release),
                    color = V4Accent,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { release.primaryArtistIds.ifEmpty { release.artistIds }.firstOrNull()?.let(openArtist) },
                )
                Text("${releaseTypeLabel(release.type)} • ${release.releaseDate.take(4)}", color = V4Muted)
                if (state != "published") {
                    Text("${playable.size}/${allTracks.size} parça şu anda dinlenebilir", color = V4Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { playable.firstOrNull()?.let { controller.play(it, release, release.cover, playable, catalog::trackArtistLine) } },
                        enabled = playable.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Rounded.PlayArrow, null); Text(" Çal") }
                    Button(
                        onClick = {
                            controller.setShuffle(true)
                            playable.randomOrNull()?.let { controller.play(it, release, release.cover, playable, catalog::trackArtistLine) }
                        },
                        enabled = playable.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = V4SurfaceLight),
                    ) { Icon(Icons.Rounded.Shuffle, null); Text(" Karıştır") }
                }
            }
        }
        itemsIndexed(allTracks, key = { _, track -> track.id }) { index, track ->
            V4TrackRow(catalog, track, index + 1, catalog.isPlayable(track), controller.currentTrack?.id == track.id) {
                controller.play(track, release, release.cover, playable, catalog::trackArtistLine)
            }
        }
        if (release.description.isNotBlank()) item { Text(release.description, color = V4Muted, lineHeight = 21.sp) }
        item {
            Column {
                if (release.label.isNotBlank()) Text(release.label, color = V4Muted, fontSize = 12.sp)
                if (release.copyright.isNotBlank()) Text(release.copyright, color = V4Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun V4TrackRow(
    catalog: Catalog,
    track: Track,
    number: Int,
    playable: Boolean,
    active: Boolean,
    click: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (playable) 1f else .42f)
            .clickable(enabled = playable, onClick = click)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            when {
                !playable -> Icon(Icons.Rounded.Lock, null, tint = V4Muted, modifier = Modifier.size(17.dp))
                active -> Icon(Icons.Rounded.GraphicEq, null, tint = V4Accent)
                else -> Text(number.toString(), color = V4Muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, color = if (active) V4Accent else Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (track.explicit) {
                    Spacer(Modifier.width(5.dp))
                    Text("E", color = V4Muted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Text(
                if (playable) catalog.trackArtistLine(track) else "Yakında • ses dosyası bekleniyor",
                color = V4Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playable) Text(formatDuration(track.durationSeconds), color = V4Muted, fontSize = 12.sp)
    }
    HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(start = 34.dp))
}

@Composable
private fun V4Search(catalog: Catalog, openRelease: (String) -> Unit, openArtist: (String) -> Unit, controller: PlayerController) {
    var query by remember { mutableStateOf("") }
    val artists = catalog.artists.filter { it.name.contains(query, true) }
    val releases = catalog.releases.filter { it.title.contains(query, true) || catalog.releaseArtistLine(it).contains(query, true) }
    val tracks = catalog.tracks.filter { it.title.contains(query, true) || catalog.trackArtistLine(it).contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 38.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ara", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Şarkı, yayın veya sanatçı") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
        }
        if (query.isNotBlank()) {
            if (artists.isNotEmpty()) item { V4SectionHeader("Sanatçılar", "") }
            items(artists, key = { "artist-${it.id}" }) { artist ->
                Surface(shape = RoundedCornerShape(16.dp), color = V4Surface, modifier = Modifier.fillMaxWidth().clickable { openArtist(artist.id) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(artist.image, artist.name, Modifier.size(58.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp)); Text(artist.name, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (releases.isNotEmpty()) item { V4SectionHeader("Yayınlar", "") }
            items(releases, key = { "release-${it.id}" }) { release ->
                Surface(shape = RoundedCornerShape(16.dp), color = V4Surface, modifier = Modifier.fillMaxWidth().clickable { openRelease(release.id) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(release.cover, release.title, Modifier.size(66.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp)); Column { Text(release.title, fontWeight = FontWeight.Bold); Text(catalog.releaseArtistLine(release), color = V4Muted, fontSize = 12.sp) }
                    }
                }
            }
            if (tracks.isNotEmpty()) item { V4SectionHeader("Şarkılar", "") }
            itemsIndexed(tracks, key = { _, it -> "track-${it.id}" }) { index, track ->
                V4TrackRow(catalog, track, index + 1, catalog.isPlayable(track), controller.currentTrack?.id == track.id) {
                    controller.play(track, null, "", listOf(track), catalog::trackArtistLine)
                }
            }
        }
    }
}

@Composable
private fun V4SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = V4Muted, fontSize = 13.sp)
    }
}

@Composable
private fun V4StatusPill(status: String, compact: Boolean = false) {
    val text = releaseStatusLabel(status)
    Surface(shape = RoundedCornerShape(999.dp), color = if (status == "published") V4Accent.copy(alpha = .88f) else Color.Black.copy(alpha = .72f)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (compact) 9.sp else 11.sp, modifier = Modifier.padding(horizontal = if (compact) 7.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp))
    }
}

@Composable
private fun V4MiniPlayer(controller: PlayerController, open: () -> Unit) {
    val track = controller.currentTrack ?: return
    Surface(color = V4SurfaceLight, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = open)) {
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(controller.currentCover, track.title, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(V4Surface), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(controller.currentArtistLine, color = V4Muted, fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = controller::toggle) { Icon(if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null) }
        }
    }
}

@Composable
private fun V4NowPlaying(controller: PlayerController, animated: Boolean) {
    val context = LocalContext.current
    val track = controller.currentTrack ?: return
    var lyrics by remember(track.id) { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(720.dp).background(V4Background)) {
        V4AnimatedBackground(
            controller.currentRelease?.animatedCoverUrl.orEmpty(),
            controller.currentCover,
            animated,
            controller.isPlaying,
            AppSettings.huggingFaceToken(context),
            Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (lyrics) .74f else .54f)))
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lyrics) "Şarkı Sözleri" else "Şu An Çalıyor", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = { lyrics = !lyrics }) { Icon(if (lyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics, null) }
            }
            if (lyrics) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 20.dp)) {
                    item {
                        Text(track.lyrics.ifBlank { "Bu şarkı için söz bulunmuyor." }, fontSize = 25.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                        if (track.credits.isNotEmpty()) {
                            Spacer(Modifier.height(28.dp)); Text("Künye", color = V4Accent, fontWeight = FontWeight.Bold)
                            track.credits.forEach { Text("${it.role}: ${it.names.joinToString(", ")}", color = Color.White.copy(alpha = .72f)) }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                Text(track.title, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth())
                Text(controller.currentArtistLine, color = Color.White.copy(alpha = .75f), modifier = Modifier.fillMaxWidth())
                Slider(
                    value = controller.positionMs.toFloat().coerceAtLeast(0f),
                    onValueChange = { controller.positionMs = it.toLong() },
                    onValueChangeFinished = { controller.seekTo(controller.positionMs) },
                    valueRange = 0f..controller.durationMs.coerceAtLeast(1).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
                )
                Row(Modifier.fillMaxWidth()) { Text(formatMillis(controller.positionMs), fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text(formatMillis(controller.durationMs), fontSize = 11.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::previous) { Icon(Icons.Rounded.SkipPrevious, null, modifier = Modifier.size(34.dp)) }
                    FilledIconButton(onClick = controller::toggle, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Icon(if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = controller::next) { Icon(Icons.Rounded.SkipNext, null, modifier = Modifier.size(34.dp)) }
                    Box {
                        IconButton(onClick = { qualityMenu = true }) { Icon(Icons.Rounded.HighQuality, null) }
                        DropdownMenu(qualityMenu, { qualityMenu = false }) {
                            track.sources.sortedByDescending { qualityRank(it.kind) }.forEach { source ->
                                DropdownMenuItem(
                                    text = { Column { Text(source.label); Text(source.codec, color = V4Muted, fontSize = 11.sp) } },
                                    trailingIcon = { if (controller.currentSource?.id == source.id) Icon(Icons.Rounded.Check, null, tint = V4Accent) },
                                    onClick = { controller.setQuality(source.kind); qualityMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = controller::downloadCurrent) { Icon(Icons.Rounded.Download, null) }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun V4AnimatedBackground(
    videoUrl: String,
    coverUrl: String,
    enabled: Boolean,
    playing: Boolean,
    token: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val clean = remember(videoUrl) { videoUrl.substringBefore('#').trim() }
    var failed by remember(clean) { mutableStateOf(false) }
    Box(modifier.background(V4Background)) {
        AsyncImage(coverUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (enabled && clean.isNotBlank() && !failed) {
            val player = remember(clean, token) {
                val host = runCatching { android.net.Uri.parse(clean).host.orEmpty().lowercase() }.getOrDefault("")
                val hf = host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co")
                val factory = DefaultHttpDataSource.Factory()
                    .setUserAgent("AuroraMusic/0.4.0")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(if (hf && token.isNotBlank()) mapOf("Authorization" to "Bearer ${token.trim()}") else emptyMap())
                ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory)).build().apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    setMediaItem(MediaItem.fromUri(clean))
                    prepare()
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } }
                player.addListener(listener)
                onDispose { player.removeListener(listener); player.release() }
            }
            LaunchedEffect(player, playing) { if (playing) player.play() else player.pause() }
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V4Settings(controller: PlayerController, animatedEnabled: Boolean, changed: (Boolean) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(AppSettings.huggingFaceToken(context)) }
    var animated by remember(animatedEnabled) { mutableStateOf(animatedEnabled) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = V4Background,
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text("Ayarlar", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                token,
                { token = it },
                label = { Text("Hugging Face Read token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Hareketli kapaklar", fontWeight = FontWeight.Bold); Text("URL ile eklenen sessiz arka plan videoları", color = V4Muted, fontSize = 12.sp) }
                Switch(animated, { animated = it })
            }
            Text("Token aynı paket adı ve kalıcı Android imzasıyla yapılan güncellemelerde cihazda şifreli olarak korunur.", color = V4Muted, fontSize = 12.sp)
            Button(
                onClick = {
                    controller.setHuggingFaceToken(token)
                    AppSettings.setAnimatedCovers(context, animated)
                    changed(animated)
                    scope.launch { delay(400); dismiss() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Rounded.Settings, null); Text(" Güvenli Kaydet") }
            Spacer(Modifier.height(20.dp))
        }
    }
}
