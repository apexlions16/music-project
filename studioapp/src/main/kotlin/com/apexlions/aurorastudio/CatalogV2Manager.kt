package com.apexlions.aurorastudio

import android.content.Context
import android.net.Uri
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal class CatalogV2Manager(
    private val context: Context,
    private val config: StudioConfig,
) {
    private val http = AuroraHttp()
    private val github = GitHubCatalogClient(config, http)
    private val hub = HuggingFaceUploader(context, config, http)

    fun loadCatalog(): CatalogSnapshot = github.loadCatalog()

    fun fetchRemoteCover(url: String): AssetDraft {
        require(url.startsWith("https://")) { "Görsel adresi HTTPS olmalı." }
        val request = Request.Builder().url(url).header("User-Agent", "AuroraStudioMobile/0.4.0").get().build()
        return http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Görsel indirilemedi: HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val extension = when {
                "png" in type -> "png"
                "webp" in type -> "webp"
                else -> "jpg"
            }
            val file = File(context.cacheDir, "aurora-image-${System.currentTimeMillis()}.$extension")
            response.body?.byteStream()?.use { input -> file.outputStream().use(input::copyTo) }
                ?: error("Görsel verisi boş döndü.")
            AssetDraft(Uri.fromFile(file), file.name, file.length())
        }
    }

    fun publishRelease(
        snapshot: CatalogSnapshot,
        draft: V2ReleaseDraft,
        progress: (String, Float) -> Unit,
    ): PublishResult {
        require(draft.title.isNotBlank()) { "Yayın adı gerekli." }
        require(draft.mainArtist.isNotBlank()) { "Ana sanatçı gerekli." }
        require(draft.tracks.isNotEmpty()) { "En az bir metadata parçası gerekli." }
        require(draft.coverAsset != null || draft.coverUrl.isNotBlank()) { "Kapak dosyası veya Spotify kapağı gerekli." }

        val catalog = JSONObject(snapshot.json.toString())
        catalog.put("schemaVersion", maxOf(5, catalog.optInt("schemaVersion", 1)))
        val artists = catalog.array("artists")
        val tracks = catalog.array("tracks")
        val releases = catalog.array("releases")
        val featured = catalog.array("featuredReleaseIds")
        val jobs = catalog.array("qualityJobs")
        val storage = hub.loadStorageIndex()
        val pending = mutableListOf<Pending>()
        val mainArtistId = ensureArtist(artists, draft.mainArtist)
        localizeArtistArtwork(artists, mainArtistId, draft.mainArtist, storage, pending, progress)
        val existingByIsrc = existingByIsrc(tracks)

        var coverUrl = draft.coverUrl.trim()
        val coverAsset = draft.coverAsset ?: coverUrl
            .takeIf { it.isNotBlank() && !isHuggingFaceUrl(it) }
            ?.let {
                progress("Spotify kapağı indiriliyor…", .02f)
                fetchRemoteCover(it)
            }
        coverAsset?.let { asset ->
            val remote = hub.allocate(storage, "artwork", extensionOf(asset.displayName))
            pending += Pending(asset, remote)
            coverUrl = hub.resolveUrl(remote)
        }
        require(isHuggingFaceUrl(coverUrl)) {
            "Kapak Hugging Face'e taşınamadı. Spotify kapağını yeniden içe aktarın veya yerel kapak seçin."
        }

        val releaseRows = JSONArray()
        var newCount = 0
        var reusedCount = 0
        draft.tracks.forEachIndexed { index, row ->
            val normalized = normalizeIsrc(row.isrc)
            val existing = normalized.takeIf(String::isNotBlank)?.let(existingByIsrc::get)
            if (existing != null) {
                reusedCount++
                releaseRows.put(trackRef(existing.getString("id"), index + 1))
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
                .put("durationSeconds", 0)
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
            releaseRows.put(trackRef(trackId, index + 1))
            newCount++
        }

        uploadPending(pending, storage, "Aurora Music: ${draft.title}", progress)
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
            .put("tracks", releaseRows)
        releases.put(release)
        refreshReleaseAvailability(catalog, release)
        if (draft.featured) catalog.put("featuredReleaseIds", prependUnique(featured, releaseId))

        progress("GitHub kataloğu commit ediliyor…", .96f)
        github.commitCatalog(catalog, snapshot.sha, "Aurora Music: ${draft.title} yayınını Spotify metadata ile ekle")
        progress("Yayın tamamlandı", 1f)
        return PublishResult(newCount, reusedCount, releaseId)
    }

    fun updateTrack(
        snapshot: CatalogSnapshot,
        draft: ExistingTrackDraft,
        audio: AssetDraft?,
        progress: (String, Float) -> Unit,
    ) {
        require(draft.title.isNotBlank()) { "Şarkı adı gerekli." }
        require(draft.primaryArtist.isNotBlank()) { "Ana sanatçı gerekli." }
        val catalog = JSONObject(snapshot.json.toString())
        catalog.put("schemaVersion", maxOf(5, catalog.optInt("schemaVersion", 1)))
        val tracks = catalog.array("tracks")
        val artists = catalog.array("artists")
        val jobs = catalog.array("qualityJobs")
        val track = findById(tracks, draft.id) ?: error("Düzenlenecek şarkı bulunamadı.")

        val normalized = normalizeIsrc(draft.isrc)
        if (normalized.isNotBlank()) {
            for (i in 0 until tracks.length()) {
                val other = tracks.optJSONObject(i) ?: continue
                if (other.optString("id") != draft.id && normalizeIsrc(other.optString("isrc")) == normalized) {
                    error("Bu ISRC başka bir şarkıda zaten var: ${other.optString("title")}")
                }
            }
        }

        val primaryId = ensureArtist(artists, draft.primaryArtist)
        val featureIds = mutableListOf<String>()
        val featureNames = mutableListOf<String>()
        splitNames(draft.featuredArtists).forEach { name ->
            findArtistId(artists, name)?.let(featureIds::add) ?: featureNames.add(name)
        }
        track
            .put("title", draft.title.trim())
            .put("slug", slugify(draft.title))
            .put("isrc", draft.isrc.trim())
            .put("artistIds", JSONArray((listOf(primaryId) + featureIds).distinct()))
            .put("primaryArtistIds", JSONArray().put(primaryId))
            .put("featuredArtistIds", JSONArray(featureIds.distinct()))
            .put("featuredArtistNames", JSONArray(featureNames.distinctBy { it.lowercase(Locale.ROOT) }))
            .put("explicit", draft.explicit)
            .put("lyrics", draft.lyrics)
            .put("syncedLyrics", draft.syncedLyrics)
            .put("credits", parseCreditsText(draft.creditsText))

        audio?.let { asset ->
            val storage = hub.loadStorageIndex()
            val remote = hub.allocate(storage, "audio-source", extensionOf(asset.displayName))
            uploadPending(listOf(Pending(asset, remote)), storage, "Aurora Music: ${draft.title} kaynak sesi", progress)
            val sourceUrl = hub.resolveUrl(remote)
            val sources = track.optJSONArray("sources") ?: JSONArray().also { track.put("sources", it) }
            sources.put(sourceJson(asset.displayName, sourceUrl, "original"))
            jobs.put(qualityJob(draft.id, remote, sourceUrl))
            track.put("playable", true).put("availability", "available").put("qualityState", "queued")
        }

        val releases = catalog.array("releases")
        for (i in 0 until releases.length()) refreshReleaseAvailability(catalog, releases.optJSONObject(i) ?: continue)
        progress("Düzenleme GitHub'a kaydediliyor…", .96f)
        github.commitCatalog(catalog, snapshot.sha, "Aurora Music: ${draft.title} metadata ve ses kaynağını güncelle")
        progress("Şarkı güncellendi", 1f)
    }

    fun trackDraft(catalog: JSONObject, track: JSONObject): ExistingTrackDraft {
        val artists = catalog.optJSONArray("artists") ?: JSONArray()
        val primaryIds = track.optJSONArray("primaryArtistIds") ?: track.optJSONArray("artistIds") ?: JSONArray()
        val primary = artistName(artists, primaryIds.optString(0))
        val featureNames = buildList {
            val ids = track.optJSONArray("featuredArtistIds") ?: JSONArray()
            for (i in 0 until ids.length()) artistName(artists, ids.optString(i)).takeIf(String::isNotBlank)?.let(::add)
            val free = track.optJSONArray("featuredArtistNames") ?: JSONArray()
            for (i in 0 until free.length()) free.optString(i).takeIf(String::isNotBlank)?.let(::add)
        }.distinctBy { it.lowercase(Locale.ROOT) }
        return ExistingTrackDraft(
            id = track.optString("id"),
            title = track.optString("title"),
            isrc = track.optString("isrc"),
            primaryArtist = primary,
            featuredArtists = featureNames.joinToString(", "),
            explicit = track.optBoolean("explicit"),
            lyrics = track.optString("lyrics"),
            syncedLyrics = track.optString("syncedLyrics"),
            creditsText = creditsToText(track.optJSONArray("credits")),
            playable = isPlayable(track),
        )
    }

    private data class Pending(val asset: AssetDraft, val remote: String)

    private fun uploadPending(pending: List<Pending>, storage: JSONObject, message: String, progress: (String, Float) -> Unit) {
        if (pending.isEmpty()) return
        val prepared = pending.distinctBy { it.remote }.mapIndexed { index, row ->
            progress("${row.asset.displayName} hazırlanıyor…", .04f + index.toFloat() / pending.size * .10f)
            hub.prepare(row.asset.uri, row.asset.displayName, row.remote)
        }
        hub.uploadAndCommit(prepared, storage, message, progress)
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
        progress("$artistName sanatçı görseli indiriliyor…", .03f)
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

    private fun trackRef(id: String, position: Int): JSONObject = JSONObject().put("trackId", id).put("disc", 1).put("position", position)

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

    private fun artistName(artists: JSONArray, id: String): String {
        for (i in 0 until artists.length()) {
            val row = artists.optJSONObject(i) ?: continue
            if (row.optString("id") == id) return row.optString("name")
        }
        return ""
    }
}
