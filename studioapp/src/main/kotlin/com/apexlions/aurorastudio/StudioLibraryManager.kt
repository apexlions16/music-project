package com.apexlions.aurorastudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class LibraryTrack(
    val id: String,
    val title: String,
    val isrc: String,
    val playable: Boolean,
    val availability: String,
    val primaryArtist: String,
    val featuredArtists: String,
    val explicit: Boolean,
    val lyrics: String,
    val syncedLyrics: String,
    val creditsText: String,
)

internal data class LibraryRelease(
    val id: String,
    val title: String,
    val type: String,
    val releaseDate: String,
    val cover: String,
    val animatedCoverUrl: String,
    val label: String,
    val copyright: String,
    val description: String,
    val status: String,
    val tracks: List<LibraryTrack>,
)

internal class StudioLibraryManager(
    private val context: Context,
    private val config: StudioConfig,
) {
    private val http = AuroraHttp()
    private val github = GitHubCatalogClient(config, http)

    fun load(): CatalogSnapshot = github.loadCatalog()

    fun parse(snapshot: CatalogSnapshot): List<LibraryRelease> {
        val root = snapshot.json
        val artists = root.optJSONArray("artists") ?: JSONArray()
        val tracks = root.optJSONArray("tracks") ?: JSONArray()
        val trackLookup = buildMap<String, JSONObject> {
            for (index in 0 until tracks.length()) {
                val row = tracks.optJSONObject(index) ?: continue
                put(row.optString("id"), row)
            }
        }
        val releases = root.optJSONArray("releases") ?: JSONArray()
        return buildList {
            for (releaseIndex in 0 until releases.length()) {
                val release = releases.optJSONObject(releaseIndex) ?: continue
                val refs = release.optJSONArray("tracks") ?: JSONArray()
                val releaseTracks = buildList {
                    for (refIndex in 0 until refs.length()) {
                        val ref = refs.optJSONObject(refIndex) ?: continue
                        val track = trackLookup[ref.optString("trackId")] ?: continue
                        val primaryIds = track.optJSONArray("primaryArtistIds") ?: track.optJSONArray("artistIds") ?: JSONArray()
                        val featureIds = track.optJSONArray("featuredArtistIds") ?: JSONArray()
                        val featureNames = mutableListOf<String>()
                        for (i in 0 until featureIds.length()) artistName(artists, featureIds.optString(i)).takeIf(String::isNotBlank)?.let(featureNames::add)
                        val free = track.optJSONArray("featuredArtistNames") ?: JSONArray()
                        for (i in 0 until free.length()) free.optString(i).takeIf(String::isNotBlank)?.let(featureNames::add)
                        add(
                            LibraryTrack(
                                id = track.optString("id"),
                                title = track.optString("title", "İsimsiz"),
                                isrc = track.optString("isrc"),
                                playable = track.optBoolean("playable", (track.optJSONArray("sources")?.length() ?: 0) > 0),
                                availability = track.optString("availability", "upcoming"),
                                primaryArtist = artistName(artists, primaryIds.optString(0)),
                                featuredArtists = featureNames.distinct().joinToString(", "),
                                explicit = track.optBoolean("explicit", false),
                                lyrics = track.optString("lyrics"),
                                syncedLyrics = track.optString("syncedLyrics"),
                                creditsText = creditsToText(track.optJSONArray("credits")),
                            ),
                        )
                    }
                }
                add(
                    LibraryRelease(
                        id = release.optString("id"),
                        title = release.optString("title", "İsimsiz Yayın"),
                        type = release.optString("type", "album"),
                        releaseDate = release.optString("releaseDate"),
                        cover = release.optString("cover"),
                        animatedCoverUrl = release.optString("animatedCoverUrl"),
                        label = release.optString("label"),
                        copyright = release.optString("copyright"),
                        description = release.optString("description"),
                        status = release.optString("status", inferStatus(releaseTracks)),
                        tracks = releaseTracks,
                    ),
                )
            }
        }.sortedByDescending(LibraryRelease::releaseDate)
    }

    fun saveRelease(snapshot: CatalogSnapshot, value: LibraryRelease): CatalogSnapshot {
        require(value.title.isNotBlank()) { "Yayın adı gereklidir." }
        val root = JSONObject(snapshot.json.toString())
        val release = root.array("releases").findById(value.id) ?: error("Yayın bulunamadı.")
        release
            .put("title", value.title.trim())
            .put("slug", slugify(value.title))
            .put("type", value.type)
            .put("releaseDate", value.releaseDate.trim())
            .put("cover", value.cover.trim())
            .put("heroImage", value.cover.trim())
            .put("animatedCoverUrl", value.animatedCoverUrl.trim())
            .put("label", value.label)
            .put("copyright", value.copyright)
            .put("description", value.description)
        github.commitCatalog(root, snapshot.sha, "Aurora Music: ${value.title} yayın bilgilerini güncelle")
        return github.loadCatalog()
    }

    fun saveTrack(snapshot: CatalogSnapshot, value: LibraryTrack): CatalogSnapshot {
        val draft = ExistingTrackDraft(
            id = value.id,
            title = value.title,
            isrc = value.isrc,
            primaryArtist = value.primaryArtist,
            featuredArtists = value.featuredArtists,
            explicit = value.explicit,
            lyrics = value.lyrics,
            syncedLyrics = value.syncedLyrics.takeIf(String::isNotBlank)?.let(StudioLrcSupport::normalize).orEmpty(),
            creditsText = value.creditsText,
            playable = value.playable,
        )
        CatalogV2Manager(context, config).updateTrack(snapshot, draft, null) { _, _ -> }
        return github.loadCatalog()
    }

    fun removeTrackFromRelease(snapshot: CatalogSnapshot, releaseId: String, trackId: String): CatalogSnapshot {
        val root = JSONObject(snapshot.json.toString())
        val release = root.array("releases").findById(releaseId) ?: error("Yayın bulunamadı.")
        val refs = release.optJSONArray("tracks") ?: JSONArray()
        release.put("tracks", JSONArray(refs.objects().filterNot { it.optString("trackId") == trackId }))
        refreshReleaseState(root, release)
        github.commitCatalog(root, snapshot.sha, "Aurora Music: şarkıyı ${release.optString("title")} yayınından kaldır")
        return github.loadCatalog()
    }

    fun deleteTrackCompletely(snapshot: CatalogSnapshot, trackId: String): CatalogSnapshot {
        val root = JSONObject(snapshot.json.toString())
        val releases = root.array("releases")
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            val refs = release.optJSONArray("tracks") ?: JSONArray()
            release.put("tracks", JSONArray(refs.objects().filterNot { it.optString("trackId") == trackId }))
            refreshReleaseState(root, release)
        }
        root.put("tracks", JSONArray(root.array("tracks").objects().filterNot { it.optString("id") == trackId }))
        root.optJSONArray("artistLists")?.objects()?.forEach { list ->
            list.put("trackIds", JSONArray(list.optJSONArray("trackIds").strings().filterNot { it == trackId }))
        }
        root.optJSONArray("homeSections")?.objects()?.forEach { section ->
            section.put("trackIds", JSONArray(section.optJSONArray("trackIds").strings().filterNot { it == trackId }))
        }
        root.put("qualityJobs", JSONArray(root.optJSONArray("qualityJobs").objects().filterNot { it.optString("trackId") == trackId }))
        github.commitCatalog(root, snapshot.sha, "Aurora Music: şarkıyı katalogdan tamamen sil")
        return github.loadCatalog()
    }

    fun deleteRelease(snapshot: CatalogSnapshot, releaseId: String, deleteOrphanTracks: Boolean): CatalogSnapshot {
        val root = JSONObject(snapshot.json.toString())
        val releases = root.array("releases")
        val target = releases.findById(releaseId) ?: error("Yayın bulunamadı.")
        val targetTrackIds = target.optJSONArray("tracks").objects().map { it.optString("trackId") }.filter(String::isNotBlank)
        val remainingReleases = releases.objects().filterNot { it.optString("id") == releaseId }
        root.put("releases", JSONArray(remainingReleases))
        root.put("featuredReleaseIds", JSONArray(root.optJSONArray("featuredReleaseIds").strings().filterNot { it == releaseId }))
        root.optJSONArray("homeSections")?.objects()?.forEach { section ->
            section.put("releaseIds", JSONArray(section.optJSONArray("releaseIds").strings().filterNot { it == releaseId }))
        }
        if (deleteOrphanTracks) {
            val stillUsed = remainingReleases.flatMap { it.optJSONArray("tracks").objects() }.map { it.optString("trackId") }.toSet()
            val deleted = targetTrackIds.filterNot { it in stillUsed }.toSet()
            root.put("tracks", JSONArray(root.array("tracks").objects().filterNot { it.optString("id") in deleted }))
            root.put("qualityJobs", JSONArray(root.optJSONArray("qualityJobs").objects().filterNot { it.optString("trackId") in deleted }))
            root.optJSONArray("artistLists")?.objects()?.forEach { list ->
                list.put("trackIds", JSONArray(list.optJSONArray("trackIds").strings().filterNot { it in deleted }))
            }
        }
        github.commitCatalog(root, snapshot.sha, "Aurora Music: ${target.optString("title")} yayınını sil")
        return github.loadCatalog()
    }

    private fun refreshReleaseState(root: JSONObject, release: JSONObject) {
        val tracks = root.array("tracks")
        val lookup = tracks.objects().associateBy { it.optString("id") }
        val refs = release.optJSONArray("tracks") ?: JSONArray()
        var available = 0
        for (index in 0 until refs.length()) {
            val id = refs.optJSONObject(index)?.optString("trackId").orEmpty()
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

    private fun artistName(artists: JSONArray, id: String): String {
        for (index in 0 until artists.length()) {
            val row = artists.optJSONObject(index) ?: continue
            if (row.optString("id") == id) return row.optString("name")
        }
        return ""
    }

    private fun creditsToText(value: JSONArray?): String = value.objects().joinToString("\n") { row ->
        val names = row.optJSONArray("names").strings().joinToString(", ")
        "${row.optString("role")}: $names".trim()
    }

    private fun inferStatus(tracks: List<LibraryTrack>): String = when {
        tracks.isNotEmpty() && tracks.all(LibraryTrack::playable) -> "published"
        tracks.any(LibraryTrack::playable) -> "partial"
        else -> "upcoming"
    }

    private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray().also { put(name, it) }
    private fun JSONArray.findById(id: String): JSONObject? = objects().firstOrNull { it.optString("id") == id }
}

private fun JSONArray?.objects(): List<JSONObject> = buildList {
    val source = this@objects ?: return@buildList
    for (index in 0 until source.length()) source.optJSONObject(index)?.let(::add)
}

private fun JSONArray?.strings(): List<String> = buildList {
    val source = this@strings ?: return@buildList
    for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
}
