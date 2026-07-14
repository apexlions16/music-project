package com.apexlions.music

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.Typography
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

private val AppBackground = Color(0xFF09090B)
private val SurfaceDark = Color(0xFF17171A)
private val SurfaceLight = Color(0xFF242428)
private val Accent = Color(0xFF9C5CFF)
private val Muted = Color(0xFFA5A5AD)
private val ErrorColor = Color(0xFFFF6B7D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuroraTheme { AuroraMusicApp() } }
    }
}

@Composable
private fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = AppBackground,
            surface = SurfaceDark,
            surfaceVariant = SurfaceLight,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Muted,
            error = ErrorColor,
        ),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

private enum class RootTab(val label: String) {
    HOME("Ana Sayfa"),
    RELEASES("Yayınlar"),
    ARTISTS("Sanatçılar"),
    SEARCH("Ara"),
}

private enum class ArtistSort(val label: String) {
    NEWEST("Yeni → Eski"),
    OLDEST("Eski → Yeni"),
    TITLE("A → Z"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuroraMusicApp() {
    val context = LocalContext.current
    val controller = remember { PlayerController(context) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf(RootTab.HOME) }
    var selectedReleaseId by remember { mutableStateOf<String?>(null) }
    var selectedArtistId by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var animatedCoversEnabled by remember { mutableStateOf(AppSettings.animatedCovers(context)) }

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
            val duration = controller.player.duration.takeIf { it > 0 }
                ?: controller.currentTrack?.durationSeconds?.times(1000L)
                ?: 0L
            controller.durationMs = duration
            delay(400)
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val goBack: () -> Unit = {
        selectedReleaseId = null
        selectedArtistId = null
    }
    BackHandler(selectedReleaseId != null || selectedArtistId != null) { goBack() }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(catalog?.brand?.name ?: "Aurora Music", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                selectedReleaseId != null -> "Yayın"
                                selectedArtistId != null -> "Sanatçı"
                                else -> selectedTab.label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                },
                navigationIcon = {
                    if (selectedReleaseId != null || selectedArtistId != null) {
                        IconButton(onClick = goBack) { Icon(Icons.Rounded.ArrowBack, "Geri") }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) { Icon(Icons.Rounded.Refresh, "Kataloğu yenile") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Ayarlar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground.copy(alpha = .97f)),
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
                            RootTab.HOME -> Icons.Rounded.Home
                            RootTab.RELEASES -> Icons.Rounded.Album
                            RootTab.ARTISTS -> Icons.Rounded.Groups
                            RootTab.SEARCH -> Icons.Rounded.Search
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab && selectedReleaseId == null && selectedArtistId == null,
                            onClick = {
                                selectedTab = tab
                                goBack()
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(tab.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Accent.copy(alpha = .18f),
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && catalog == null -> LoadingState()
                error != null && catalog == null -> ErrorState(error.orEmpty()) { refreshKey++ }
                catalog != null -> {
                    val data = catalog!!
                    when {
                        selectedReleaseId != null -> data.release(selectedReleaseId!!)?.let { release ->
                            ReleaseDetailScreen(
                                catalog = data,
                                release = release,
                                controller = controller,
                                onArtist = { artistId ->
                                    selectedArtistId = artistId
                                    selectedReleaseId = null
                                },
                            )
                        }
                        selectedArtistId != null -> data.artist(selectedArtistId!!)?.let { artist ->
                            ArtistDetailScreen(
                                catalog = data,
                                artist = artist,
                                onRelease = { selectedReleaseId = it },
                            )
                        }
                        selectedTab == RootTab.HOME -> HomeScreen(
                            catalog = data,
                            onRelease = { selectedReleaseId = it },
                            onArtist = { selectedArtistId = it },
                        )
                        selectedTab == RootTab.RELEASES -> ReleasesScreen(data) { selectedReleaseId = it }
                        selectedTab == RootTab.ARTISTS -> ArtistsScreen(data) { selectedArtistId = it }
                        else -> SearchScreen(
                            catalog = data,
                            onRelease = { selectedReleaseId = it },
                            onArtist = { selectedArtistId = it },
                        )
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
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .4f)) },
        ) {
            NowPlayingScreen(controller, animatedCoversEnabled)
        }
    }

    if (showSettings) {
        SettingsSheet(
            controller = controller,
            animatedCoversEnabled = animatedCoversEnabled,
            onAnimatedCoversChanged = { animatedCoversEnabled = it },
            onDismiss = { showSettings = false },
        )
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
private fun ErrorState(message: String, retry: () -> Unit) = Box(
    Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
) {
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
    val featured = catalog.featuredReleaseIds.mapNotNull { catalog.release(it) }.firstOrNull()
        ?: catalog.releases.maxByOrNull { it.releaseDate }
    val recent = catalog.releases.sortedByDescending { it.releaseDate }.take(6)
    val artists = catalog.artists.sortedBy { it.name }.take(5)

    LazyColumn(
        contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Şimdi Dinle", style = MaterialTheme.typography.headlineLarge)
            Text(catalog.brand.subtitle, color = Muted)
        }
        featured?.let { release -> item { HeroRelease(catalog, release) { onRelease(release.id) } } }
        item { SectionTitle("Yeni Çıkanlar") }
        items(recent, key = { it.id }) { release ->
            VerticalReleaseCard(catalog, release) { onRelease(release.id) }
        }
        if (artists.isNotEmpty()) item { SectionTitle("Sanatçılar") }
        items(artists, key = { it.id }) { artist ->
            VerticalArtistCard(artist) { onArtist(artist.id) }
        }
    }
}

@Composable
private fun HeroRelease(catalog: Catalog, release: Release, open: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = open),
    ) {
        AsyncImage(
            model = release.heroImage.ifBlank { release.cover },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .15f), Color.Black.copy(alpha = .94f))),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Text("ÖNE ÇIKAN ${releaseTypeLabel(release.type).uppercase()}", color = Accent, fontWeight = FontWeight.Bold)
            Text(release.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(catalog.releaseArtistLine(release), color = Color.White.copy(alpha = .82f), fontSize = 17.sp)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = open,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Dinle")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun VerticalReleaseCard(catalog: Catalog, release: Release, open: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SurfaceDark,
        modifier = Modifier.fillMaxWidth().clickable(onClick = open),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = release.cover,
                contentDescription = release.title,
                modifier = Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceLight),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(release.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(catalog.releaseArtistLine(release), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("${releaseTypeLabel(release.type)} • ${release.releaseDate.take(4)}", color = Accent, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun VerticalArtistCard(artist: Artist, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceDark).clickable(onClick = open).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artist.image,
            contentDescription = artist.name,
            modifier = Modifier.size(76.dp).clip(CircleShape).background(SurfaceLight),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.titleMedium)
            if (artist.bio.isNotBlank()) Text(artist.bio, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReleasesScreen(catalog: Catalog, onRelease: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Text("Tüm Yayınlar", style = MaterialTheme.typography.headlineLarge)
            Text("Single, EP ve albümler dikey listede", color = Muted)
        }
        items(catalog.releases.sortedByDescending { it.releaseDate }, key = { it.id }) { release ->
            VerticalReleaseCard(catalog, release) { onRelease(release.id) }
        }
    }
}

@Composable
private fun ArtistsScreen(catalog: Catalog, onArtist: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Sanatçılar", style = MaterialTheme.typography.headlineLarge)
            Text("Tüm sanatçılar alt alta", color = Muted)
        }
        items(catalog.artists.sortedBy { it.name }, key = { it.id }) { artist ->
            VerticalArtistCard(artist) { onArtist(artist.id) }
        }
    }
}

