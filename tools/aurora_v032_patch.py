from __future__ import annotations

import re
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: beklenen 1 eşleşme, bulunan {count}")
    return text.replace(old, new, 1)


# Studio Mobile küçük derleme düzeltmeleri
screens_path = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioScreens.kt")
screens = screens_path.read_text(encoding="utf-8")
if "ColumnScope" not in screens:
    screens = screens.replace(
        "import androidx.compose.foundation.layout.Column\n",
        "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.ColumnScope\n",
        1,
    )
if "import androidx.compose.foundation.lazy.items\n" not in screens:
    screens = screens.replace(
        "import androidx.compose.foundation.lazy.LazyColumn\n",
        "import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\n",
        1,
    )
screens = screens.replace(
    "content: @Composable Column.() -> Unit",
    "content: @Composable ColumnScope.() -> Unit",
)
screens_path.write_text(screens, encoding="utf-8")


# Android müzik uygulaması v0.3.2
build_path = Path("app/build.gradle.kts")
build = build_path.read_text(encoding="utf-8")
build = build.replace('versionCode = 4', 'versionCode = 5')
build = build.replace('versionName = "0.3.1"', 'versionName = "0.3.2"')
build_path.write_text(build, encoding="utf-8")

main_path = Path("app/src/main/kotlin/com/apexlions/music/MainActivity.kt")
main = main_path.read_text(encoding="utf-8")

new_player = r'''@Composable
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
}'''

pattern = re.compile(
    r"@Composable\nprivate fun NowPlayingScreen\(controller: PlayerController, animatedCoversEnabled: Boolean\) \{.*?\n\}\n\n\n@Composable\nprivate fun AnimatedNowPlayingArtwork\(.*?\n\}\n\n@OptIn\(ExperimentalMaterial3Api::class\)",
    re.S,
)
if not pattern.search(main):
    raise RuntimeError("Android NowPlayingScreen bloğu bulunamadı")
main = pattern.sub(new_player + "\n\n@OptIn(ExperimentalMaterial3Api::class)", main, count=1)
main_path.write_text(main, encoding="utf-8")


# PC Studio v0.2.3: serbest feat isimleri, explicit ve görünmez konsol ilerleme düzeltmesi
pc_path = Path("pc/AuroraStudio.py")
pc = pc_path.read_text(encoding="utf-8")
if 'HF_HUB_DISABLE_PROGRESS_BARS' not in pc:
    pc = replace_once(
        pc,
        'os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")',
        'os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")\nos.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")',
        "HF progress bar",
    )
pc = pc.replace('APP_VERSION = "0.2.2"', 'APP_VERSION = "0.2.3"')

pc = replace_once(
    pc,
    '    featured_artist_ids: list[str] = field(default_factory=list)\n    spotify_id: str = ""',
    '    featured_artist_ids: list[str] = field(default_factory=list)\n    featured_artist_names: list[str] = field(default_factory=list)\n    spotify_id: str = ""',
    "ImportTrack featured names",
)

pc = replace_once(
    pc,
    '        self.featured_artists = QLineEdit(", ".join(track.featured_artist_ids))\n        self.primary_artists.setPlaceholderText("artist_id, artist_id")',
    '        self.featured_artists = QLineEdit(", ".join(track.featured_artist_ids))\n        self.featured_artist_names = QLineEdit(", ".join(track.featured_artist_names))\n        self.explicit = QCheckBox("Explicit içerik • uygulamada E göster")\n        self.explicit.setChecked(track.explicit)\n        self.primary_artists.setPlaceholderText("artist_id, artist_id")',
    "LyricsDialog widgets",
)

pc = replace_once(
    pc,
    '        self.featured_artists.setPlaceholderText("feat artist_id, feat artist_id")\n        artists_form.addRow("Ana sanatçı ID\'leri", self.primary_artists)\n        artists_form.addRow("Feat sanatçı ID\'leri", self.featured_artists)',
    '        self.featured_artists.setPlaceholderText("feat artist_id, feat artist_id")\n        self.featured_artist_names.setPlaceholderText("Profil gerektirmeyen feat isimleri, virgülle")\n        artists_form.addRow("Ana sanatçı ID\'leri", self.primary_artists)\n        artists_form.addRow("Feat sanatçı ID\'leri", self.featured_artists)\n        artists_form.addRow("Serbest feat isimleri", self.featured_artist_names)\n        artists_form.addRow("", self.explicit)',
    "LyricsDialog rows",
)

pc = replace_once(
    pc,
    '            self.track.featured_artist_ids = [value for value in ordered_unique([value.strip() for value in self.featured_artists.text().split(",") if value.strip()]) if value not in self.track.primary_artist_ids]\n            self.track.lyrics = self.plain.toPlainText()',
    '            self.track.featured_artist_ids = [value for value in ordered_unique([value.strip() for value in self.featured_artists.text().split(",") if value.strip()]) if value not in self.track.primary_artist_ids]\n            self.track.featured_artist_names = ordered_unique([value.strip() for value in self.featured_artist_names.text().split(",") if value.strip()])\n            self.track.explicit = self.explicit.isChecked()\n            self.track.lyrics = self.plain.toPlainText()',
    "LyricsDialog accept",
)

