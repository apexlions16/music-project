package com.apexlions.aurorastudio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class PublishRequest(
    val releaseTitle: String,
    val releaseType: String,
    val releaseDate: String,
    val mainArtist: String,
    val label: String,
    val copyright: String,
    val description: String,
    val featured: Boolean,
    val coverUrl: String,
    val coverAsset: AssetDraft?,
    val videoUrl: String,
    val videoAsset: AssetDraft?,
    val tracks: List<TrackDraft>,
)

internal data class PublishResult(
    val newTracks: Int,
    val reusedTracks: Int,
    val releaseId: String,
)

internal class AuroraHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.MINUTES)
        .writeTimeout(90, TimeUnit.MINUTES)
        .callTimeout(95, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
}

private fun Response.errorMessage(prefix: String): String {
    val bodyText = runCatching { body?.string().orEmpty() }.getOrDefault("")
    return "$prefix: HTTP $code ${bodyText.take(1000)}".trim()
}

internal fun contentDisplayName(resolver: ContentResolver, uri: Uri): String {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index).orEmpty().ifBlank { "dosya" }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "dosya" } ?: "dosya"
}

internal fun contentSize(resolver: ContentResolver, uri: Uri): Long {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index).coerceAtLeast(0L)
        }
    }
    return runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L }
        .getOrDefault(0L)
}

private data class HashResult(val sha256: String, val size: Long)

private fun hashUri(resolver: ContentResolver, uri: Uri): HashResult {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            size += read
        }
    } ?: error("Dosya açılamadı: $uri")
    return HashResult(digest.digest().joinToString("") { "%02x".format(it) }, size)
}

private class UriRangeBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
) : RequestBody() {
    override fun contentType() = null
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                if (offset > 0) {
                    val positioned = runCatching { input.channel.position(offset); true }.getOrDefault(false)
                    if (!positioned) {
                        var skipped = 0L
                        while (skipped < offset) {
                            val value = input.skip(offset - skipped)
                            if (value <= 0) error("Dosyanın istenen bölümüne gidilemedi")
                            skipped += value
                        }
                    }
                }
                val buffer = ByteArray(1024 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("Dosya beklenenden erken bitti")
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        } ?: error("Dosya açılamadı: $uri")
    }
}

internal class GitHubCatalogClient(
    private val config: StudioConfig,
    private val http: AuroraHttp,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "AuroraStudioMobile/0.5.0")
        .apply { if (config.githubToken.isNotBlank()) header("Authorization", "Bearer ${config.githubToken.trim()}") }

    private fun contentsUrl(): String = "https://api.github.com".toHttpUrl().newBuilder()
        .addPathSegment("repos")
        .addPathSegments(config.githubRepo.trim('/'))
        .addPathSegment("contents")
        .addPathSegments(config.catalogPath.trim('/'))
        .build().toString()

    fun loadCatalog(): CatalogSnapshot {
        val url = contentsUrl().toHttpUrl().newBuilder()
            .addQueryParameter("ref", config.githubBranch)
            .build()
        val response = http.client.newCall(requestBuilder(url.toString()).get().build()).execute()
        response.use {
            if (!it.isSuccessful) error(it.errorMessage("GitHub kataloğu alınamadı"))
            val payload = JSONObject(it.body?.string().orEmpty())
            val decoded = Base64.decode(payload.getString("content"), Base64.DEFAULT).toString(Charsets.UTF_8)
            return CatalogSnapshot(JSONObject(decoded), payload.getString("sha"))
        }
    }

    fun commitCatalog(catalog: JSONObject, sha: String, message: String): String {
        catalog.put("updatedAt", nowIso())
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString((catalog.toString(2) + "\n").toByteArray(), Base64.NO_WRAP))
            .put("sha", sha)
            .put("branch", config.githubBranch)
            .toString()
            .toRequestBody(jsonType)
        val response = http.client.newCall(requestBuilder(contentsUrl()).put(body).build()).execute()
        response.use {
            if (it.code == 409) error("GitHub kataloğu başka bir işlem tarafından değiştirildi. Kataloğu yenileyip tekrar deneyin.")
            if (!it.isSuccessful) error(it.errorMessage("GitHub katalog commit'i başarısız"))
            return JSONObject(it.body?.string().orEmpty()).getJSONObject("content").getString("sha")
        }
    }
}

