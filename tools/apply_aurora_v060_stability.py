from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_required(text: str, old: str, new: str, *, count: int = 1) -> str:
    actual = text.count(old)
    if actual < count:
        raise RuntimeError(f"Beklenen kaynak parçası bulunamadı ({actual}/{count}): {old[:120]!r}")
    return text.replace(old, new, count)


# 1) Android: foreground servis zaman aşımını kaldır, servis başlatmayı idempotent ve hataya dayanıklı yap.
service_path = "app/src/main/kotlin/com/apexlions/music/AuroraPlaybackService.kt"
service = read(service_path)
service = service.replace("import androidx.core.content.ContextCompat\n", "")
service = replace_required(
    service,
    "    private var internalHttpFactory: DefaultHttpDataSource.Factory? = null\n",
    "    private var internalHttpFactory: DefaultHttpDataSource.Factory? = null\n    @Volatile private var serviceStarted = false\n",
)
old_start = '''    fun startService(context: Context) {
        val intent = Intent(context.applicationContext, AuroraPlaybackService::class.java)
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }
'''
new_start = '''    fun startService(context: Context) {
        if (serviceStarted) return
        val appContext = context.applicationContext
        runCatching {
            // MediaSessionService kendi medya bildirimiyle gerektiğinde foreground'a yükselir.
            // Oynatma başlamadan startForegroundService çağırmak Android'in süre sınırında
            // uygulamayı kapatmasına neden oluyordu; bu nedenle normal started-service kullanılır.
            appContext.startService(Intent(appContext, AuroraPlaybackService::class.java))
            serviceStarted = true
        }.onFailure { error ->
            serviceStarted = false
            playbackError = "Arka plan oynatma servisi başlatılamadı: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun markServiceStopped() {
        serviceStarted = false
    }
'''
service = replace_required(service, old_start, new_start)
service = replace_required(
    service,
    '''    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
''',
    '''    override fun onDestroy() {
        AuroraPlaybackEngine.markServiceStopped()
        mediaSession?.release()
        mediaSession = null
''',
)
service = service.replace('AuroraMusic/0.5.0', 'AuroraMusic/0.6.0')
write(service_path, service)

controller_path = "app/src/main/kotlin/com/apexlions/music/PlayerController.kt"
controller = read(controller_path)
controller = replace_required(controller, "        AuroraPlaybackEngine.startService(appContext)\n        currentRelease = release\n", "        currentRelease = release\n")
controller = replace_required(
    controller,
    '''        if (playableQueue.none { it.id == track.id }) {
            playbackError = "Seçilen şarkı oynatma kuyruğuna eklenemedi."
            return
        }
        queue = playableQueue

        val same = currentTrack?.id == track.id
        if (same && player.mediaItemCount > 0) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
''',
    '''        if (playableQueue.none { it.id == track.id }) {
            playbackError = "Seçilen şarkı oynatma kuyruğuna eklenemedi."
            return
        }
        AuroraPlaybackEngine.startService(appContext)
        queue = playableQueue

        val same = currentTrack?.id == track.id
        if (same && player.mediaItemCount > 0) {
            runCatching {
                if (player.isPlaying) player.pause() else player.play()
            }.onFailure(::recordPlaybackFailure)
            return
        }
''',
)
controller = replace_required(
    controller,
    '''        val items = playlistItems(artistLine, cover, release?.title.orEmpty())
        val index = items.indexOfFirst { it.mediaId == track.id }.coerceAtLeast(0)
        player.setMediaItems(items, index, 0L)
        player.shuffleModeEnabled = shuffle
        player.prepare()
        player.playWhenReady = true
''',
    '''        val items = playlistItems(artistLine, cover, release?.title.orEmpty())
        val index = items.indexOfFirst { it.mediaId == track.id }.coerceAtLeast(0)
        runCatching {
            player.setMediaItems(items, index, 0L)
            player.shuffleModeEnabled = shuffle
            player.prepare()
            player.playWhenReady = true
        }.onFailure(::recordPlaybackFailure)
''',
)
controller = replace_required(
    controller,
    '''    fun toggle() {
        if (player.isPlaying) {
            player.pause()
        } else {
            AuroraPlaybackEngine.startService(appContext)
            player.play()
        }
    }
''',
    '''    fun toggle() {
        runCatching {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.mediaItemCount == 0) return
                AuroraPlaybackEngine.startService(appContext)
                player.play()
            }
        }.onFailure(::recordPlaybackFailure)
    }

    private fun recordPlaybackFailure(error: Throwable) {
        isPlaying = false
        playbackError = "Oynatıcı işlemi güvenli biçimde durduruldu: ${error.message ?: error.javaClass.simpleName}"
    }
''',
)
controller = replace_required(
    controller,
    '''        val index = items.indexOfFirst { it.mediaId == currentTrackId }.coerceAtLeast(0)
        player.setMediaItems(items, index, position)
        player.shuffleModeEnabled = shuffle
        player.prepare()
        player.playWhenReady = playWhenReady
''',
    '''        val index = items.indexOfFirst { it.mediaId == currentTrackId }.coerceAtLeast(0)
        runCatching {
            player.setMediaItems(items, index, position)
            player.shuffleModeEnabled = shuffle
            player.prepare()
            player.playWhenReady = playWhenReady
        }.onFailure(::recordPlaybackFailure)
''',
)
write(controller_path, controller)