@Composable
private fun ArtistDetailScreen(catalog: Catalog, artist: Artist, onRelease: (String) -> Unit) {
    var showAll by remember(artist.id) { mutableStateOf(false) }
    var sort by remember(artist.id) { mutableStateOf(ArtistSort.NEWEST) }
    val releases = catalog.releases.filter { artist.id in (it.primaryArtistIds.ifEmpty { it.artistIds }) }
    val sorted = when (sort) {
        ArtistSort.NEWEST -> releases.sortedByDescending { it.releaseDate }
        ArtistSort.OLDEST -> releases.sortedBy { it.releaseDate }
        ArtistSort.TITLE -> releases.sortedBy { it.title.lowercase() }
    }
    val visible = if (showAll) sorted else sorted.take(3)

    LazyColumn(
        contentPadding = PaddingValues(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                AsyncImage(
                    model = artist.backgroundImage.ifBlank { artist.heroImage.ifBlank { artist.image } },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, AppBackground))))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text(artist.name, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    if (artist.bio.isNotBlank()) Text(artist.bio, color = Color.White.copy(alpha = .8f), maxLines = 4)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Yayınlar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${releases.size} yayın", color = Muted)
                }
                if (showAll && releases.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArtistSort.entries.forEach { option ->
                            FilterChip(
                                selected = sort == option,
                                onClick = { sort = option },
                                label = { Text(option.label, fontSize = 11.sp) },
                                leadingIcon = if (sort == option) ({ Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }) else null,
                            )
                        }
                    }
                }
            }
        }
        items(visible, key = { it.id }) { release ->
            Box(Modifier.padding(horizontal = 18.dp)) {
                VerticalReleaseCard(catalog, release) { onRelease(release.id) }
            }
        }
        if (releases.size > 3) {
            item {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                ) {
                    Icon(if (showAll) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (showAll) "Daha Az Göster" else "Tümünü Gör ve Sırala")
                }
            }
        }
    }
}

