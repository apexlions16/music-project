package com.apexlions.music

import org.json.JSONArray
import org.json.JSONObject

data class Brand(val name: String, val subtitle: String, val logoText: String)
data class Features(
    val syncedLyrics: Boolean,
    val animatedCovers: Boolean,
    val artistBackgrounds: Boolean,
    val offlineDownloads: Boolean,
)

data class Artist(
    val id: String,
    val slug: String,
    val name: String,
    val image: String,
    val heroImage: String,
    val backgroundImage: String,
    val backgroundVideoUrl: String,
    val bio: String,
    val spotifyUrl: String,
    val popularTrackIds: List<String>,
    val listIds: List<String>,
)

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
    val spatial: Boolean,
)

data class Track(
    val id: String,
    val slug: String,
    val title: String,
    val artistIds: List<String>,
    val primaryArtistIds: List<String>,
    val featuredArtistIds: List<String>,
    val featuredArtistNames: List<String>,
    val durationSeconds: Int,
    val lyrics: String,
    val syncedLyrics: String,
    val isrc: String,
    val explicit: Boolean,
    val spotifyUrl: String,
    val credits: List<Credit>,
    val sources: List<AudioSource>,
    val playable: Boolean,
    val availability: String,
    val qualityState: String,
)

data class ReleaseTrack(val trackId: String, val disc: Int, val position: Int)

data class Release(
    val id: String,
    val slug: String,
    val title: String,
    val type: String,
    val artistIds: List<String>,
    val primaryArtistIds: List<String>,
    val releaseDate: String,
    val cover: String,
    val heroImage: String,
    val animatedCoverUrl: String,
    val label: String,
    val copyright: String,
    val description: String,
    val spotifyUrl: String,
    val tracks: List<ReleaseTrack>,
    val status: String,
    val availableTrackCount: Int,
    val totalTrackCount: Int,
)

data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val layout: String,
    val releaseIds: List<String>,
    val artistIds: List<String>,
    val trackIds: List<String>,
    val listIds: List<String>,
)

data class ArtistList(
    val id: String,
    val artistId: String,
    val title: String,
    val description: String,
    val cover: String,
    val trackIds: List<String>,
)

data class Catalog(
    val schemaVersion: Int,
    val updatedAt: String,
    val brand: Brand,
    val features: Features,
    val featuredReleaseIds: List<String>,
    val artists: List<Artist>,
    val tracks: List<Track>,
    val releases: List<Release>,
    val homeSections: List<HomeSection>,
    val artistLists: List<ArtistList>,
) {
    fun artist(id: String) = artists.firstOrNull { it.id == id }
    fun track(id: String) = tracks.firstOrNull { it.id == id }
    fun release(id: String) = releases.firstOrNull { it.id == id }
    fun artistList(id: String) = artistLists.firstOrNull { it.id == id }
    fun artistNames(ids: List<String>): String = ids.mapNotNull { artist(it)?.name }.joinToString(", ")

    fun releaseTracks(release: Release): List<Track> = release.tracks
        .sortedWith(compareBy<ReleaseTrack> { it.disc }.thenBy { it.position })
        .mapNotNull { track(it.trackId) }

    fun playableReleaseTracks(release: Release): List<Track> = releaseTracks(release).filter(::isPlayable)

    fun releaseArtistLine(release: Release): String = artistNames(release.primaryArtistIds.ifEmpty { release.artistIds })

    fun trackArtistLine(track: Track): String {
        val primary = artistNames(track.primaryArtistIds.ifEmpty { track.artistIds })
        val featured = (track.featuredArtistIds.mapNotNull { artist(it)?.name } + track.featuredArtistNames)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
        return when {
            featured.isBlank() -> primary
            primary.isBlank() -> featured
            else -> "$primary feat. $featured"
        }
    }

    fun isPlayable(track: Track): Boolean = track.playable && track.sources.any { it.url.isNotBlank() }

    fun releaseState(release: Release): String {
        if (release.status.isNotBlank()) return release.status
        val rows = releaseTracks(release)
        val count = rows.count(::isPlayable)
        return when {
            rows.isNotEmpty() && count == rows.size -> "published"
            count > 0 -> "partial"
            else -> "upcoming"
        }
    }

    fun popularTracks(artist: Artist): List<Track> {
        val configured = artist.popularTrackIds.mapNotNull(::track).filter(::isPlayable)
        if (configured.isNotEmpty()) return configured.take(5)
        val releaseDates = buildMap<String, String> {
            releases.forEach { release -> release.tracks.forEach { put(it.trackId, release.releaseDate) } }
        }
        return tracks
            .filter { artist.id in (it.primaryArtistIds.ifEmpty { it.artistIds }) && isPlayable(it) }
            .sortedByDescending { releaseDates[it.id].orEmpty() }
            .take(5)
    }

    fun listsForArtist(artist: Artist): List<ArtistList> {
        val configured = artist.listIds.mapNotNull(::artistList)
        return if (configured.isNotEmpty()) configured else artistLists.filter { it.artistId == artist.id }
    }

    fun effectiveHomeSections(): List<HomeSection> {
        if (homeSections.isNotEmpty()) return homeSections
        return listOf(
            HomeSection(
                id = "featured",
                title = "Öne Çıkanlar",
                subtitle = "Aurora seçkisi",
                type = "releases",
                layout = "hero",
                releaseIds = featuredReleaseIds,
                artistIds = emptyList(),
                trackIds = emptyList(),
                listIds = emptyList(),
            ),
            HomeSection(
                id = "new-releases",
                title = "Yeni Çıkanlar",
                subtitle = "En son eklenen yayınlar",
                type = "releases",
                layout = "horizontal",
                releaseIds = releases.sortedByDescending { it.releaseDate }.take(10).map { it.id },
                artistIds = emptyList(),
                trackIds = emptyList(),
                listIds = emptyList(),
            ),
            HomeSection(
                id = "artists",
                title = "Sanatçılar",
                subtitle = "Arşivdeki isimler",
                type = "artists",
                layout = "horizontal",
                releaseIds = emptyList(),
                artistIds = artists.sortedBy { it.name }.map { it.id },
                trackIds = emptyList(),
                listIds = emptyList(),
            ),
        )
    }
}