# 2) Studio Mobile: Hugging Face commit başlığını doğrula ve protokol uyumsuzluğunda güvenli retry yap.
hub_path = "studioapp/src/main/kotlin/com/apexlions/aurorastudio/HubClients.kt"
hub = read(hub_path)
start = hub.index("    fun uploadAndCommit(\n")
end = hub.index("    private fun requestLfsActions", start)
new_uploader = '''    private data class CommitAttempt(val code: Int, val body: String, val successful: Boolean)

    private fun normalizedCommitSummary(message: String): String = message
        .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(200)
        .ifBlank { "Aurora Studio Mobile media upload" }

    private fun commitPayload(
        uploads: List<PreparedUpload>,
        index: JSONObject,
        summary: String,
        trailingNewline: Boolean,
    ): ByteArray {
        val safeSummary = normalizedCommitSummary(summary)
        val headerValue = JSONObject()
            .put("summary", safeSummary)
            .put("description", "Aurora Studio Mobile")
        check(headerValue.optString("summary").isNotBlank()) { "Hugging Face commit özeti boş olamaz." }

        val lines = mutableListOf<String>()
        lines += JSONObject().put("key", "header").put("value", headerValue).toString()
        uploads.forEach { item ->
            require(item.remotePath.isNotBlank()) { "Hugging Face uzak dosya yolu boş." }
            require(item.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Geçersiz SHA-256: ${item.displayName}" }
            lines += JSONObject().put("key", "lfsFile").put(
                "value",
                JSONObject()
                    .put("path", item.remotePath)
                    .put("algo", "sha256")
                    .put("oid", item.sha256.lowercase(Locale.ROOT))
                    .put("size", item.size),
            ).toString()
        }
        val indexEncoded = Base64.encodeToString((index.toString(2) + "\\n").toByteArray(), Base64.NO_WRAP)
        lines += JSONObject().put("key", "file").put(
            "value",
            JSONObject().put("content", indexEncoded).put("path", INDEX_PATH).put("encoding", "base64"),
        ).toString()
        val text = lines.joinToString("\\n") + if (trailingNewline) "\\n" else ""
        // Sunucuya gönderilmeden önce ilk NDJSON satırını tekrar parse ederek value.summary'nin
        // gerçekten bir String olduğunu garanti et.
        val parsedHeader = JSONObject(text.lineSequence().first())
            .getJSONObject("value")
            .getString("summary")
        check(parsedHeader.isNotBlank()) { "Hugging Face commit özeti oluşturulamadı." }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun postCommit(payload: ByteArray): CommitAttempt {
        val url = "https://huggingface.co/api/datasets/${config.hfRepo}/commit/main"
        val response = http.client.newCall(
            auth(Request.Builder().url(url))
                .header("Accept", "application/json")
                .post(payload.toRequestBody(ndjsonType))
                .build(),
        ).execute()
        return response.use {
            CommitAttempt(it.code, runCatching { it.body?.string().orEmpty() }.getOrDefault(""), it.isSuccessful)
        }
    }

    fun uploadAndCommit(
        uploads: List<PreparedUpload>,
        index: JSONObject,
        message: String,
        progress: (String, Float) -> Unit,
    ) {
        if (uploads.isEmpty()) {
            progress("Yüklenecek medya yok; Hugging Face commit'i atlandı", 1f)
            return
        }
        val recent = SecureSettings.recentCommits(context)
        if (recent.size >= SOFT_COMMIT_LIMIT) {
            val waitMillis = (recent.first() + 3_600_000L - System.currentTimeMillis()).coerceAtLeast(1L)
            val minutes = (waitMillis + 59_999L) / 60_000L
            error("Hugging Face güvenlik sınırı: son saatte ${recent.size} commit. Yaklaşık $minutes dakika bekleyin.")
        }
        if (config.hfRepo.isBlank() || config.hfToken.isBlank()) error("Hugging Face repo ve token gerekli.")

        progress("Hugging Face LFS yükleme planı hazırlanıyor…", 0.04f)
        val actionsByOid = requestLfsActions(uploads)
        uploads.forEachIndexed { indexInList, item ->
            val action = actionsByOid[item.sha256]
            if (action != null) uploadLfsObject(item, action)
            progress(
                "Dosya ${indexInList + 1}/${uploads.size} tamamlandı: ${item.displayName}",
                0.08f + ((indexInList + 1).toFloat() / uploads.size.coerceAtLeast(1)) * 0.78f,
            )
        }

        progress("Tek Hugging Face commit'i oluşturuluyor…", 0.90f)
        val safeSummary = normalizedCommitSummary(message)
        val attempts = listOf(
            commitPayload(uploads, index, safeSummary, trailingNewline = false),
            commitPayload(uploads, index, "Aurora Studio Mobile media upload", trailingNewline = false),
            commitPayload(uploads, index, "Aurora Studio Mobile media upload", trailingNewline = true),
        )
        var result: CommitAttempt? = null
        for ((attemptIndex, payload) in attempts.withIndex()) {
            result = postCommit(payload)
            if (result.successful) break
            val summaryProtocolError = result.code == 400 && result.body.contains("value.summary", ignoreCase = true)
            if (!summaryProtocolError || attemptIndex == attempts.lastIndex) break
            progress("Hugging Face commit protokolü yeniden deneniyor…", 0.92f + attemptIndex * 0.02f)
        }
        val finalResult = result ?: error("Hugging Face commit yanıtı alınamadı.")
        if (!finalResult.successful) {
            error("Hugging Face commit'i başarısız: HTTP ${finalResult.code} ${finalResult.body.take(1000)}".trim())
        }
        SecureSettings.recordCommit(context)
        progress("Hugging Face yüklemesi tamamlandı", 1f)
    }

'''
hub = hub[:start] + new_uploader + hub[end:]
hub = hub.replace('AuroraStudioMobile/0.1.0', 'AuroraStudioMobile/0.5.0')
write(hub_path, hub)

