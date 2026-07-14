package com.apexlions.music

import org.json.JSONObject

data class Brand(val name: String, val subtitle: String, val logoText: String)
data class Artist(val id: String, val slug: String, val name: String, val image: String, val heroImage: String, val bio: String)
data class Credit(val role: String, val names: List<String>)
data class AudioSource(
    val id: String,
    val kind: String,
    val label: String,
    val codec: String,
    val url: String,
    val downloadUrl: String,
    val downloadable: Boolean,
    val bitrateKbps: Int?,
    val sampleRateKhz: Double?,
    val bitDepth: Int?,
    val channels: String?,
    val spatial: Boolean
)
data class Track(
    val id: String,
    val slug: String,
    val title: String,
    val artistIds: List<String>,
    val durationSeconds: Int,
    val lyrics: String,
    val isrc: String,
    val credits: List<Credit>,
    val sources: List<AudioSource>
)
data class ReleaseTrack(val trackId: String, val disc: Int, val position: Int)
data class Release(
    val id: String,
    val slug: String,
    val title: String,
    val type: String,
    val artistIds: List<String>,
    val releaseDate: String,
    val cover: String,
    val heroImage: String,
    val label: String,
    val copyright: String,
    val description: String,
    val tracks: List<ReleaseTrack>
)
data class Catalog(
    val schemaVersion: Int,
    val updatedAt: String,
    val brand: Brand,
    val featuredReleaseIds: List<String>,
    val artists: List<Artist>,
    val tracks: List<Track>,
    val releases: List<Release>
) {
    fun artist(id: String) = artists.firstOrNull { it.id == id }
    fun track(id: String) = tracks.firstOrNull { it.id == id }
    fun release(id: String) = releases.firstOrNull { it.id == id }
    fun artistNames(ids: List<String>) = ids.mapNotNull { artist(it)?.name }.joinToString(", ")
    fun releaseTracks(release: Release) = release.tracks.sortedWith(compareBy<ReleaseTrack> { it.disc }.thenBy { it.position }).mapNotNull { track(it.trackId) }
}

object CatalogParser {
    fun parse(text: String): Catalog {
        val root = JSONObject(text)
        val brandObject = root.getJSONObject("brand")
        val artists = root.getJSONArray("artists").toObjectList { item ->
            Artist(
                id = item.getString("id"),
                slug = item.optString("slug"),
                name = item.getString("name"),
                image = item.optString("image"),
                heroImage = item.optString("heroImage", item.optString("image")),
                bio = item.optString("bio")
            )
        }
        val tracks = root.getJSONArray("tracks").toObjectList { item ->
            val credits = item.optJSONArray("credits")?.toObjectList { credit ->
                Credit(credit.optString("role"), credit.optJSONArray("names").toStringList())
            }.orEmpty()
            val sources = item.getJSONArray("sources").toObjectList { source ->
                AudioSource(
                    id = source.getString("id"),
                    kind = source.optString("kind", "standard"),
                    label = source.optString("label", "Standart"),
                    codec = source.optString("codec"),
                    url = source.getString("url"),
                    downloadUrl = source.optString("downloadUrl", source.getString("url")),
                    downloadable = source.optBoolean("downloadable", true),
                    bitrateKbps = source.optIntOrNull("bitrateKbps"),
                    sampleRateKhz = source.optDoubleOrNull("sampleRateKhz"),
                    bitDepth = source.optIntOrNull("bitDepth"),
                    channels = source.optString("channels").takeIf { it.isNotBlank() },
                    spatial = source.optBoolean("spatial", false)
                )
            }
            Track(
                id = item.getString("id"),
                slug = item.optString("slug"),
                title = item.getString("title"),
                artistIds = item.getJSONArray("artistIds").toStringList(),
                durationSeconds = item.optInt("durationSeconds"),
                lyrics = item.optString("lyrics"),
                isrc = item.optString("isrc"),
                credits = credits,
                sources = sources
            )
        }
        val releases = root.getJSONArray("releases").toObjectList { item ->
            Release(
                id = item.getString("id"),
                slug = item.optString("slug"),
                title = item.getString("title"),
                type = item.optString("type", "album"),
                artistIds = item.getJSONArray("artistIds").toStringList(),
                releaseDate = item.optString("releaseDate"),
                cover = item.optString("cover"),
                heroImage = item.optString("heroImage", item.optString("cover")),
                label = item.optString("label"),
                copyright = item.optString("copyright"),
                description = item.optString("description"),
                tracks = item.getJSONArray("tracks").toObjectList { row ->
                    ReleaseTrack(row.getString("trackId"), row.optInt("disc", 1), row.optInt("position", 1))
                }
            )
        }
        return Catalog(
            schemaVersion = root.optInt("schemaVersion", 1),
            updatedAt = root.optString("updatedAt"),
            brand = Brand(brandObject.optString("name", "Müzik Projesi"), brandObject.optString("subtitle"), brandObject.optString("logoText", "M")),
            featuredReleaseIds = root.optJSONArray("featuredReleaseIds").toStringList(),
            artists = artists,
            tracks = tracks,
            releases = releases
        )
    }
}

private inline fun <T> org.json.JSONArray.toObjectList(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun org.json.JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.optDoubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null

fun releaseTypeLabel(type: String) = when (type.lowercase()) {
    "single" -> "Single"
    "ep" -> "EP"
    else -> "Albüm"
}

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
fun formatMillis(value: Long): String {
    val total = (value.coerceAtLeast(0) / 1000).toInt()
    return formatDuration(total)
}

fun qualityRank(kind: String) = when (kind.lowercase()) {
    "dolby_atmos" -> 5
    "hires" -> 4
    "lossless" -> 3
    "high" -> 2
    else -> 1
}
