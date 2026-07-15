package com.apexlions.aurorastudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class PendingTrack(
    val id: String,
    val title: String,
    val isrc: String,
    val releaseTitle: String,
    val disc: Int,
    val position: Int,
)

internal class AudioCompletionManager(
    private val context: Context,
    private val config: StudioConfig,
) {
    private val http = AuroraHttp()
    private val github = GitHubCatalogClient(config, http)
    private val hub = HuggingFaceUploader(context, config, http)

    fun load(): CatalogSnapshot = github.loadCatalog()

    fun pendingTracks(snapshot: CatalogSnapshot): List<PendingTrack> {
        val root = snapshot.json
        val trackMap = buildMap<String, JSONObject> {
            val tracks = root.optJSONArray("tracks") ?: JSONArray()
            for (index in 0 until tracks.length()) {
                val row = tracks.optJSONObject(index) ?: continue
                if ((row.optJSONArray("sources")?.length() ?: 0) == 0 || !row.optBoolean("playable", false)) {
                    put(row.optString("id"), row)
                }
            }
        }
        val result = mutableListOf<PendingTrack>()
        val releases = root.optJSONArray("releases") ?: JSONArray()
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.optJSONObject(releaseIndex) ?: continue
            val refs = release.optJSONArray("tracks") ?: JSONArray()
            for (refIndex in 0 until refs.length()) {
                val ref = refs.optJSONObject(refIndex) ?: continue
                val track = trackMap[ref.optString("trackId")] ?: continue
                result += PendingTrack(
                    id = track.optString("id"),
                    title = track.optString("title", "İsimsiz"),
                    isrc = track.optString("isrc"),
                    releaseTitle = release.optString("title", "Yayın"),
                    disc = ref.optInt("disc", 1),
                    position = ref.optInt("position", refIndex + 1),
                )
            }
        }
        return result.distinctBy(PendingTrack::id)
            .sortedWith(compareBy<PendingTrack> { it.releaseTitle.lowercase() }.thenBy { it.disc }.thenBy { it.position })
    }

    fun attachAudioBatch(
        snapshot: CatalogSnapshot,
        assignments: Map<String, AssetDraft>,
        progress: (String, Float) -> Unit,
    ): CatalogSnapshot {
        require(assignments.isNotEmpty()) { "En az bir şarkıya ses dosyası eşleyin." }
        val root = JSONObject(snapshot.json.toString())
        root.put("schemaVersion", maxOf(5, root.optInt("schemaVersion", 1)))
        val tracks = root.array("tracks")
        val jobs = root.array("qualityJobs")
        val storage = hub.loadStorageIndex()
        val prepared = mutableListOf<PreparedUpload>()

        assignments.entries.forEachIndexed { index, (trackId, asset) ->
            val track = tracks.findById(trackId) ?: error("Şarkı bulunamadı: $trackId")
            if ((track.optJSONArray("sources")?.length() ?: 0) > 0 && track.optBoolean("playable", false)) {
                return@forEachIndexed
            }
            progress("${track.optString("title")}: kaynak dosya hazırlanıyor…", .03f + index.toFloat() / assignments.size.coerceAtLeast(1) * .12f)
            val remote = hub.allocate(storage, "audio-source", extensionOf(asset.displayName))
            prepared += hub.prepare(asset.uri, asset.displayName, remote)
            val sourceUrl = hub.resolveUrl(remote)
            val sources = track.optJSONArray("sources") ?: JSONArray().also { track.put("sources", it) }
            sources.put(sourceJson(asset, sourceUrl))
            jobs.put(qualityJob(trackId, remote, sourceUrl))
            track
                .put("playable", true)
                .put("availability", "available")
                .put("qualityState", "queued")
        }

        hub.uploadAndCommit(prepared, storage, "Aurora Music: Yakında şarkıların kaynak sesleri", progress)
        refreshAllReleaseStates(root)
        progress("GitHub kataloğu güncelleniyor…", .96f)
        github.commitCatalog(root, snapshot.sha, "Aurora Music: Yakında şarkılara toplu ses ekle")
        progress("Ses tamamlama bitti", 1f)
        return github.loadCatalog()
    }

    fun attachLyricsBatch(
        snapshot: CatalogSnapshot,
        assignments: Map<String, Pair<String, Boolean>>,
    ): CatalogSnapshot {
        require(assignments.isNotEmpty()) { "En az bir söz dosyası eşleyin." }
        val root = JSONObject(snapshot.json.toString())
        val tracks = root.array("tracks")
        assignments.forEach { (trackId, content) ->
            val track = tracks.findById(trackId) ?: error("Şarkı bulunamadı: $trackId")
            if (content.second) track.put("syncedLyrics", content.first) else track.put("lyrics", content.first)
        }
        github.commitCatalog(root, snapshot.sha, "Aurora Music: toplu söz dosyalarını eşleştir")
        return github.loadCatalog()
    }

    private fun sourceJson(asset: AssetDraft, url: String): JSONObject {
        val descriptor = sourceDescriptor(asset.displayName)
        return JSONObject()
            .put("id", opaqueId("audio"))
            .put("kind", "original")
            .put("label", "Orijinal Kaynak")
            .put("codec", descriptor.codec)
            .put("url", url)
            .put("downloadUrl", url)
            .put("downloadable", true)
            .put("spatial", false)
            .put("generated", false)
    }

    private fun qualityJob(trackId: String, sourcePath: String, sourceUrl: String): JSONObject = JSONObject()
        .put("id", opaqueId("quality"))
        .put("trackId", trackId)
        .put("sourcePath", sourcePath)
        .put("sourceUrl", sourceUrl)
        .put("status", "queued")
        .put("presets", JSONArray(listOf("standard", "high", "lossless")))
        .put("createdAt", nowIso())

    private fun refreshAllReleaseStates(root: JSONObject) {
        val tracks = root.optJSONArray("tracks") ?: JSONArray()
        val lookup = buildMap<String, JSONObject> {
            for (index in 0 until tracks.length()) {
                val row = tracks.optJSONObject(index) ?: continue
                put(row.optString("id"), row)
            }
        }
        val releases = root.optJSONArray("releases") ?: JSONArray()
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.optJSONObject(releaseIndex) ?: continue
            val refs = release.optJSONArray("tracks") ?: JSONArray()
            var available = 0
            for (refIndex in 0 until refs.length()) {
                val id = refs.optJSONObject(refIndex)?.optString("trackId").orEmpty()
                val track = lookup[id] ?: continue
                if (track.optBoolean("playable", false) && (track.optJSONArray("sources")?.length() ?: 0) > 0) available++
            }
            val total = refs.length()
            release
                .put("status", when {
                    total > 0 && available == total -> "published"
                    available > 0 -> "partial"
                    else -> "upcoming"
                })
                .put("availableTrackCount", available)
                .put("totalTrackCount", total)
        }
    }

    private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray().also { put(name, it) }

    private fun JSONArray.findById(id: String): JSONObject? {
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            if (row.optString("id") == id) return row
        }
        return null
    }
}
