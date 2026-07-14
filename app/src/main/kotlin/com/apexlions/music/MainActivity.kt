package com.apexlions.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val AppBackground = Color(0xFF09090B)
private val SurfaceDark = Color(0xFF17171A)
private val SurfaceLight = Color(0xFF242428)
private val Accent = Color(0xFFFF375F)
private val Muted = Color(0xFFA5A5AD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MusicProjectTheme { MusicProjectApp() } }
    }
}

@Composable
private fun MusicProjectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = AppBackground,
            surface = SurfaceDark,
            surfaceVariant = SurfaceLight,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Muted
        ),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        ),
        content = content
    )
}

private enum class RootTab(val label: String) { ANA_SAYFA("Ana Sayfa"), GOZ_AT("Göz At"), SANATCILAR("Sanatçılar"), ARA("Ara") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicProjectApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { PlayerController(context) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf(RootTab.ANA_SAYFA) }
    var selectedReleaseId by remember { mutableStateOf<String?>(null) }
    var selectedArtistId by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        runCatching { CatalogRepository.load(context, forceRemote = refreshKey > 0) }
            .onSuccess { catalog = it }
            .onFailure { error = it.message ?: "Katalog yüklenemedi." }
        loading = false
    }
    LaunchedEffect(controller.player) {
        while (true) {
            controller.positionMs = controller.player.currentPosition.coerceAtLeast(0)
            controller.durationMs = controller.player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    val goHome: () -> Unit = {
        selectedReleaseId = null
        selectedArtistId = null
        showAdmin = false
    }
    BackHandler(selectedReleaseId != null || selectedArtistId != null || showAdmin) { goHome() }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(catalog?.brand?.name ?: "Müzik Projesi", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                showAdmin -> "Yönetim Paneli"
                                selectedReleaseId != null -> "Yayın"
                                selectedArtistId != null -> "Sanatçı"
                                else -> selectedTab.label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted
                        )
                    }
                },
                navigationIcon = {
                    if (selectedReleaseId != null || selectedArtistId != null || showAdmin) {
                        IconButton(onClick = goHome) { Icon(Icons.Rounded.ArrowBack, "Geri") }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) { Icon(Icons.Rounded.Refresh, "Kataloğu yenile") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Menü") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Yönetim Paneli") },
                                leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, null) },
                                onClick = { menuOpen = false; showAdmin = true; selectedReleaseId = null; selectedArtistId = null }
                            )
                            DropdownMenuItem(
                                text = { Text("GitHub deposunu aç") },
                                leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/apexlions16/music-project")))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground.copy(alpha = .96f))
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(AppBackground.copy(alpha = .98f))) {
                AnimatedVisibility(controller.currentTrack != null) {
                    MiniPlayer(controller = controller, onOpen = { showPlayer = true })
                }
                NavigationBar(containerColor = SurfaceDark.copy(alpha = .98f), tonalElevation = 0.dp) {
                    RootTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            RootTab.ANA_SAYFA -> Icons.Rounded.Home
                            RootTab.GOZ_AT -> Icons.Rounded.Album
                            RootTab.SANATCILAR -> Icons.Rounded.Groups
                            RootTab.ARA -> Icons.Rounded.Search
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab && selectedReleaseId == null && selectedArtistId == null && !showAdmin,
                            onClick = { selectedTab = tab; goHome() },
                            icon = { Icon(icon, null) },
                            label = { Text(tab.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Accent.copy(alpha = .18f), selectedIconColor = Accent, selectedTextColor = Accent)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && catalog == null -> LoadingState()
                error != null && catalog == null -> ErrorState(error.orEmpty()) { refreshKey++ }
                catalog != null -> {
                    val data = catalog!!
                    when {
                        showAdmin -> AdminScreen(data)
                        selectedReleaseId != null -> data.release(selectedReleaseId!!)?.let { release ->
                            ReleaseDetailScreen(data, release, controller, onArtist = { selectedArtistId = it; selectedReleaseId = null })
                        }
                        selectedArtistId != null -> data.artist(selectedArtistId!!)?.let { artist ->
                            ArtistDetailScreen(data, artist, onRelease = { selectedReleaseId = it; selectedArtistId = null })
                        }
                        selectedTab == RootTab.ANA_SAYFA -> HomeScreen(data, onRelease = { selectedReleaseId = it }, onArtist = { selectedArtistId = it })
                        selectedTab == RootTab.GOZ_AT -> BrowseScreen(data, onRelease = { selectedReleaseId = it })
                        selectedTab == RootTab.SANATCILAR -> ArtistsScreen(data, onArtist = { selectedArtistId = it })
                        else -> SearchScreen(data, onRelease = { selectedReleaseId = it }, onArtist = { selectedArtistId = it })
                    }
                }
            }
        }
    }

    if (showPlayer && controller.currentTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { showPlayer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppBackground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .4f)) }
        ) {
            NowPlayingScreen(controller)
        }
    }
}

