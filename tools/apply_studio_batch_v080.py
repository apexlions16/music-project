from pathlib import Path


def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Beklenen blok bulunamadı: {path}\n{old[:240]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


path = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt")

replace_required(
    path,
    '''    val releaseTracks = remember { mutableStateListOf<V2TrackDraft>() }

    var selectedEdit''',
    '''    val releaseTracks = remember { mutableStateListOf<V2TrackDraft>() }
    val batchQueue = remember { mutableStateListOf<V2ReleaseDraft>() }

    var selectedEdit''',
)

replace_required(
    path,
    '''

    fun publishRelease() {
        val current = snapshot ?: run {
            error = "Önce kataloğu yükleyin."
            return
        }
        if (busy) return
        busy = true
        error = null
        progress = 0f
        status = "Yayın hazırlanıyor…"
        val draft = V2ReleaseDraft(
            title = releaseTitle,
            type = releaseType,
            releaseDate = releaseDate,
            mainArtist = mainArtist,
            label = label,
            copyright = copyright,
            description = description,
            coverUrl = coverUrl,
            coverAsset = coverAsset,
            animatedCoverUrl = animatedCoverUrl,
            featured = featured,
            tracks = releaseTracks.toList(),
            metadataSource = metadataSource,
            metadataSourceId = metadataSourceId,
        )
''',
    '''

    fun currentReleaseDraft(): V2ReleaseDraft = V2ReleaseDraft(
        title = releaseTitle,
        type = releaseType,
        releaseDate = releaseDate,
        mainArtist = mainArtist,
        label = label,
        copyright = copyright,
        description = description,
        coverUrl = coverUrl,
        coverAsset = coverAsset,
        animatedCoverUrl = animatedCoverUrl,
        featured = featured,
        tracks = releaseTracks.toList(),
        metadataSource = metadataSource,
        metadataSourceId = metadataSourceId,
    )

    fun resetReleaseForm() {
        releaseTitle = ""
        description = ""
        coverUrl = ""
        coverAsset = null
        animatedCoverUrl = ""
        metadataSource = "manual"
        metadataSourceId = ""
        releaseTracks.clear()
    }

    fun addCurrentToBatch() {
        if (busy) return
        val draft = currentReleaseDraft()
        runCatching {
            require(draft.title.isNotBlank()) { "Yayın adı gerekli." }
            require(draft.mainArtist.isNotBlank()) { "Ana sanatçı gerekli." }
            require(draft.tracks.isNotEmpty()) { "En az bir metadata parçası gerekli." }
            require(draft.coverAsset != null || draft.coverUrl.isNotBlank()) { "Kapak dosyası veya Spotify kapağı gerekli." }
            draft.tracks.filter { it.syncedLyrics.isNotBlank() }.forEach { track -> StudioLrcSupport.normalize(track.syncedLyrics) }
        }.onSuccess {
            batchQueue += draft
            status = "${draft.title} batch kuyruğuna eklendi • kuyrukta ${batchQueue.size} yayın var."
            error = null
            resetReleaseForm()
        }.onFailure {
            error = it.message ?: "Yayın batch kuyruğuna eklenemedi."
        }
    }

    fun publishBatch() {
        val current = snapshot ?: run {
            error = "Önce kataloğu yükleyin."
            return
        }
        if (busy || batchQueue.isEmpty()) return
        val queued = batchQueue.toList()
        busy = true
        error = null
        progress = 0f
        status = "${queued.size} yayın tek batch için hazırlanıyor…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val publisher = CatalogBatchPublisher(context, config)
                    val result = publisher.publish(current, queued) { text, value ->
                        scope.launch { status = text; progress = value.coerceIn(0f, 1f) }
                    }
                    result to CatalogV2Manager(context, config).loadCatalog()
                }
            }.onSuccess { (result, refreshed) ->
                snapshot = refreshed
                batchQueue.clear()
                status = "Batch tamamlandı: ${result.releases} yayın, ${result.newTracks} yeni ve ${result.reusedTracks} ISRC ile tekrar kullanılan parça • 1 HF + 1 GitHub commit."
                progress = 1f
                resetReleaseForm()
            }.onFailure {
                error = it.message ?: it.toString()
                status = "Batch yayın başarısız • kuyruk korunuyor"
                progress = 0f
            }
            busy = false
        }
    }

    fun publishRelease() {
        val current = snapshot ?: run {
            error = "Önce kataloğu yükleyin."
            return
        }
        if (busy) return
        busy = true
        error = null
        progress = 0f
        status = "Yayın hazırlanıyor…"
        val draft = currentReleaseDraft()
''',
)

replace_required(
    path,
    '''                releaseTitle = ""
                description = ""
                coverUrl = ""
                coverAsset = null
                animatedCoverUrl = ""
                metadataSource = "manual"
                metadataSourceId = ""
                releaseTracks.clear()
''',
    '''                resetReleaseForm()
''',
)

replace_required(path, 'Text("v0.7.0 • ${screen.title}"', 'Text("v0.8.0 • ${screen.title}"')

replace_required(
    path,
    '''                    pickTrackLrc = { index -> lrcTargetIndex = index; lrcPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream")) },
                    publish = ::publishRelease,
                    canPublish = snapshot != null && !busy,
''',
    '''                    pickTrackLrc = { index -> lrcTargetIndex = index; lrcPicker.launch(arrayOf("application/x-lrc", "text/plain", "application/octet-stream")) },
                    publish = ::publishRelease,
                    addToBatch = ::addCurrentToBatch,
                    batchQueue = batchQueue,
                    removeFromBatch = { index -> if (!busy && index in batchQueue.indices) batchQueue.removeAt(index) },
                    publishBatch = ::publishBatch,
                    canPublish = snapshot != null && !busy,
''',
)

replace_required(
    path,
    '''    pickTrackLrc: (Int) -> Unit,
    publish: () -> Unit,
    canPublish: Boolean,
''',
    '''    pickTrackLrc: (Int) -> Unit,
    publish: () -> Unit,
    addToBatch: () -> Unit,
    batchQueue: List<V2ReleaseDraft>,
    removeFromBatch: (Int) -> Unit,
    publishBatch: () -> Unit,
    canPublish: Boolean,
''',
)

replace_required(
    path,
    '''        item {
            Button(onClick = publish, enabled = canPublish && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CloudUpload, null)
                Text(" Hugging Face'e yükle ve GitHub'a yayınla")
            }
        }
''',
    '''        item {
            V2Card("Yayınlama") {
                Button(onClick = publish, enabled = canPublish && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CloudUpload, null)
                    Text(" Bu yayını şimdi tekli yayınla")
                }
                OutlinedButton(onClick = addToBatch, enabled = !busy && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Text(" Yayını batch kuyruğuna ekle")
                }
                Text("Tekli yükleme mantığı korunur. Batch kuyruğunda bütün yayınların medyası tek Hugging Face commit'ine, katalogları tek GitHub commit'ine yazılır.", color = StudioMuted, fontSize = 11.sp)
            }
        }
        item {
            V2Card("Batch Yayın Kuyruğu • ${batchQueue.size}") {
                if (batchQueue.isEmpty()) {
                    Text("Kuyruk boş. Bir yayını hazırlayıp yukarıdaki düğmeyle ekleyin.", color = StudioMuted, fontSize = 11.sp)
                } else {
                    batchQueue.forEachIndexed { index, draft ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. ${draft.title}", fontWeight = FontWeight.SemiBold)
                                Text("${draft.mainArtist} • ${draft.tracks.size} parça • ${draft.tracks.count { it.audio != null }} ses hazır", color = StudioMuted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { removeFromBatch(index) }, enabled = !busy) { Text("Kaldır", color = StudioError) }
                        }
                        if (index < batchQueue.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .08f))
                    }
                    Button(onClick = publishBatch, enabled = canPublish, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.CloudUpload, null)
                        Text(" ${batchQueue.size} yayını tek batch olarak yayınla")
                    }
                    Text("Commit kullanımı: medya varsa 1 Hugging Face commit + bütün katalog için 1 GitHub commit.", color = StudioMuted, fontSize = 11.sp)
                }
            }
        }
''',
)

print("Studio Mobile batch v0.8.0 yaması uygulandı.")