internal class HuggingFaceUploader(
    private val context: Context,
    private val config: StudioConfig,
    private val http: AuroraHttp,
) {
    companion object {
        private const val INDEX_PATH = "aurora/.aurora-storage-index.json"
        private const val SHARD_LIMIT = 9000
        private const val SOFT_COMMIT_LIMIT = 120
    }

    private val resolver = context.contentResolver
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val lfsType = "application/vnd.git-lfs+json".toMediaType()
    private val ndjsonType = "application/x-ndjson".toMediaType()

    private fun auth(builder: Request.Builder): Request.Builder = builder
        .header("User-Agent", "AuroraStudioMobile/0.5.0")
        .apply { if (config.hfToken.isNotBlank()) header("Authorization", "Bearer ${config.hfToken.trim()}") }

    fun resolveUrl(path: String): String = "https://huggingface.co/datasets/${config.hfRepo}/resolve/main/$path"

    fun loadStorageIndex(): JSONObject {
        val url = resolveUrl(INDEX_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("aurora_cache_bust", System.currentTimeMillis().toString())
            .build()
        val response = http.client.newCall(auth(Request.Builder().url(url)).get().build()).execute()
        response.use {
            if (it.isSuccessful) {
                return runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrElse { emptyIndex() }
            }
            if (it.code !in listOf(401, 403, 404)) error(it.errorMessage("Hugging Face shard indeksi alınamadı"))
        }
        return emptyIndex()
    }

    private fun emptyIndex(): JSONObject = JSONObject()
        .put("version", 1)
        .put("shardLimit", SHARD_LIMIT)
        .put("counts", JSONObject())
        .put("updatedAt", nowIso())

    fun allocate(index: JSONObject, categoryRaw: String, suffixRaw: String): String {
        val category = categoryRaw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { "media" }
        val suffix = suffixRaw.lowercase(Locale.ROOT).trim().let { if (it.isBlank()) "" else if (it.startsWith('.')) it else ".$it" }
        val allCounts = index.optJSONObject("counts") ?: JSONObject().also { index.put("counts", it) }
        val counts = allCounts.optJSONObject(category) ?: JSONObject().also { allCounts.put(category, it) }
        var shard = 1
        while (counts.optInt(shard.toString(), 0) >= SHARD_LIMIT) shard++
        counts.put(shard.toString(), counts.optInt(shard.toString(), 0) + 1)
        index.put("updatedAt", nowIso()).put("shardLimit", SHARD_LIMIT)
        return "aurora/${category}${shard}/${UUID.randomUUID().toString().replace("-", "")}$suffix"
    }

    fun prepare(uri: Uri, displayName: String, remotePath: String): PreparedUpload {
        val hash = hashUri(resolver, uri)
        return PreparedUpload(uri, displayName, remotePath, hash.size, hash.sha256)
    }

    private data class CommitAttempt(val code: Int, val body: String, val successful: Boolean)

    private fun normalizedCommitSummary(message: String): String = message
        .replace(Regex("[\\p{Cc}]+"), " ")
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
        val indexEncoded = Base64.encodeToString((index.toString(2) + "\n").toByteArray(), Base64.NO_WRAP)
        lines += JSONObject().put("key", "file").put(
            "value",
            JSONObject().put("content", indexEncoded).put("path", INDEX_PATH).put("encoding", "base64"),
        ).toString()
        val text = lines.joinToString("\n") + if (trailingNewline) "\n" else ""
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

    private fun requestLfsActions(uploads: List<PreparedUpload>): Map<String, JSONObject?> {
        if (uploads.isEmpty()) return emptyMap()
        val objects = JSONArray()
        uploads.forEach { objects.put(JSONObject().put("oid", it.sha256).put("size", it.size)) }
        val payload = JSONObject()
            .put("operation", "upload")
            .put("transfers", JSONArray().put("basic").put("multipart"))
            .put("objects", objects)
            .put("hash_algo", "sha256")
            .put("ref", JSONObject().put("name", "main"))
        val url = "https://huggingface.co/datasets/${config.hfRepo}.git/info/lfs/objects/batch"
        val request = auth(Request.Builder().url(url))
            .header("Accept", "application/vnd.git-lfs+json")
            .post(payload.toString().toRequestBody(lfsType))
            .build()
        val response = http.client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) error(it.errorMessage("Hugging Face LFS planı alınamadı"))
            val result = JSONObject(it.body?.string().orEmpty()).optJSONArray("objects") ?: JSONArray()
            return buildMap {
                for (i in 0 until result.length()) {
                    val row = result.getJSONObject(i)
                    row.optJSONObject("error")?.let { error("LFS hatası: ${it.optString("message")}") }
                    put(row.getString("oid"), row.optJSONObject("actions"))
                }
            }
        }
    }

    private fun uploadLfsObject(item: PreparedUpload, actions: JSONObject) {
        val upload = actions.optJSONObject("upload") ?: return
        val href = upload.getString("href")
        val header = upload.optJSONObject("header") ?: JSONObject()
        val chunkSize = header.optLong("chunk_size", 0L)
        if (chunkSize > 0L) {
            val partUrls = mutableListOf<Pair<Int, String>>()
            val keys = header.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                key.toIntOrNull()?.let { partUrls += it to header.getString(key) }
            }
            partUrls.sortBy { it.first }
            if (partUrls.isEmpty()) error("Hugging Face multipart URL'leri alınamadı")
            val parts = JSONArray()
            partUrls.forEach { (partNumber, partUrl) ->
                val offset = (partNumber - 1L) * chunkSize
                val length = minOf(chunkSize, item.size - offset)
                val response = http.client.newCall(
                    Request.Builder().url(partUrl).put(UriRangeBody(resolver, item.uri, offset, length)).build(),
                ).execute()
                response.use {
                    if (!it.isSuccessful) error(it.errorMessage("Multipart parça $partNumber yüklenemedi"))
                    val etag = it.header("ETag") ?: error("Multipart ETag alınamadı")
                    parts.put(JSONObject().put("partNumber", partNumber).put("etag", etag))
                }
            }
            val complete = JSONObject().put("oid", item.sha256).put("parts", parts)
            val response = http.client.newCall(
                Request.Builder().url(href).post(complete.toString().toRequestBody(lfsType)).build(),
            ).execute()
            response.use { if (!it.isSuccessful) error(it.errorMessage("Multipart tamamlama başarısız")) }
        } else {
            val response = http.client.newCall(
                Request.Builder().url(href).put(UriRangeBody(resolver, item.uri, 0L, item.size)).build(),
            ).execute()
            response.use { if (!it.isSuccessful) error(it.errorMessage("LFS dosyası yüklenemedi")) }
        }

        actions.optJSONObject("verify")?.let { verify ->
            val payload = JSONObject().put("oid", item.sha256).put("size", item.size)
            val response = http.client.newCall(
                auth(Request.Builder().url(verify.getString("href")))
                    .post(payload.toString().toRequestBody(jsonType))
                    .build(),
            ).execute()
            response.use { if (!it.isSuccessful) error(it.errorMessage("LFS doğrulaması başarısız")) }
        }
    }
}

