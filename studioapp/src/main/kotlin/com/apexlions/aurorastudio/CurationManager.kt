package com.apexlions.aurorastudio

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal data class CurationArtist(
    val id: String,
    val name: String,
    val popularTrackIds: List<String>,
)

internal data class CurationTrack(
    val id: String,
    val title: String,
    val artistIds: List<String>,
    val playable: Boolean,
)

internal data class CurationArtistList(
    val id: String,
    val artistId: String,
    val title: String,
    val description: String,
    val cover: String,
    val trackIds: List<String>,
)

internal data class CurationHomeSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val layout: String,
    val itemIds: List<String>,
)

internal data class CurationData(
    val artists: List<CurationArtist>,
    val tracks: List<CurationTrack>,
    val releases: List<Pair<String, String>>,
    val artistLists: List<CurationArtistList>,
    val homeSections: List<CurationHomeSection>,
)

internal class CurationManager(
    private val config: StudioConfig,
) {
    private val http = AuroraHttp()
    private val github = GitHubCatalogClient(config, http)

    fun load(): CatalogSnapshot = github.loadCatalog()

    fun parse(snapshot: CatalogSnapshot): CurationData {
        val root = snapshot.json
        val artists = root.optJSONArray("artists").objects().map { row ->
            CurationArtist(
                id = row.optString("id"),
                name = row.optString("name", "Adsız"),
                popularTrackIds = row.optJSONArray("popularTrackIds").strings(),
            )
        }
        val tracks = root.optJSONArray("tracks").objects().map { row ->
            CurationTrack(
                id = row.optString("id"),
                title = row.optString("title", "Adsız"),
                artistIds = (
                    row.optJSONArray("primaryArtistIds").strings().ifEmpty {
                        row.optJSONArray("artistIds").strings()
                    } + row.optJSONArray("featuredArtistIds").strings()
                ).distinct(),
                playable = row.optBoolean("playable", (row.optJSONArray("sources")?.length() ?: 0) > 0),
            )
        }
        val releases = root.optJSONArray("releases").objects().map {
            it.optString("id") to it.optString("title", "Adsız")
        }
        val lists = root.optJSONArray("artistLists").objects().map { row ->
            CurationArtistList(
                id = row.optString("id"),
                artistId = row.optString("artistId"),
                title = row.optString("title", "Sanatçı Seçkisi"),
                description = row.optString("description"),
                cover = row.optString("cover"),
                trackIds = row.optJSONArray("trackIds").strings(),
            )
        }
        val sections = root.optJSONArray("homeSections").objects().map { row ->
            val type = row.optString("type", "releases")
            CurationHomeSection(
                id = row.optString("id"),
                title = row.optString("title", "Bölüm"),
                subtitle = row.optString("subtitle"),
                type = type,
                layout = row.optString("layout", "horizontal"),
                itemIds = row.optJSONArray(sectionKey(type)).strings(),
            )
        }
        return CurationData(artists, tracks, releases, lists, sections)
    }

    fun savePopular(snapshot: CatalogSnapshot, artistId: String, trackIds: List<String>): CatalogSnapshot {
        val root = copy(snapshot.json)
        val artist = root.optJSONArray("artists").findObject(artistId)
            ?: error("Sanatçı bulunamadı.")
        artist.put("popularTrackIds", JSONArray(trackIds.distinct().take(5)))
        return commit(root, snapshot.sha, "Aurora: sanatçı popüler sırasını güncelle")
    }

    fun saveArtistList(snapshot: CatalogSnapshot, value: CurationArtistList): CatalogSnapshot {
        require(value.artistId.isNotBlank()) { "Sanatçı seçilmelidir." }
        require(value.title.isNotBlank()) { "Liste başlığı gereklidir." }
        val root = copy(snapshot.json)
        val lists = root.array("artistLists")
        val id = value.id.ifBlank { "artist_list_${UUID.randomUUID().toString().replace("-", "")}" }
        val current = lists.findObject(id)
        val row = (current ?: JSONObject().also { lists.put(it) })
            .put("id", id)
            .put("artistId", value.artistId)
            .put("title", value.title.trim())
            .put("description", value.description)
            .put("cover", value.cover.trim())
            .put("trackIds", JSONArray(value.trackIds.distinct()))
        root.optJSONArray("artists").objects().forEach { artist ->
            val ids = artist.optJSONArray("listIds").strings().filterNot { it == id }.toMutableList()
            if (artist.optString("id") == value.artistId) ids += id
            artist.put("listIds", JSONArray(ids.distinct()))
        }
        return commit(root, snapshot.sha, "Aurora: ${row.optString("title")} sanatçı listesini güncelle")
    }

    fun deleteArtistList(snapshot: CatalogSnapshot, id: String): CatalogSnapshot {
        val root = copy(snapshot.json)
        root.put("artistLists", JSONArray(root.optJSONArray("artistLists").objects().filterNot { it.optString("id") == id }))
        root.optJSONArray("artists").objects().forEach { artist ->
            artist.put("listIds", JSONArray(artist.optJSONArray("listIds").strings().filterNot { it == id }))
        }
        root.optJSONArray("homeSections").objects().forEach { section ->
            section.put("listIds", JSONArray(section.optJSONArray("listIds").strings().filterNot { it == id }))
        }
        return commit(root, snapshot.sha, "Aurora: sanatçı listesini sil")
    }

    fun saveHomeSection(snapshot: CatalogSnapshot, value: CurationHomeSection): CatalogSnapshot {
        require(value.title.isNotBlank()) { "Bölüm başlığı gereklidir." }
        val root = copy(snapshot.json)
        val sections = root.array("homeSections")
        val id = value.id.ifBlank { "home_${UUID.randomUUID().toString().replace("-", "")}" }
        val current = sections.findObject(id)
        val row = (current ?: JSONObject().also { sections.put(it) })
            .put("id", id)
            .put("title", value.title.trim())
            .put("subtitle", value.subtitle.trim())
            .put("type", value.type)
            .put("layout", value.layout)
            .put("releaseIds", JSONArray())
            .put("artistIds", JSONArray())
            .put("trackIds", JSONArray())
            .put("listIds", JSONArray())
        row.put(sectionKey(value.type), JSONArray(value.itemIds.distinct()))
        return commit(root, snapshot.sha, "Aurora: ${row.optString("title")} ana sayfa bölümünü güncelle")
    }

    fun deleteHomeSection(snapshot: CatalogSnapshot, id: String): CatalogSnapshot {
        val root = copy(snapshot.json)
        root.put("homeSections", JSONArray(root.optJSONArray("homeSections").objects().filterNot { it.optString("id") == id }))
        return commit(root, snapshot.sha, "Aurora: ana sayfa bölümünü sil")
    }

    fun moveHomeSection(snapshot: CatalogSnapshot, id: String, direction: Int): CatalogSnapshot {
        val root = copy(snapshot.json)
        val rows = root.optJSONArray("homeSections").objects().toMutableList()
        val index = rows.indexOfFirst { it.optString("id") == id }
        val target = index + direction
        if (index !in rows.indices || target !in rows.indices) return snapshot
        val moving = rows.removeAt(index)
        rows.add(target, moving)
        root.put("homeSections", JSONArray(rows))
        return commit(root, snapshot.sha, "Aurora: ana sayfa bölüm sırasını güncelle")
    }

    private fun commit(root: JSONObject, sha: String, message: String): CatalogSnapshot {
        root.put("schemaVersion", maxOf(5, root.optInt("schemaVersion", 1)))
        root.put("updatedAt", Instant.now().toString())
        github.commitCatalog(root, sha, message)
        return github.loadCatalog()
    }

    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())

    private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray().also { put(name, it) }

    companion object {
        fun sectionKey(type: String): String = when (type) {
            "artists" -> "artistIds"
            "tracks" -> "trackIds"
            "lists" -> "listIds"
            else -> "releaseIds"
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = buildList {
    val source = this@objects ?: return@buildList
    for (index in 0 until source.length()) source.optJSONObject(index)?.let(::add)
}

private fun JSONArray?.strings(): List<String> = buildList {
    val source = this@strings ?: return@buildList
    for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun JSONArray?.findObject(id: String): JSONObject? = this.objects().firstOrNull { it.optString("id") == id }