@Composable
private fun ReleaseDetailScreen(
    catalog: Catalog,
    release: Release,
    controller: PlayerController,
    onArtist: (String) -> Unit,
) {
    val tracks = catalog.releaseTracks(release)
    val qualities = tracks.flatMap { it.sources }.distinctBy { it.kind }.sortedByDescending { qualityRank(it.kind) }

    LazyColumn(
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 34.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = release.cover,
                    contentDescription = release.title,
                    modifier = Modifier.fillMaxWidth(.82f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(SurfaceLight),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(20.dp))
                Text(release.title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    catalog.releaseArtistLine(release),
                    color = Accent,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { release.primaryArtistIds.ifEmpty { release.artistIds }.firstOrNull()?.let(onArtist) },
                )
                Text("${releaseTypeLabel(release.type)} • ${release.releaseDate.take(4)}", color = Muted)
                if (qualities.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(qualities.joinToString("  •  ") { it.label }, color = Color.White.copy(alpha = .72f), fontSize = 11.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            tracks.firstOrNull()?.let {
                                controller.play(it, release, release.cover, tracks, catalog::trackArtistLine)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Text("Çal")
                    }
                    FilledTonalButton(
                        onClick = {
                            controller.setShuffle(true)
                            tracks.randomOrNull()?.let {
                                controller.play(it, release, release.cover, tracks, catalog::trackArtistLine)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Shuffle, null)
                        Text("Karıştır")
                    }
                }
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                catalog = catalog,
                track = track,
                number = index + 1,
                active = controller.currentTrack?.id == track.id,
                playing = controller.isPlaying,
            ) {
                controller.play(track, release, release.cover, tracks, catalog::trackArtistLine)
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
private fun TrackRow(
    catalog: Catalog,
    track: Track,
    number: Int,
    active: Boolean,
    playing: Boolean,
    click: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Icon(if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = Accent)
            } else {
                Text(number.toString(), color = Muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, color = if (active) Accent else Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                if (track.explicit) {
                    Spacer(Modifier.width(5.dp))
                    Text("E", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(catalog.trackArtistLine(track), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDuration(track.durationSeconds), color = Muted, fontSize = 13.sp)
    }
    HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(start = 34.dp))
}

@Composable
private fun SearchScreen(catalog: Catalog, onRelease: (String) -> Unit, onArtist: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val releases = catalog.releases.filter {
        it.title.contains(query, true) || catalog.releaseArtistLine(it).contains(query, true)
    }
    val artists = catalog.artists.filter { it.name.contains(query, true) }
    val tracks = catalog.tracks.filter {
        it.title.contains(query, true) || catalog.trackArtistLine(it).contains(query, true)
    }

    LazyColumn(
        contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Ara", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Şarkı, albüm veya sanatçı") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(18.dp),
            )
        }
        if (query.isNotBlank()) {
            if (artists.isNotEmpty()) item { SectionTitle("Sanatçılar") }
            items(artists, key = { it.id }) { artist -> VerticalArtistCard(artist) { onArtist(artist.id) } }
            if (releases.isNotEmpty()) item { SectionTitle("Yayınlar") }
            items(releases, key = { it.id }) { release -> VerticalReleaseCard(catalog, release) { onRelease(release.id) } }
            if (tracks.isNotEmpty()) item { SectionTitle("Şarkılar") }
            items(tracks, key = { it.id }) { track ->
                Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDark) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(track.title, fontWeight = FontWeight.SemiBold)
                        Text(catalog.trackArtistLine(track), color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(controller: PlayerController, onOpen: () -> Unit) {
    val track = controller.currentTrack ?: return
    Column {
        Row(
            Modifier.fillMaxWidth().height(70.dp).background(SurfaceLight.copy(alpha = .97f)).clickable(onClick = onOpen).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = controller.currentCover,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceDark),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(controller.currentArtistLine, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = controller::toggle) {
                Icon(if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = controller::next) { Icon(Icons.Rounded.SkipNext, null) }
        }
        val progress by animateFloatAsState(
            targetValue = if (controller.durationMs > 0) controller.positionMs.toFloat() / controller.durationMs else 0f,
            label = "mini-progress",
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Accent,
            trackColor = SurfaceLight,
        )
    }
}

@Composable
private fun NowPlayingScreen(controller: PlayerController, animatedCoversEnabled: Boolean) {
    val track = controller.currentTrack ?: return
    val context = LocalContext.current
    var lyricsMode by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    val sources = track.sources.sortedByDescending { qualityRank(it.kind) }

    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(.94f)
            .background(AppBackground),
    ) {
        AnimatedNowPlayingBackground(
            videoUrl = controller.currentRelease?.animatedCoverUrl.orEmpty(),
            coverUrl = controller.currentCover,
            enabled = animatedCoversEnabled,
            playing = controller.isPlaying,
            huggingFaceToken = AppSettings.huggingFaceToken(context),
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (lyricsMode) .70f else .50f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = .26f),
                        Color.Transparent,
                        Color.Black.copy(alpha = .78f),
                    ),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (lyricsMode) "Şarkı Sözleri" else "Şu An Çalıyor",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { lyricsMode = !lyricsMode }) {
                    Icon(if (lyricsMode) Icons.Rounded.Album else Icons.Rounded.Lyrics, null)
                }
            }

            controller.playbackError?.let { message ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ErrorColor.copy(alpha = .22f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(message, color = Color.White, modifier = Modifier.padding(14.dp))
                }
                Spacer(Modifier.height(10.dp))
            }

            if (lyricsMode) {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(vertical = 24.dp),
                ) {
                    item {
                        Text(
                            track.lyrics.ifBlank { "Bu şarkı için henüz söz eklenmemiş." },
                            fontSize = 27.sp,
                            lineHeight = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (track.lyrics.isBlank()) Color.White.copy(alpha = .65f) else Color.White,
                        )
                        if (track.credits.isNotEmpty()) {
                            Spacer(Modifier.height(32.dp))
                            Text("Künye", color = Accent, fontWeight = FontWeight.Bold)
                            track.credits.forEach {
                                Text("${it.role}: ${it.names.joinToString(", ")}", color = Color.White.copy(alpha = .72f))
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(track.title, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (track.explicit) {
                                Spacer(Modifier.width(7.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.White.copy(alpha = .88f),
                                ) {
                                    Text(
                                        "E",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(controller.currentArtistLine, color = Color.White.copy(alpha = .76f), fontSize = 16.sp, maxLines = 1)
                    }
                    IconButton(onClick = controller::downloadCurrent) {
                        Icon(Icons.Rounded.Download, "İndir", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = controller.positionMs.toFloat().coerceAtLeast(0f),
                    onValueChange = { controller.positionMs = it.toLong() },
                    onValueChangeFinished = { controller.seekTo(controller.positionMs) },
                    valueRange = 0f..controller.durationMs.coerceAtLeast(1).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = .28f),
                    ),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatMillis(controller.positionMs), color = Color.White.copy(alpha = .70f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatMillis(controller.durationMs), color = Color.White.copy(alpha = .70f), fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { controller.setShuffle(!controller.shuffle) }) {
                        Icon(Icons.Rounded.Shuffle, null, tint = if (controller.shuffle) Accent else Color.White)
                    }
                    IconButton(onClick = { controller.seekBy(-10_000) }) {
                        Icon(Icons.Rounded.FastRewind, "10 saniye geri")
                    }
                    IconButton(onClick = controller::previous) {
                        Icon(Icons.Rounded.SkipPrevious, null, modifier = Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = controller::toggle,
                        modifier = Modifier.size(70.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) {
                        Icon(
                            if (controller.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            null,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    IconButton(onClick = controller::next) {
                        Icon(Icons.Rounded.SkipNext, null, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { controller.seekBy(10_000) }) {
                        Icon(Icons.Rounded.FastForward, "10 saniye ileri")
                    }
                    Box {
                        IconButton(onClick = { qualityMenu = true }) {
                            Icon(Icons.Rounded.HighQuality, null, tint = Color.White)
                        }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            sources.forEach { source ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(source.label)
                                            Text(source.codec, color = Muted, fontSize = 12.sp)
                                        }
                                    },
                                    trailingIcon = {
                                        if (controller.currentSource?.id == source.id) {
                                            Icon(Icons.Rounded.Check, null, tint = Accent)
                                        }
                                    },
                                    onClick = {
                                        controller.setQuality(source.kind)
                                        qualityMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun AnimatedNowPlayingBackground(
    videoUrl: String,
    coverUrl: String,
    enabled: Boolean,
    playing: Boolean,
    huggingFaceToken: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cleanVideoUrl = remember(videoUrl) { videoUrl.substringBefore("#").trim() }
    var videoFailed by remember(cleanVideoUrl) { mutableStateOf(false) }
    val showVideo = enabled && cleanVideoUrl.isNotBlank() && !videoFailed

    Box(modifier = modifier.background(AppBackground)) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        if (showVideo) {
            val videoPlayer = remember(cleanVideoUrl, huggingFaceToken) {
                val host = runCatching {
                    android.net.Uri.parse(cleanVideoUrl).host.orEmpty().lowercase()
                }.getOrDefault("")
                val isHuggingFace = host == "huggingface.co" ||
                    host.endsWith(".huggingface.co") ||
                    host == "hf.co" ||
                    host.endsWith(".hf.co")
                val headers = if (isHuggingFace && huggingFaceToken.isNotBlank()) {
                    mapOf("Authorization" to "Bearer ${huggingFaceToken.trim()}")
                } else {
                    emptyMap()
                }
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("AuroraMusic/0.3.2")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers)
                val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)

                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                    .apply {
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ONE
                        setMediaItem(MediaItem.fromUri(cleanVideoUrl))
                        prepare()
                    }
            }

            DisposableEffect(videoPlayer) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        videoFailed = true
                    }
                }
                videoPlayer.addListener(listener)
                onDispose {
                    videoPlayer.removeListener(listener)
                    videoPlayer.release()
                }
            }

            LaunchedEffect(videoPlayer, playing) {
                if (playing) videoPlayer.play() else videoPlayer.pause()
            }

            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        player = videoPlayer
                    }
                },
                update = { it.player = videoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    controller: PlayerController,
    animatedCoversEnabled: Boolean,
    onAnimatedCoversChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(AppSettings.huggingFaceToken(context)) }
    var animated by remember(animatedCoversEnabled) { mutableStateOf(animatedCoversEnabled) }
    var saved by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppBackground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .4f)) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 42.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Ayarlar", style = MaterialTheme.typography.headlineMedium)
                Text("Yönetim paneli mobil uygulamadan tamamen kaldırılmıştır.", color = Muted)
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = SurfaceDark) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Hugging Face Gated Erişim", fontWeight = FontWeight.Bold)
                        Text(
                            "Yalnızca hcywashere/m-project deposunu okuyabilen fine-grained Read token girin. Token cihazda şifreli saklanır.",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; saved = false },
                            label = { Text("hf_… Read token") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = SurfaceDark) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hareketli kapaklar", fontWeight = FontWeight.Bold)
                            Text("Desteklenen yayınlarda animasyonu etkinleştirir.", color = Muted, fontSize = 13.sp)
                        }
                        Switch(checked = animated, onCheckedChange = { animated = it; saved = false })
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        controller.setHuggingFaceToken(token)
                        AppSettings.setAnimatedCovers(context, animated)
                        onAnimatedCoversChanged(animated)
                        saved = true
                        scope.launch {
                            delay(700)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saved) Icon(Icons.Rounded.Check, null) else Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (saved) "Kaydedildi" else "Güvenli Kaydet")
                }
            }
        }
    }
}