# 3) Windows Studio: ImportRequest şemasını hareketli kapak dosyasıyla eşitle.
base_path = "pc/AuroraStudio.py"
base_text = read(base_path)
base_text = replace_required(
    base_text,
    '''    spotify_id: str = ""
    spotify_url: str = ""


class TaskThread''',
    '''    spotify_id: str = ""
    spotify_url: str = ""
    animated_cover_path: Path | None = None


class TaskThread''',
)
write(base_path, base_text)

v3_path = "pc/AuroraStudioV3.py"
v3_text = read(v3_path)
v3_text = replace_required(
    v3_text,
    '''            hero_url=self.hero_url.text().strip(),
            animated_cover_path=Path(self.animated_path.text()) if self.animated_path.text() else None,
''',
    '''            hero_url=self.hero_url.text().strip(),
            animated_cover_url=self.animated_url_v3.text().strip() if hasattr(self, "animated_url_v3") else "",
            animated_cover_path=Path(self.animated_path.text()) if self.animated_path.text() else None,
''',
)
v3_text = v3_text.replace('        animated_url = self.animated_url_v3.text().strip() if hasattr(self, "animated_url_v3") else ""\n', '')
v3_text = replace_required(v3_text, '                local_animated_url = animated_url\n', '                local_animated_url = request.animated_cover_url\n')
v3_text = v3_text.replace('Studio v0.3 ile ekle', 'Studio v0.5 ile ekle')
write(v3_path, v3_text)

# 4) Sürümler ve görünen metinler.
app_gradle = read("app/build.gradle.kts").replace('versionCode = 7', 'versionCode = 8').replace('versionName = "0.5.0"', 'versionName = "0.6.0"')
write("app/build.gradle.kts", app_gradle)
studio_gradle = read("studioapp/build.gradle.kts").replace('versionCode = 4', 'versionCode = 5').replace('versionName = "0.4.0"', 'versionName = "0.5.0"')
write("studioapp/build.gradle.kts", studio_gradle)

for path in [
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt",
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioHubActivity.kt",
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioCurationActivity.kt",
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/CatalogV2Manager.kt",
]:
    value = read(path).replace('v0.4.0', 'v0.5.0').replace('/0.4.0', '/0.5.0')
    write(path, value)

v4 = read("pc/AuroraStudioV4Entry.py").replace('APP_VERSION = "0.4.0"', 'APP_VERSION = "0.5.0"')
write("pc/AuroraStudioV4Entry.py", v4)

activity = read("app/src/main/kotlin/com/apexlions/music/AuroraV4Activity.kt").replace('AuroraMusic/0.4.0', 'AuroraMusic/0.6.0')
write("app/src/main/kotlin/com/apexlions/music/AuroraV4Activity.kt", activity)

print("Aurora Music v0.6.0, Studio Mobile/Windows v0.5.0 kararlılık düzeltmeleri uygulandı.")
