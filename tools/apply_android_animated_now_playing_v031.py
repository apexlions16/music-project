from __future__ import annotations

from pathlib import Path

MAIN = Path("app/src/main/kotlin/com/apexlions/music/MainActivity.kt")
GRADLE = Path("app/build.gradle.kts")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Beklenen bölüm bulunamadı: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"Beklenen bölüm tekil değil: {label} ({text.count(old)})")
    return text.replace(old, new, 1)


main = MAIN.read_text(encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, 'versionCode = 3', 'versionCode = 4', 'versionCode')
gradle = replace_once(gradle, 'versionName = "0.3.0"', 'versionName = "0.3.1"', 'versionName')
gradle = replace_once(
    gradle,
    '    implementation("androidx.media3:media3-exoplayer:1.10.1")\n',
    '    implementation("androidx.media3:media3-exoplayer:1.10.1")\n'
    '    implementation("androidx.media3:media3-ui:1.10.1")\n',
    'Media3 UI bağımlılığı',
)

main = replace_once(
    main,
    'import android.os.Bundle\n',
    'import android.os.Bundle\nimport android.view.ViewGroup\n',
    'Android ViewGroup importu',
)
main = replace_once(
    main,
    'import androidx.compose.ui.unit.sp\n',
    'import androidx.compose.ui.unit.sp\n'
    'import androidx.compose.ui.viewinterop.AndroidView\n'
    'import androidx.media3.common.MediaItem\n'
    'import androidx.media3.common.PlaybackException\n'
    'import androidx.media3.common.Player\n'
    'import androidx.media3.datasource.DefaultHttpDataSource\n'
    'import androidx.media3.exoplayer.ExoPlayer\n'
    'import androidx.media3.exoplayer.source.DefaultMediaSourceFactory\n'
    'import androidx.media3.ui.AspectRatioFrameLayout\n'
    'import androidx.media3.ui.PlayerView\n',
    'AndroidView ve Media3 importları',
)

main = replace_once(
    main,
    '    var showSettings by remember { mutableStateOf(false) }\n',
    '    var showSettings by remember { mutableStateOf(false) }\n'
    '    var animatedCoversEnabled by remember { mutableStateOf(AppSettings.animatedCovers(context)) }\n',
    'hareketli kapak durum state’i',
)
main = replace_once(
    main,
    '            NowPlayingScreen(controller)\n',
    '            NowPlayingScreen(controller, animatedCoversEnabled)\n',
    'NowPlayingScreen çağrısı',
)
main = replace_once(
    main,
    '''        SettingsSheet(
            controller = controller,
            onDismiss = { showSettings = false },
        )
''',
    '''        SettingsSheet(
            controller = controller,
            animatedCoversEnabled = animatedCoversEnabled,
            onAnimatedCoversChanged = { animatedCoversEnabled = it },
            onDismiss = { showSettings = false },
        )
''',
    'SettingsSheet çağrısı',
)

main = replace_once(
    main,
    '''@Composable
private fun NowPlayingScreen(controller: PlayerController) {
    val track = controller.currentTrack ?: return
''',
    '''@Composable
private fun NowPlayingScreen(controller: PlayerController, animatedCoversEnabled: Boolean) {
    val track = controller.currentTrack ?: return
    val context = LocalContext.current
''',
    'NowPlayingScreen imzası',
)
main = replace_once(
    main,
    '''            AsyncImage(
                model = controller.currentCover,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(SurfaceDark),
                contentScale = ContentScale.Crop,
            )
''',
    '''            AnimatedNowPlayingArtwork(
                videoUrl = controller.currentRelease?.animatedCoverUrl.orEmpty(),
                coverUrl = controller.currentCover,
                enabled = animatedCoversEnabled,
                playing = controller.isPlaying,
                huggingFaceToken = AppSettings.huggingFaceToken(context),
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
''',
    'oynatma ekranı kapak bileşeni',
)

animated_component = r'''
@Composable
private fun AnimatedNowPlayingArtwork(
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

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark),
    ) {
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
                    .setUserAgent("AuroraMusic/0.3.1")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers)
                val mediaSourceFactory = DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(httpFactory)

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
                if (playing) {
                    videoPlayer.play()
                } else {
                    videoPlayer.pause()
                }
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
                update = { view ->
                    view.player = videoPlayer
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

'''
main = replace_once(
    main,
    '@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun SettingsSheet(',
    animated_component + '@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun SettingsSheet(',
    'hareketli oynatma bileşeni',
)

main = replace_once(
    main,
    'private fun SettingsSheet(controller: PlayerController, onDismiss: () -> Unit) {\n',
    '''private fun SettingsSheet(
    controller: PlayerController,
    animatedCoversEnabled: Boolean,
    onAnimatedCoversChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
''',
    'SettingsSheet imzası',
)
main = replace_once(
    main,
    '    var animated by remember { mutableStateOf(AppSettings.animatedCovers(context)) }\n',
    '    var animated by remember(animatedCoversEnabled) { mutableStateOf(animatedCoversEnabled) }\n',
    'SettingsSheet hareketli kapak state’i',
)
main = replace_once(
    main,
    '''                        AppSettings.setAnimatedCovers(context, animated)
                        saved = true
''',
    '''                        AppSettings.setAnimatedCovers(context, animated)
                        onAnimatedCoversChanged(animated)
                        saved = true
''',
    'ayar callback’i',
)

MAIN.write_text(main, encoding="utf-8")
GRADLE.write_text(gradle, encoding="utf-8")

print("Android v0.3.1 hareketli oynatma ekranı yaması uygulandı")