internal class MobilePublisher(
    private val context: Context,
    private val config: StudioConfig,
) {
    private val http = AuroraHttp()
    private val github = GitHubCatalogClient(config, http)
    private val hub = HuggingFaceUploader(context, config, http)

    fun loadCatalog(): CatalogSnapshot = github.loadCatalog()

    fun publish(snapshot: CatalogSnapshot, request: PublishRequest, progress: (String, Float) -> Unit): PublishResult {
        require(request.releaseTitle.isNotBlank()) { "Yayın adı gerekli." }
        require(request.mainArtist.isNotBlank()) { "Ana sanatçı gerekli." }
        require(request.tracks.isNotEmpty()) { "En az bir ses dosyası seçin." }
        require(request.coverAsset != null || request.coverUrl.isNotBlank()) { "Kapak dosyası veya kapak URL'si gerekli." }

        val catalog = JSONObject(snapshot.json.toString())
        catalog.put("schemaVersion", maxOf(4, catalog.optInt("schemaVersion", 1)))
        val artists = catalog.optJSONArray("artists") ?: JSONArray().also { catalog.put("artists", it) }
        val tracks = catalog.optJSONArray("tracks") ?: JSONArray().also { catalog.put("tracks", it) }
        val releases = catalog.optJSONArray("releases") ?: JSONArray().also { catalog.put("releases", it) }
        val featuredReleaseIds = catalog.optJSONArray("featuredReleaseIds") ?: JSONArray().also { catalog.put("featuredReleaseIds", it) }

        val mainArtistId = ensureArtist(artists, request.mainArtist)
        val existingByIsrc = mutableMapOf<String, JSONObject>()
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val normalized = normalizeIsrc(track.optString("isrc"))
            if (normalized.isNotBlank()) existingByIsrc[normalized] = track
        }

        val storageIndex = hub.loadStorageIndex()
        val pending = mutableListOf<Triple<Uri, String, String>>()
        var coverUrl = request.coverUrl.trim()
        request.coverAsset?.let { asset ->
            val remote = hub.allocate(storageIndex, "artwork", extensionOf(asset.displayName))
            pending += Triple(asset.uri, asset.displayName, remote)
            coverUrl = hub.resolveUrl(remote)
        }
        var videoUrl = request.videoUrl.trim()
        request.videoAsset?.let { asset ->
            val remote = hub.allocate(storageIndex, "animated", extensionOf(asset.displayName))
            pending += Triple(asset.uri, asset.displayName, remote)
            videoUrl = hub.resolveUrl(remote)
        }

        val releaseRows = JSONArray()
        var newCount = 0
        var reusedCount = 0
        request.tracks.forEachIndexed { index, draft ->
            val normalized = normalizeIsrc(draft.isrc)
            val existing = normalized.takeIf { it.isNotBlank() }?.let(existingByIsrc::get)
            if (existing != null) {
                reusedCount++
                releaseRows.put(JSONObject().put("trackId", existing.getString("id")).put("disc", 1).put("position", index + 1))
                return@forEachIndexed
            }

            val primaryName = draft.primaryArtist.ifBlank { request.mainArtist }
            val primaryId = ensureArtist(artists, primaryName)
            val featIds = mutableListOf<String>()
            val featNames = mutableListOf<String>()
            splitNames(draft.featuredArtists).forEach { name ->
                findArtistId(artists, name)?.let(featIds::add) ?: featNames.add(name)
            }
            val remote = hub.allocate(storageIndex, "audio", extensionOf(draft.displayName))
            pending += Triple(draft.uri, draft.displayName, remote)
            val sourceInfo = sourceDescriptor(draft.displayName)
            val sourceUrl = hub.resolveUrl(remote)
            val trackId = opaqueId("track")
            val trackJson = JSONObject()
                .put("id", trackId)
                .put("slug", slugify(draft.title))
                .put("title", draft.title.ifBlank { draft.displayName.substringBeforeLast('.') })
                .put("artistIds", JSONArray((listOf(primaryId) + featIds).distinct()))
                .put("primaryArtistIds", JSONArray().put(primaryId))
                .put("featuredArtistIds", JSONArray(featIds.distinct()))
                .put("featuredArtistNames", JSONArray(featNames.distinctBy { it.lowercase(Locale.ROOT) }))
                .put("durationSeconds", 0)
                .put("isrc", draft.isrc.trim())
                .put("explicit", draft.explicit)
                .put("lyrics", draft.lyrics)
                .put("syncedLyrics", "")
                .put("credits", JSONArray())
                .put(
                    "sources",
                    JSONArray().put(
                        JSONObject()
                            .put("id", opaqueId("audio"))
                            .put("kind", sourceInfo.kind)
                            .put("label", sourceInfo.label)
                            .put("codec", sourceInfo.codec)
                            .put("url", sourceUrl)
                            .put("downloadUrl", sourceUrl)
                            .put("downloadable", true)
                            .put("spatial", false),
                    ),
                )
            tracks.put(trackJson)
            if (normalized.isNotBlank()) existingByIsrc[normalized] = trackJson
            releaseRows.put(JSONObject().put("trackId", trackId).put("disc", 1).put("position", index + 1))
            newCount++
        }

        val prepared = pending.mapIndexed { index, (uri, name, remote) ->
            progress("Dosya ${index + 1}/${pending.size} için SHA-256 hesaplanıyor: $name", 0.03f + index.toFloat() / pending.size.coerceAtLeast(1) * 0.12f)
            hub.prepare(uri, name, remote)
        }
        if (prepared.isNotEmpty()) {
            hub.uploadAndCommit(prepared, storageIndex, "Aurora Music: ${request.releaseTitle}", progress)
        }

        val releaseId = opaqueId("release")
        val release = JSONObject()
            .put("id", releaseId)
            .put("slug", slugify(request.releaseTitle))
            .put("title", request.releaseTitle.trim())
            .put("type", request.releaseType)
            .put("artistIds", JSONArray().put(mainArtistId))
            .put("primaryArtistIds", JSONArray().put(mainArtistId))
            .put("releaseDate", request.releaseDate)
            .put("cover", coverUrl)
            .put("heroImage", coverUrl)
            .put("animatedCoverUrl", videoUrl)
            .put("label", request.label)
            .put("copyright", request.copyright)
            .put("description", request.description)
            .put("tracks", releaseRows)
        releases.put(release)
        if (request.featured) {
            val rebuilt = JSONArray().put(releaseId)
            for (i in 0 until featuredReleaseIds.length()) rebuilt.put(featuredReleaseIds.get(i))
            catalog.put("featuredReleaseIds", rebuilt)
        }

        progress("GitHub kataloğu commit ediliyor…", 0.96f)
        github.commitCatalog(catalog, snapshot.sha, "Aurora Music: ${request.releaseTitle} yayınını mobil Studio'dan ekle")
        progress("Yayın tamamlandı", 1f)
        return PublishResult(newCount, reusedCount, releaseId)
    }

    private fun findArtistId(artists: JSONArray, name: String): String? {
        for (i in 0 until artists.length()) {
            val artist = artists.optJSONObject(i) ?: continue
            if (artist.optString("name").equals(name.trim(), ignoreCase = true)) return artist.optString("id").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun ensureArtist(artists: JSONArray, name: String): String {
        findArtistId(artists, name)?.let { return it }
        val id = opaqueId("artist")
        artists.put(
            JSONObject()
                .put("id", id)
                .put("slug", slugify(name))
                .put("name", name.trim())
                .put("image", "")
                .put("heroImage", "")
                .put("backgroundImage", "")
                .put("backgroundVideoUrl", "")
                .put("bio", ""),
        )
        return id
    }
}