pc = replace_once(
    pc,
    '        self.track_featured_artists = QLineEdit()\n        self.track_duration = QSpinBox(); self.track_duration.setRange(0, 60 * 60 * 10)',
    '        self.track_featured_artists = QLineEdit()\n        self.track_featured_names = QLineEdit()\n        self.track_explicit = QCheckBox("Explicit • E rozeti")\n        self.track_duration = QSpinBox(); self.track_duration.setRange(0, 60 * 60 * 10)',
    "track page widgets",
)

pc = replace_once(
    pc,
    '        form.addRow("Feat sanatçı ID\'leri (virgül)", self.track_featured_artists)\n        form.addRow("Süre (saniye)", self.track_duration)',
    '        form.addRow("Feat sanatçı ID\'leri (virgül)", self.track_featured_artists)\n        form.addRow("Serbest feat isimleri (virgül)", self.track_featured_names)\n        form.addRow("", self.track_explicit)\n        form.addRow("Süre (saniye)", self.track_duration)',
    "track page rows",
)

pc = replace_once(
    pc,
    '        self.track_featured_artists.setText(", ".join(featured_ids))\n        self.track_duration.setValue(int(track.get("durationSeconds", 0)))',
    '        self.track_featured_artists.setText(", ".join(featured_ids))\n        self.track_featured_names.setText(", ".join(track.get("featuredArtistNames", [])))\n        self.track_explicit.setChecked(bool(track.get("explicit", False)))\n        self.track_duration.setValue(int(track.get("durationSeconds", 0)))',
    "load track free feat",
)

pc = replace_once(
    pc,
    '                "featuredArtistIds": [x for x in ordered_unique([x.strip() for x in self.track_featured_artists.text().split(",") if x.strip()]) if x not in [y.strip() for y in self.track_artists.text().split(",") if y.strip()]],\n                "artistIds": ordered_unique([x.strip() for x in self.track_artists.text().split(",") if x.strip()] + [x.strip() for x in self.track_featured_artists.text().split(",") if x.strip()]),',
    '                "featuredArtistIds": [x for x in ordered_unique([x.strip() for x in self.track_featured_artists.text().split(",") if x.strip()]) if x not in [y.strip() for y in self.track_artists.text().split(",") if y.strip()]],\n                "featuredArtistNames": ordered_unique([x.strip() for x in self.track_featured_names.text().split(",") if x.strip()]),\n                "artistIds": ordered_unique([x.strip() for x in self.track_artists.text().split(",") if x.strip()] + [x.strip() for x in self.track_featured_artists.text().split(",") if x.strip()]),',
    "save track free feat",
)

pc = replace_once(
    pc,
    '                "durationSeconds": self.track_duration.value(),\n                "isrc": self.track_isrc.text().strip(),',
    '                "durationSeconds": self.track_duration.value(),\n                "explicit": self.track_explicit.isChecked(),\n                "isrc": self.track_isrc.text().strip(),',
    "save track explicit",
)

pc = replace_once(
    pc,
    '                ", ".join(self.artist_name_for_id(value) for value in track.featured_artist_ids) or "—",',
    '                ", ".join([self.artist_name_for_id(value) for value in track.featured_artist_ids] + track.featured_artist_names) or "—",',
    "import table feat names",
)

pc = replace_once(
    pc,
    '                        "featuredArtistIds": featured_ids,\n                        "durationSeconds": info["duration"] or row.duration_seconds,',
    '                        "featuredArtistIds": featured_ids,\n                        "featuredArtistNames": ordered_unique(row.featured_artist_names),\n                        "durationSeconds": info["duration"] or row.duration_seconds,',
    "published track free feat",
)

pc = pc.replace('snapshot["schemaVersion"] = max(3, int(snapshot.get("schemaVersion", 1)))', 'snapshot["schemaVersion"] = max(4, int(snapshot.get("schemaVersion", 1)))')
pc_path.write_text(pc, encoding="utf-8")


# Katalog şeması v4
schema_path = Path("catalog/schema.json")
schema = schema_path.read_text(encoding="utf-8")
schema = schema.replace('"title": "Aurora Music Catalog v3"', '"title": "Aurora Music Catalog v4"')
schema = schema.replace('"schemaVersion": { "type": "integer", "minimum": 3 }', '"schemaVersion": { "type": "integer", "minimum": 3 }')
if '"featuredArtistNames"' not in schema:
    schema = schema.replace(
        '          "featuredArtistIds": { "type": "array", "items": { "type": "string" } },',
        '          "featuredArtistIds": { "type": "array", "items": { "type": "string" } },\n          "featuredArtistNames": { "type": "array", "items": { "type": "string" } },',
        1,
    )
schema_path.write_text(schema, encoding="utf-8")

print("Aurora v0.3.2 / Studio v0.2.3 patch applied")