@Composable
private fun LoadingState() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(16.dp))
        Text("Katalog yükleniyor…", color = Muted)
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, tint = Accent, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(12.dp))
        Text("Katalog açılamadı", style = MaterialTheme.typography.titleLarge)
        Text(message, color = Muted)
        Spacer(Modifier.height(18.dp))
        Button(onClick = retry) { Text("Tekrar Dene") }
    }
}

@Composable
private fun HomeScreen(catalog: Catalog, onRelease: (String) -> Unit, onArtist: (String) -> Unit) {
    val featured = catalog.featuredReleaseIds.mapNotNull { catalog.release(it) }.ifEmpty { catalog.releases.take(1) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text("Şimdi Dinle", style = MaterialTheme.typography.headlineLarge)
                Text(catalog.brand.subtitle, color = Muted)
            }
        }
        featured.firstOrNull()?.let { release -> item { HeroRelease(catalog, release) { onRelease(release.id) } } }
        item { SectionTitle("Yeni Çıkanlar", "Tümünü Gör") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(catalog.releases.sortedByDescending { it.releaseDate }) { ReleaseCard(catalog, it) { onRelease(it.id) } }
            }
        }
        item { SectionTitle("Sanatçılar") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(catalog.artists) { ArtistCard(it) { onArtist(it.id) } }
            }
        }
    }
}