object CatalogParser {
    fun parse(text: String): Catalog {
        val root = JSONObject(text)
        val brandObject = root.optJSONObject("brand") ?: JSONObject()
        val featureObject = root.optJSONObject("features") ?: JSONObject()
        val artists = root.optJSONArray("artists").toObjectList { item ->
            Artist(
                id = item.getString("id"),
                slug = item.optString("slug"),
                name = item.getString("name"),
                image = item.optString("image"),
                heroImage = item.optString("heroImage", item.optString("image")),
                backgroundImage = item.optString("backgroundImage", item.optString("heroImage", item.optString("image"))),
                backgroundVideoUrl = item.optString("backgroundVideoUrl"),
                bio = item.optString("bio"),
                spotifyUrl = item.optString("spotifyUrl"),
                popularTrackIds = item.optJSONArray("popularTrackIds").toStringList(),
                listIds = item.optJSONArray("listIds").toStringList(),
            )
        }
        val tracks = root.optJSONArray("tracks").toObjectList { item ->
            val legacyArtistIds = item.optJSONArray("artistIds").toStringList()
            val primaryArtistIds = item.optJSONArray("primaryArtistIds").toStringList().ifEmpty { legacyArtistIds }
            val featuredArtistIds = item.optJSONArray("featuredArtistIds").toStringList().filterNot { it in primaryArtistIds }
            val featuredArtistNames = item.optJSONArray("featuredArtistNames").toStringList()
            val allArtistIds = (legacyArtistIds + primaryArtistIds + featuredArtistIds).distinct()
            val credits = item.optJSONArray("credits").toObjectList { credit ->
                Credit(credit.optString("role"), credit.optJSONArray("names").toStringList())
            }
            val sources = item.optJSONArray("sources").toObjectList { source ->
                AudioSource(
                    id = source.getString("id"),
                    kind = source.optString("kind", "standard"),
                    label = source.optString("label", "Standart"),
                    codec = source.optString("codec"),
                    url = source.optString("url"),
                    downloadUrl = source.optString("downloadUrl", source.optString("url")),
                    downloadable = source.optBoolean("downloadable", true),
                    bitrateKbps = source.optIntOrNull("bitrateKbps"),
                    sampleRateKhz = source.optDoubleOrNull("sampleRateKhz"),
                    bitDepth = source.optIntOrNull("bitDepth"),
                    channels = source.optString("channels").takeIf { it.isNotBlank() },
                    spatial = source.optBoolean("spatial", false),
                )
            }
            val explicitPlayable = item.optBoolean("playable", sources.isNotEmpty())
            Track(
                id = item.getString("id"),
                slug = item.optString("slug"),
                title = item.getString("title"),
                artistIds = allArtistIds,
                primaryArtistIds = primaryArtistIds,
                featuredArtistIds = featuredArtistIds,
                featuredArtistNames = featuredArtistNames,
                durationSeconds = item.optInt("durationSeconds"),
                lyrics = item.optString("lyrics"),
                syncedLyrics = item.optString("syncedLyrics"),
                isrc = item.optString("isrc"),
                explicit = item.optBoolean("explicit", false),
                spotifyUrl = item.optString("spotifyUrl"),
                credits = credits,
                sources = sources,
                playable = explicitPlayable && sources.isNotEmpty(),
                availability = item.optString("availability", if (sources.isNotEmpty()) "available" else "upcoming"),
                qualityState = item.optString("qualityState", if (sources.isNotEmpty()) "ready" else "waiting_for_audio"),
            )
        }
        val releases = root.optJSONArray("releases").toObjectList { item ->
            val legacyArtistIds = item.optJSONArray("artistIds").toStringList()
            val primaryArtistIds = item.optJSONArray("primaryArtistIds").toStringList().ifEmpty { legacyArtistIds }
            val refs = item.optJSONArray("tracks").toObjectList { row ->
                ReleaseTrack(row.getString("trackId"), row.optInt("disc", 1), row.optInt("position", 1))
            }
            Release(
                id = item.getString("id"),
                slug = item.optString("slug"),
                title = item.getString("title"),
                type = item.optString("type", "album"),
                artistIds = (legacyArtistIds + primaryArtistIds).distinct(),
                primaryArtistIds = primaryArtistIds,
                releaseDate = item.optString("releaseDate"),
                cover = item.optString("cover"),
                heroImage = item.optString("heroImage", item.optString("cover")),
                animatedCoverUrl = item.optString("animatedCoverUrl"),
                label = item.optString("label"),
                copyright = item.optString("copyright"),
                description = item.optString("description"),
                spotifyUrl = item.optString("spotifyUrl"),
                tracks = refs,
                status = item.optString("status"),
                availableTrackCount = item.optInt("availableTrackCount", 0),
                totalTrackCount = item.optInt("totalTrackCount", refs.size),
            )
        }
        val artistLists = root.optJSONArray("artistLists").toObjectList { item ->
            ArtistList(
                id = item.getString("id"),
                artistId = item.optString("artistId"),
                title = item.optString("title", "Sanatçı Seçkisi"),
                description = item.optString("description"),
                cover = item.optString("cover"),
                trackIds = item.optJSONArray("trackIds").toStringList(),
            )
        }
        val homeSections = root.optJSONArray("homeSections").toObjectList { item ->
            HomeSection(
                id = item.optString("id", "section-${item.hashCode()}"),
                title = item.optString("title"),
                subtitle = item.optString("subtitle"),
                type = item.optString("type", "releases"),
                layout = item.optString("layout", "horizontal"),
                releaseIds = item.optJSONArray("releaseIds").toStringList(),
                artistIds = item.optJSONArray("artistIds").toStringList(),
                trackIds = item.optJSONArray("trackIds").toStringList(),
                listIds = item.optJSONArray("listIds").toStringList(),
            )
        }
        return Catalog(
            schemaVersion = root.optInt("schemaVersion", 1),
            updatedAt = root.optString("updatedAt"),
            brand = Brand(
                brandObject.optString("name", "Aurora Music"),
                brandObject.optString("subtitle"),
                brandObject.optString("logoText", "A"),
            ),
            features = Features(
                featureObject.optBoolean("syncedLyrics", true),
                featureObject.optBoolean("animatedCovers", true),
                featureObject.optBoolean("artistBackgrounds", true),
                featureObject.optBoolean("offlineDownloads", true),
            ),
            featuredReleaseIds = root.optJSONArray("featuredReleaseIds").toStringList(),
            artists = artists,
            tracks = tracks,
            releases = releases,
            homeSections = homeSections,
            artistLists = artistLists,
        )
    }
}

private inline fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { it.isNotBlank() } }

private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
private fun JSONObject.optDoubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null

fun releaseTypeLabel(type: String) = when (type.lowercase()) {
    "single" -> "Single"
    "maxi_single" -> "Maxi Single"
    "ep" -> "EP"
    else -> "Albüm"
}

fun releaseStatusLabel(status: String) = when (status.lowercase()) {
    "published" -> "Yayında"
    "partial" -> "Kısmen Yayında"
    else -> "Yakında"
}

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
fun formatMillis(value: Long): String = formatDuration((value.coerceAtLeast(0) / 1000).toInt())
fun qualityRank(kind: String) = when (kind.lowercase()) {
    "dolby_atmos" -> 5
    "hires" -> 4
    "lossless" -> 3
    "high" -> 2
    else -> 1
}
