package com.apexlions.aurorastudio

import android.content.Context
import android.net.Uri
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class BatchPublishResult(
    val releases: Int,
    val newTracks: Int,
    val reusedTracks: Int,
    val releaseIds: List<String>,
)

/**
 * Birden fazla yeni yayını mevcut tekli yükleme kurallarını değiştirmeden hazırlar.
 * Bütün medya dosyaları tek Hugging Face commit'inde, bütün katalog değişiklikleri
 * de tek GitHub commit'inde gönderilir.
 */
internal class CatalogBatchPublisher(
    private val context: Context,
    private val config: StudioConfig,
) {
    private val http = AuroraHttp()
    private val github = GitHubCatalogClient(config, http)
    private val hub = HuggingFaceUploader(context, config, http)

    private data class Pending(val asset: AssetDraft, val remote: String)
    private data class ReleaseBuild(val releaseId: String, val newTracks: Int, val reusedTracks: Int)

    fun publish(
        snapshot: CatalogSnapshot,
        drafts: List<V2ReleaseDraft>,
        progress: (String, Float) -> Unit,
    ): BatchPublishResult {
        require(drafts.isNotEmpty()) { "Batch kuyruğu boş." }
        drafts.forEachIndexed { index, draft -> validateDraft(draft, index) }

        val catalog = JSONObject(snapshot.json.toString())
        catalog.put("schemaVersion", maxOf(5, catalog.optInt("schemaVersion", 1)))
        val artists = catalog.array("artists")
        val tracks = catalog.array("tracks")
        val releases = catalog.array("releases")
        val featured = catalog.array("featuredReleaseIds")
        val jobs = catalog.array("qualityJobs")
        val storage = hub.loadStorageIndex()
        val pending = mutableListOf<Pending>()
        val existingByIsrc = existingByIsrc(tracks)
        val builds = mutableListOf<ReleaseBuild>()

        drafts.forEachIndexed { releaseIndex, draft ->
            val start = releaseIndex.toFloat() / drafts.size * .72f
            val span = .72f / drafts.size
            fun releaseProgress(text: String, local: Float) {
                progress("${releaseIndex + 1}/${drafts.size} • $text", start + local.coerceIn(0f, 1f) * span)
            }
            builds += buildRelease(
                catalog = catalog,
                artists = artists,
                tracks = tracks,
                releases = releases,
                featured = featured,
                jobs = jobs,
                storage = storage,
                pending = pending,
                existingByIsrc = existingByIsrc,
                draft = draft,
                progress = ::releaseProgress,
            )
        }

        if (pending.isNotEmpty()) {
            val unique = pending.distinctBy(Pending::remote)
            progress("${unique.size} medya dosyası tek batch commit için hazırlanıyor…", .74f)
            val prepared = unique.mapIndexed { index, row ->
                progress("${index + 1}/${unique.size} • ${row.asset.displayName} hazırlanıyor…", .74f + index.toFloat() / unique.size * .06f)
                hub.prepare(row.asset.uri, row.asset.displayName, row.remote)
            }
            hub.uploadAndCommit(
                prepared,
                storage,
                "Aurora Music: ${drafts.size} yayınlık batch medya yüklemesi",
            ) { text, value ->
                progress(text, .80f + value.coerceIn(0f, 1f) * .15f)
            }
        } else {
            progress("Batch içindeki bütün şarkılar mevcut ISRC kayıtlarından kullanıldı; medya commit'i gerekmedi.", .95f)
        }

        val titles = drafts.joinToString(", ") { it.title.trim() }.take(180)
        progress("Bütün yayınlar tek GitHub katalog commit'ine yazılıyor…", .97f)
        github.commitCatalog(
            catalog,
            snapshot.sha,
            "Aurora Music: ${drafts.size} yayını batch olarak ekle • $titles",
        )
        progress("Batch yayın tamamlandı", 1f)

        return BatchPublishResult(
            releases = builds.size,
            newTracks = builds.sumOf(ReleaseBuild::newTracks),
            reusedTracks = builds.sumOf(ReleaseBuild::reusedTracks),
            releaseIds = builds.map(ReleaseBuild::releaseId),
        )
    }

    private fun validateDraft(draft: V2ReleaseDraft, index: Int) {
        val prefix = "${index + 1}. yayın"
        require(draft.title.isNotBlank()) { "$prefix için yayın adı gerekli." }
        require(draft.mainArtist.isNotBlank()) { "$prefix için ana sanatçı gerekli." }
        require(draft.tracks.isNotEmpty()) { "$prefix için en az bir metadata parçası gerekli." }
        require(draft.coverAsset != null || draft.coverUrl.isNotBlank()) { "$prefix için kapak dosyası veya Spotify kapağı gerekli." }
    }

    private fun buildRelease(
        catalog: JSONObject,
        artists: JSONArray,
        tracks: JSONArray,
        releases: JSONArray,
        featured: JSONArray,
        jobs: JSONArray,
        storage: JSONObject,
        pending: MutableList<Pending>,
        existingByIsrc: MutableMap<String, JSONObject>,
        draft: V2ReleaseDraft,
        progress: (String, Float) -> Unit,
    ): ReleaseBuild {
        val mainArtistId = ensureArtist(artists, draft.mainArtist)
        localizeArtistArtwork(artists, mainArtistId, draft.mainArtist, storage, pending, progress)

        var coverUrl = draft.coverUrl.trim()
        val coverAsset = draft.coverAsset ?: coverUrl
            .takeIf { it.isNotBlank() && !isHuggingFaceUrl(it) }
            ?.let {
                progress("${draft.title}: Spotify kapağı indiriliyor…", .02f)
                fetchRemoteCover(it)
            }
        coverAsset?.let { asset ->
            val remote = hub.allocate(storage, "artwork", extensionOf(asset.displayName))
            pending += Pending(asset, remote)
            coverUrl = hub.resolveUrl(remote)
        }
        require(isHuggingFaceUrl(coverUrl)) {
            "${draft.title}: kapak Hugging Face'e taşınamadı. Spotify kapağını yeniden içe aktarın veya yerel kapak seçin."
        }

        val releaseRows = JSONArray()
        var newCount = 0
        var reusedCount = 0
        draft.tracks.forEachIndexed { index, row ->
            progress("${draft.title} • ${row.title.ifBlank { "${index + 1}. parça" }} hazırlanıyor…", .10f + index.toFloat() / draft.tracks.size * .72f)
            val normalized = normalizeIsrc(row.isrc)
            val existing = normalized.takeIf(String::isNotBlank)?.let(existingByIsrc::get)
            if (existing != null) {
                reusedCount++
                if (row.audio != null && !isPlayable(existing)) {
                    val remote = hub.allocate(storage, "audio-source", extensionOf(row.audio.displayName))
                    pending += Pending(row.audio, remote)
                    val sourceUrl = hub.resolveUrl(remote)
                    val sources = existing.optJSONArray("sources") ?: JSONArray().also { existing.put("sources", it) }
                    sources.put(sourceJson(row.audio.displayName, sourceUrl, "original"))
                    jobs.put(qualityJob(existing.getString("id"), remote, sourceUrl))
                    existing
                        .put("playable", true)
                        .put("availability", "available")
                        .put("qualityState", "queued")
                        .put("durationSeconds", row.durationSeconds)
                        .put("spotifyId", row.spotifyId)
                        .put("spotifyUrl", row.spotifyUrl)
                }
                releaseRows.put(trackRef(existing.getString("id"), row.disc, row.position.ifBlankPosition(index + 1)))
                return@forEachIndexed
            }

            val primaryName = row.primaryArtist.ifBlank { draft.mainArtist }
            val primaryId = ensureArtist(artists, primaryName)
            localizeArtistArtwork(artists, primaryId, primaryName, storage, pending, progress)
            val featureIds = mutableListOf<String>()
            val featureNames = mutableListOf<String>()
            splitNames(row.featuredArtists).forEach { name ->
                val known = findArtistId(artists, name)
                if (known != null) {
                    featureIds += known
                    localizeArtistArtwork(artists, known, name, storage, pending, progress)
                } else {
                    featureNames += name
                }
            }

            val trackId = opaqueId("track")
            val sourceRows = JSONArray()
            row.audio?.let { asset ->
                val remote = hub.allocate(storage, "audio-source", extensionOf(asset.displayName))
                pending += Pending(asset, remote)
                val sourceUrl = hub.resolveUrl(remote)
                sourceRows.put(sourceJson(asset.displayName, sourceUrl, "original"))
                jobs.put(qualityJob(trackId, remote, sourceUrl))
            }
            val playable = sourceRows.length() > 0
            val track = JSONObject()
                .put("id", trackId)
                .put("slug", slugify(row.title))
                .put("title", row.title.ifBlank { "İsimsiz parça ${index + 1}" })
                .put("artistIds", JSONArray((listOf(primaryId) + featureIds).distinct()))
                .put("primaryArtistIds", JSONArray().put(primaryId))
                .put("featuredArtistIds", JSONArray(featureIds.distinct()))
                .put("featuredArtistNames", JSONArray(featureNames.distinctBy { it.lowercase(Locale.ROOT) }))
                .put("durationSeconds", row.durationSeconds)
                .put("spotifyId", row.spotifyId)
                .put("spotifyUrl", row.spotifyUrl)
                .put("isrc", row.isrc.trim())
                .put("explicit", row.explicit)
                .put("lyrics", row.lyrics)
                .put("syncedLyrics", row.syncedLyrics)
                .put("credits", parseCreditsText(row.creditsText))
                .put("sources", sourceRows)
                .put("playable", playable)
                .put("availability", if (playable) "available" else "upcoming")
                .put("qualityState", if (playable) "queued" else "waiting_for_audio")
            tracks.put(track)
            if (normalized.isNotBlank()) existingByIsrc[normalized] = track
            releaseRows.put(trackRef(trackId, row.disc, row.position.ifBlankPosition(index + 1)))
            newCount++
        }

        val releaseId = opaqueId("release")
        val release = JSONObject()
            .put("id", releaseId)
            .put("slug", slugify(draft.title))
            .put("title", draft.title.trim())
            .put("type", draft.type)
            .put("artistIds", JSONArray().put(mainArtistId))
            .put("primaryArtistIds", JSONArray().put(mainArtistId))
            .put("releaseDate", draft.releaseDate)
            .put("cover", coverUrl)
            .put("heroImage", coverUrl)
            .put("animatedCoverUrl", draft.animatedCoverUrl.trim())
            .put("label", draft.label)
            .put("copyright", draft.copyright)
            .put("description", draft.description)
            .put("metadataSource", "spotify")
            .put("metadataSourceId", draft.metadataSourceId)
            .put("spotifyUrl", draft.spotifyUrl)
            .put("spotifyCoverUrl", draft.coverUrl)
            .put("tracks", releaseRows)
        releases.put(release)
        refreshReleaseAvailability(catalog, release)
        if (draft.featured) catalog.put("featuredReleaseIds", prependUnique(featured, releaseId))
        progress("${draft.title}: katalog kaydı hazır", .96f)
        return ReleaseBuild(releaseId, newCount, reusedCount)
    }

    private fun fetchRemoteCover(url: String): AssetDraft {
        require(url.startsWith("https://")) { "Görsel adresi HTTPS olmalı." }
        val request = Request.Builder().url(url).header("User-Agent", "AuroraStudioMobile/0.8.0").get().build()
        return http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Görsel indirilemedi: HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val extension = when {
                "png" in type -> "png"
                "webp" in type -> "webp"
                else -> "jpg"
            }
            val file = File(context.cacheDir, "aurora-batch-image-${System.currentTimeMillis()}.$extension")
            response.body?.byteStream()?.use { input -> file.outputStream().use(input::copyTo) }
                ?: error("Görsel verisi boş döndü.")
            AssetDraft(Uri.fromFile(file), file.name, file.length())
        }
    }

    private fun localizeArtistArtwork(
        artists: JSONArray,
        artistId: String,
        artistName: String,
        storage: JSONObject,
        pending: MutableList<Pending>,
        progress: (String, Float) -> Unit,
    ) {
        val artist = findById(artists, artistId) ?: return
        if (isHuggingFaceUrl(artist.optString("image"))) return
        val spotifyImage = SpotifyMetadataCache.artistImageUrl(artistName)
        if (spotifyImage.isBlank()) return
        progress("$artistName sanatçı görseli indiriliyor…", .04f)
        val asset = fetchRemoteCover(spotifyImage)
        val remote = hub.allocate(storage, "artist-artwork", extensionOf(asset.displayName))
        pending += Pending(asset, remote)
        val hfUrl = hub.resolveUrl(remote)
        artist.put("image", hfUrl)
        if (!isHuggingFaceUrl(artist.optString("heroImage"))) artist.put("heroImage", hfUrl)
        if (!isHuggingFaceUrl(artist.optString("backgroundImage"))) artist.put("backgroundImage", hfUrl)
    }

    private fun isHuggingFaceUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co")
    }

    private fun qualityJob(trackId: String, sourcePath: String, sourceUrl: String): JSONObject = JSONObject()
        .put("id", opaqueId("quality"))
        .put("trackId", trackId)
        .put("sourcePath", sourcePath)
        .put("sourceUrl", sourceUrl)
        .put("status", "queued")
        .put("presets", JSONArray(listOf("standard", "high", "lossless")))
        .put("createdAt", nowIso())

    private fun sourceJson(name: String, url: String, forcedKind: String): JSONObject {
        val descriptor = sourceDescriptor(name)
        return JSONObject()
            .put("id", opaqueId("audio"))
            .put("kind", if (forcedKind == "original") descriptor.kind else forcedKind)
            .put("label", if (forcedKind == "original") "Orijinal Kaynak" else descriptor.label)
            .put("codec", descriptor.codec)
            .put("url", url)
            .put("downloadUrl", url)
            .put("downloadable", true)
            .put("spatial", false)
            .put("generated", false)
    }

    private fun refreshReleaseAvailability(catalog: JSONObject, release: JSONObject) {
        val tracks = catalog.optJSONArray("tracks") ?: JSONArray()
        val refs = release.optJSONArray("tracks") ?: JSONArray()
        var available = 0
        for (i in 0 until refs.length()) {
            val id = refs.optJSONObject(i)?.optString("trackId").orEmpty()
            val track = findById(tracks, id)
            if (track != null && isPlayable(track)) available++
        }
        val total = refs.length()
        val status = when {
            total > 0 && available == total -> "published"
            available > 0 -> "partial"
            else -> "upcoming"
        }
        release.put("status", status).put("availableTrackCount", available).put("totalTrackCount", total)
    }

    private fun isPlayable(track: JSONObject): Boolean = track.optBoolean("playable", false) || (track.optJSONArray("sources")?.length() ?: 0) > 0
    private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray().also { put(name, it) }
    private fun trackRef(id: String, disc: Int, position: Int): JSONObject = JSONObject()
        .put("trackId", id)
        .put("disc", disc.coerceAtLeast(1))
        .put("position", position.coerceAtLeast(1))
    private fun Int.ifBlankPosition(fallback: Int): Int = if (this > 0) this else fallback

    private fun existingByIsrc(tracks: JSONArray): MutableMap<String, JSONObject> = buildMap {
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            normalizeIsrc(track.optString("isrc")).takeIf(String::isNotBlank)?.let { put(it, track) }
        }
    }.toMutableMap()

    private fun prependUnique(array: JSONArray, id: String): JSONArray {
        val result = JSONArray().put(id)
        for (i in 0 until array.length()) if (array.optString(i) != id) result.put(array.optString(i))
        return result
    }

    private fun findById(array: JSONArray, id: String): JSONObject? {
        for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { it.optString("id") == id }?.let { return it }
        return null
    }

    private fun findArtistId(artists: JSONArray, name: String): String? {
        for (i in 0 until artists.length()) {
            val row = artists.optJSONObject(i) ?: continue
            if (row.optString("name").equals(name.trim(), ignoreCase = true)) return row.optString("id").takeIf(String::isNotBlank)
        }
        return null
    }

    private fun ensureArtist(artists: JSONArray, name: String): String {
        findArtistId(artists, name)?.let { return it }
        val id = opaqueId("artist")
        artists.put(JSONObject().put("id", id).put("slug", slugify(name)).put("name", name.trim()).put("image", "").put("heroImage", "").put("backgroundImage", "").put("backgroundVideoUrl", "").put("bio", ""))
        return id
    }
}