@Composable
private fun HeroRelease(catalog: Catalog, release: Release, open: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(430.dp).padding(horizontal = 18.dp).clip(RoundedCornerShape(28.dp)).clickable(onClick = open)
    ) {
        AsyncImage(release.heroImage.ifBlank { release.cover }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .15f), Color.Black.copy(alpha = .92f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Text("ÖNE ÇIKAN ${releaseTypeLabel(release.type).uppercase()}", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(release.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(catalog.artistNames(release.artistIds), color = Color.White.copy(alpha = .8f), fontSize = 17.sp)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = open, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Dinle")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.let { Text(it, color = Accent, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun ReleaseCard(catalog: Catalog, release: Release, open: () -> Unit) {
    Column(Modifier.width(170.dp).clickable(onClick = open)) {
        AsyncImage(release.cover, release.title, Modifier.size(170.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceLight), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(10.dp))
        Text(release.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${releaseTypeLabel(release.type)} • ${catalog.artistNames(release.artistIds)}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistCard(artist: Artist, open: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = open), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(artist.image, artist.name, Modifier.size(128.dp).clip(CircleShape).background(SurfaceLight), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(9.dp)); Text(artist.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun BrowseScreen(catalog: Catalog, onRelease: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Katalog", style = MaterialTheme.typography.headlineLarge); Text("Albüm, EP ve single yayınları", color = Muted) }
        items(catalog.releases.sortedByDescending { it.releaseDate }) { release ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceDark).clickable { onRelease(release.id) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(release.cover, null, Modifier.size(82.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(release.title, style = MaterialTheme.typography.titleMedium)
                    Text(catalog.artistNames(release.artistIds), color = Muted)
                    Text("${releaseTypeLabel(release.type)} • ${release.releaseDate.take(4)}", color = Accent, fontSize = 12.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
    }
}

@Composable
private fun ArtistsScreen(catalog: Catalog, onArtist: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Sanatçılar", style = MaterialTheme.typography.headlineLarge) }
        items(catalog.artists) { artist ->
            Row(Modifier.fillMaxWidth().clickable { onArtist(artist.id) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(artist.image, null, Modifier.size(70.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(15.dp)); Column(Modifier.weight(1f)) { Text(artist.name, style = MaterialTheme.typography.titleMedium); Text(artist.bio, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
        }
    }
}

@Composable
private fun SearchScreen(catalog: Catalog, onRelease: (String) -> Unit, onArtist: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val releases = catalog.releases.filter { it.title.contains(query, true) || catalog.artistNames(it.artistIds).contains(query, true) }
    val artists = catalog.artists.filter { it.name.contains(query, true) }
    val tracks = catalog.tracks.filter { it.title.contains(query, true) || catalog.artistNames(it.artistIds).contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ara", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Şarkı, albüm veya sanatçı") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(18.dp)
            )
        }
        if (query.isNotBlank()) {
            if (artists.isNotEmpty()) item { Text("Sanatçılar", style = MaterialTheme.typography.titleLarge) }
            items(artists) { artist -> ListResult(artist.name, "Sanatçı", artist.image) { onArtist(artist.id) } }
            if (releases.isNotEmpty()) item { Text("Yayınlar", style = MaterialTheme.typography.titleLarge) }
            items(releases) { release -> ListResult(release.title, "${releaseTypeLabel(release.type)} • ${catalog.artistNames(release.artistIds)}", release.cover) { onRelease(release.id) } }
            if (tracks.isNotEmpty()) item { Text("Şarkılar", style = MaterialTheme.typography.titleLarge) }
            items(tracks) { track -> ListResult(track.title, catalog.artistNames(track.artistIds), catalog.releases.firstOrNull { row -> row.tracks.any { it.trackId == track.id } }?.cover.orEmpty(), null) }
        }
    }
}

@Composable
private fun ListResult(title: String, subtitle: String, image: String, click: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(image, null, Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceLight), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, maxLines = 1) }
    }
}

@Composable
private fun ReleaseDetailScreen(catalog: Catalog, release: Release, controller: PlayerController, onArtist: (String) -> Unit) {
    val tracks = catalog.releaseTracks(release)
    val qualities = tracks.flatMap { it.sources }.distinctBy { it.kind }.sortedByDescending { qualityRank(it.kind) }
    LazyColumn(contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 34.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(release.cover, release.title, Modifier.fillMaxWidth(.82f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(SurfaceLight), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(20.dp)); Text(release.title, style = MaterialTheme.typography.headlineMedium)
                Text(catalog.artistNames(release.artistIds), color = Accent, fontSize = 18.sp, modifier = Modifier.clickable { release.artistIds.firstOrNull()?.let(onArtist) })
                Text("${releaseTypeLabel(release.type)} • ${release.releaseDate.take(4)}", color = Muted)
                Spacer(Modifier.height(12.dp)); QualityBadges(qualities)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { tracks.firstOrNull()?.let { controller.play(it, release, release.cover, tracks) } }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Text("Çal") }
                    FilledTonalButton(onClick = { controller.setShuffle(true); tracks.randomOrNull()?.let { controller.play(it, release, release.cover, tracks) } }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Text("Karıştır") }
                }
            }
        }
        itemsIndexed(tracks) { index, track ->
            TrackRow(catalog, track, index + 1, controller.currentTrack?.id == track.id, controller.isPlaying) {
                controller.play(track, release, release.cover, tracks)
            }
        }
        if (release.description.isNotBlank()) item { Text(release.description, color = Muted, lineHeight = 21.sp) }
        item {
            Column {
                if (release.label.isNotBlank()) Text(release.label, color = Muted, fontSize = 12.sp)
                if (release.copyright.isNotBlank()) Text(release.copyright, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TrackRow(catalog: Catalog, track: Track, number: Int, active: Boolean, playing: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            if (active) Icon(if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = Accent) else Text(number.toString(), color = Muted)
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = if (active) Accent else Color.White, fontWeight = FontWeight.Medium)
            Text(catalog.artistNames(track.artistIds), color = Muted, fontSize = 13.sp)
        }
        Text(formatDuration(track.durationSeconds), color = Muted, fontSize = 13.sp)
        Icon(Icons.Rounded.MoreHoriz, null, tint = Muted, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(start = 32.dp))
}

@Composable
private fun QualityBadges(sources: List<AudioSource>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(sources) { source ->
            Surface(shape = RoundedCornerShape(7.dp), color = Color.White.copy(alpha = .09f), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .15f))) {
                Text(source.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun ArtistDetailScreen(catalog: Catalog, artist: Artist, onRelease: (String) -> Unit) {
    val releases = catalog.releases.filter { artist.id in it.artistIds }
    LazyColumn(contentPadding = PaddingValues(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                AsyncImage(artist.heroImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, AppBackground))))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text(artist.name, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    Text(artist.bio, color = Color.White.copy(alpha = .78f), maxLines = 3)
                }
            }
        }
        item { SectionTitle("Yayınlar") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(releases) { ReleaseCard(catalog, it) { onRelease(it.id) } }
            }
        }
    }
}

@Composable
private fun MiniPlayer(controller: PlayerController, onOpen: () -> Unit) {
    val track = controller.currentTrack ?: return
    Column {
        Row(
            Modifier.fillMaxWidth().height(68.dp).background(SurfaceLight.copy(alpha = .96f)).clickable(onClick = onOpen).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(controller.currentCover, null, Modifier.size(50.dp).clip(RoundedCornerShape(9.dp)).background(SurfaceDark), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(controller.currentSource?.label.orEmpty(), color = Muted, fontSize = 12.sp) }
            IconButton(onClick = { controller.toggle() }) { Icon(if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { controller.next() }) { Icon(Icons.Rounded.SkipNext, null) }
        }
        val progress by animateFloatAsState(if (controller.durationMs > 0) controller.positionMs.toFloat() / controller.durationMs else 0f, label = "mini-progress")
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = SurfaceLight)
    }
}

@Composable
private fun NowPlayingScreen(controller: PlayerController) {
    val track = controller.currentTrack ?: return
    var lyricsMode by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    val sources = track.sources.sortedByDescending { qualityRank(it.kind) }
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(.94f).background(Brush.verticalGradient(listOf(SurfaceLight, AppBackground))).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (lyricsMode) "Şarkı Sözleri" else "Şu An Çalıyor", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { lyricsMode = !lyricsMode }) { Icon(if (lyricsMode) Icons.Rounded.Album else Icons.Rounded.Lyrics, null) }
        }
        if (lyricsMode) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 24.dp)) {
                item {
                    Text(track.lyrics.ifBlank { "Bu şarkı için henüz söz eklenmemiş." }, fontSize = 28.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, color = if (track.lyrics.isBlank()) Muted else Color.White)
                    if (track.credits.isNotEmpty()) {
                        Spacer(Modifier.height(32.dp)); Text("Künye", color = Accent, fontWeight = FontWeight.Bold)
                        track.credits.forEach { Text("${it.role}: ${it.names.joinToString(", ")}", color = Muted) }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            AsyncImage(controller.currentCover, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(SurfaceDark), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(controller.currentRelease?.title.orEmpty(), color = Muted, fontSize = 16.sp, maxLines = 1)
                }
                IconButton(onClick = { controller.downloadCurrent() }) { Icon(Icons.Rounded.Download, "İndir", tint = Accent) }
            }
            Slider(
                value = controller.positionMs.toFloat().coerceAtLeast(0f),
                onValueChange = { controller.positionMs = it.toLong() },
                onValueChangeFinished = { controller.seekTo(controller.positionMs) },
                valueRange = 0f..controller.durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .2f))
            )
            Row(Modifier.fillMaxWidth()) { Text(formatMillis(controller.positionMs), color = Muted, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("-${formatMillis((controller.durationMs - controller.positionMs).coerceAtLeast(0))}", color = Muted, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.setShuffle(!controller.shuffle) }) { Icon(Icons.Rounded.Shuffle, null, tint = if (controller.shuffle) Accent else Color.White) }
                IconButton(onClick = { controller.previous() }, modifier = Modifier.size(62.dp)) { Icon(Icons.Rounded.SkipPrevious, null, modifier = Modifier.size(42.dp)) }
                FilledIconButton(onClick = { controller.toggle() }, modifier = Modifier.size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(45.dp))
                }
                IconButton(onClick = { controller.next() }, modifier = Modifier.size(62.dp)) { Icon(Icons.Rounded.SkipNext, null, modifier = Modifier.size(42.dp)) }
                Box {
                    IconButton(onClick = { qualityMenu = true }) { Icon(Icons.Rounded.HighQuality, null, tint = Accent) }
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        sources.forEach { source ->
                            DropdownMenuItem(
                                text = { Column { Text(source.label); Text(source.codec, color = Muted, fontSize = 12.sp) } },
                                trailingIcon = { if (controller.currentSource?.id == source.id) Icon(Icons.Rounded.Check, null, tint = Accent) },
                                onClick = { controller.setQuality(source.kind); qualityMenu = false }
                            )
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .07f), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Accent); Spacer(Modifier.width(10.dp))
                    Column { Text(controller.currentSource?.label.orEmpty(), fontWeight = FontWeight.SemiBold); Text(controller.currentSource?.codec.orEmpty(), color = Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun AdminScreen(catalog: Catalog) {
    val context = LocalContext.current
    val actions = listOf(
        Triple("Katalogu Düzenle", "Sanatçı, şarkı, albüm ve EP kayıtlarını GitHub üzerinde düzenle.", "https://github.com/apexlions16/music-project/edit/main/catalog/catalog.json"),
        Triple("Katalog Dosyasını Gör", "Uygulamanın canlı okuduğu JSON verisini aç.", CatalogRepository.RAW_CATALOG_URL),
        Triple("Yeni Ses Bağlantısı Ekle", "Hugging Face dosya bağlantısını şarkının sources alanına ekle.", "https://github.com/apexlions16/music-project/blob/main/README.md"),
        Triple("Şema ve Kurallar", "Single, EP, albüm ve kalite alanlarının yapısını incele.", "https://github.com/apexlions16/music-project/blob/main/catalog/schema.json")
    )
    LazyColumn(contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = Accent.copy(alpha = .13f), border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = .35f))) {
                Column(Modifier.padding(20.dp)) {
                    Icon(Icons.Rounded.AdminPanelSettings, null, tint = Accent, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp)); Text("GitHub Katalog Yönetimi", style = MaterialTheme.typography.titleLarge)
                    Text("Kullanıcı hesabı yoktur. Katalog doğrudan bu repoda tutulur ve uygulama her açılışta güncel sürümü kontrol eder.", color = Muted)
                    Spacer(Modifier.height(8.dp)); Text("${catalog.artists.size} sanatçı • ${catalog.releases.size} yayın • ${catalog.tracks.size} şarkı", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(actions) { (title, description, url) ->
            Surface(
                shape = RoundedCornerShape(18.dp), color = SurfaceDark,
                modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, color = Muted, fontSize = 13.sp) }
                    Icon(Icons.Rounded.OpenInNew, null, tint = Accent)
                }
            }
        }
        item {
            Text("Güvenlik", color = Accent, fontWeight = FontWeight.Bold)
            Text("GitHub veya Hugging Face erişim anahtarları APK içine gömülmez. Yönetim işlemleri GitHub oturumun üzerinden yapılır.", color = Muted)
        }
    }
}
