package com.apexlions.aurorastudio

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object SpotifyMetadataCache {
    private val artistImages = linkedMapOf<String, String>()

    @Synchronized
    fun rememberArtist(name: String, imageUrl: String) {
        if (name.isBlank() || imageUrl.isBlank()) return
        artistImages[name.trim().lowercase(Locale.ROOT)] = imageUrl.trim()
    }

    @Synchronized
    fun artistImageUrl(name: String): String = artistImages[name.trim().lowercase(Locale.ROOT)].orEmpty()
}

internal class UnifiedMetadataClient(
    private val providers: ProviderConfig,
) {
    private val http = AuroraHttp().client
    private var accessToken: String = ""

    fun importRelease(
        rawQuery: String,
        includeLyrics: Boolean = false,
        progress: (String) -> Unit = {},
    ): ImportedRelease {
        require(rawQuery.isNotBlank()) { "Spotify albüm/şarkı bağlantısı veya arama metni gerekli." }
        require(providers.spotifyClientId.isNotBlank() && providers.spotifyClientSecret.isNotBlank()) {
            "Spotify Client ID ve Client Secret ayarlanmamış."
        }

        progress("Spotify erişim anahtarı alınıyor…")
        token()
        progress("Spotify üzerinde yayın eşleştiriliyor…")
        val resource = resolveResource(rawQuery.trim())
        val album = spotifyJson("/albums/${resource.albumId}")
        val albumArtists = album.optJSONArray("artists") ?: JSONArray()
        val albumArtistIds = buildList {
            for (index in 0 until albumArtists.length()) {
                albumArtists.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }

        progress("Spotify parça metadata bilgileri alınıyor…")
        val fullTracks = if (resource.trackId != null) {
            listOf(spotifyJson("/tracks/${resource.trackId}"))
        } else {
            hydrateAlbumTracks(album)
        }
        if (fullTracks.isEmpty()) error("Spotify yayınında kullanılabilir parça bulunamadı.")

        val allArtistIds = buildList {
            addAll(albumArtistIds)
            fullTracks.forEach { track ->
                val rows = track.optJSONArray("artists") ?: JSONArray()
                for (index in 0 until rows.length()) {
                    rows.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.distinct()
        val artistDetails = fetchArtists(allArtistIds)
        artistDetails.values.forEach { artist ->
            SpotifyMetadataCache.rememberArtist(
                artist.optString("name"),
                largestImage(artist.optJSONArray("images")),
            )
        }

        val title = album.optString("name").ifBlank { rawQuery.trim() }
        val mainArtist = albumArtists.optJSONObject(0)?.optString("name").orEmpty().ifBlank {
            fullTracks.firstOrNull()?.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
        }
        val coverUrl = largestImage(album.optJSONArray("images"))
        if (coverUrl.isBlank()) error("Spotify bu yayın için kapak görseli döndürmedi.")

        val tracks = fullTracks
            .sortedWith(compareBy<JSONObject> { it.optInt("disc_number", 1) }.thenBy { it.optInt("track_number", 1) })
            .mapIndexed { index, track ->
                val names = artistNames(track.optJSONArray("artists"))
                val primary = names.firstOrNull().orEmpty().ifBlank { mainArtist }
                val featured = names.drop(1).joinToString(", ")
                V2TrackDraft(
                    title = track.optString("name").ifBlank { "Şarkı ${index + 1}" },
                    isrc = track.optJSONObject("external_ids")?.optString("isrc").orEmpty(),
                    primaryArtist = primary,
                    featuredArtists = featured,
                    explicit = track.optBoolean("explicit", false),
                    lyrics = "",
                    syncedLyrics = "",
                    creditsText = names.takeIf { it.isNotEmpty() }
                        ?.let { "Sanatçılar: ${it.joinToString(", ")}" }
                        .orEmpty(),
                )
            }

        val albumType = album.optString("album_type", "album").lowercase(Locale.ROOT)
        val type = when (albumType) {
            "single" -> if (tracks.size > 1) "ep" else "single"
            "compilation" -> "album"
            else -> "album"
        }
        val copyrights = album.optJSONArray("copyrights") ?: JSONArray()
        val copyright = buildList {
            for (index in 0 until copyrights.length()) {
                copyrights.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().joinToString(" • ")

        @Suppress("UNUSED_VARIABLE")
        val spotifyDoesNotProvideLyrics = includeLyrics
        return ImportedRelease(
            title = title,
            type = type,
            releaseDate = album.optString("release_date"),
            mainArtist = mainArtist,
            label = album.optString("label"),
            copyright = copyright,
            coverUrl = coverUrl,
            tracks = tracks,
            source = "spotify",
            sourceId = album.optString("id", resource.albumId),
        )
    }

    private data class SpotifyResource(val albumId: String, val trackId: String? = null)

    private fun resolveResource(value: String): SpotifyResource {
        parseSpotifyResource(value)?.let { (kind, id) ->
            return if (kind == "album") {
                SpotifyResource(albumId = id)
            } else {
                val track = spotifyJson("/tracks/$id")
                val albumId = track.optJSONObject("album")?.optString("id").orEmpty()
                if (albumId.isBlank()) error("Spotify şarkısının albüm bilgisi alınamadı.")
                SpotifyResource(albumId = albumId, trackId = id)
            }
        }

        val search = spotifyJson(
            "/search",
            mapOf(
                "q" to value,
                "type" to "album,track",
                "limit" to "10",
                "market" to "TR",
            ),
        )
        val album = bestSearchItem(search.optJSONObject("albums")?.optJSONArray("items"), value)
        if (album != null) return SpotifyResource(album.optString("id"))
        val track = bestSearchItem(search.optJSONObject("tracks")?.optJSONArray("items"), value)
            ?: error("Spotify üzerinde uygun albüm veya şarkı bulunamadı.")
        val albumId = track.optJSONObject("album")?.optString("id").orEmpty()
        if (albumId.isBlank()) error("Spotify arama sonucunun albüm bilgisi bulunamadı.")
        return SpotifyResource(albumId = albumId, trackId = track.optString("id").takeIf(String::isNotBlank))
    }

    private fun parseSpotifyResource(value: String): Pair<String, String>? {
        val direct = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z]{2}/)?|spotify:)(album|track)[/:]([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(value)
        return direct?.let { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
    }

    private fun bestSearchItem(items: JSONArray?, query: String): JSONObject? {
        if (items == null || items.length() == 0) return null
        val normalized = query.trim().lowercase(Locale.ROOT)
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            val name = row.optString("name").lowercase(Locale.ROOT)
            val score = when {
                name == normalized -> 1000
                name.startsWith(normalized) -> 700
                name.contains(normalized) -> 500
                else -> 100 - index
            } + row.optInt("popularity", 0)
            if (score > bestScore && row.optString("id").isNotBlank()) {
                best = row
                bestScore = score
            }
        }
        return best
    }

    private fun hydrateAlbumTracks(album: JSONObject): List<JSONObject> {
        val simplified = mutableListOf<JSONObject>()
        var page: JSONObject? = album.optJSONObject("tracks")
        while (page != null) {
            val items = page.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(simplified::add)
            val next = page.optString("next").takeIf(String::isNotBlank) ?: break
            page = spotifyAbsoluteJson(next)
        }

        val result = mutableListOf<JSONObject>()
        simplified.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            .chunked(50)
            .forEach { ids ->
                val payload = spotifyJson("/tracks", mapOf("ids" to ids.joinToString(","), "market" to "TR"))
                val tracks = payload.optJSONArray("tracks") ?: JSONArray()
                for (index in 0 until tracks.length()) tracks.optJSONObject(index)?.let(result::add)
            }
        return result
    }

    private fun fetchArtists(ids: List<String>): Map<String, JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        ids.distinct().filter(String::isNotBlank).chunked(50).forEach { chunk ->
            val payload = spotifyJson("/artists", mapOf("ids" to chunk.joinToString(",")))
            val rows = payload.optJSONArray("artists") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                row.optString("id").takeIf(String::isNotBlank)?.let { result[it] = row }
            }
        }
        return result
    }

    private fun artistNames(value: JSONArray?): List<String> = buildList {
        if (value == null) return@buildList
        for (index in 0 until value.length()) {
            value.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }.distinctBy { it.lowercase(Locale.ROOT) }

    private fun largestImage(value: JSONArray?): String {
        if (value == null) return ""
        var selected = ""
        var selectedArea = -1L
        for (index in 0 until value.length()) {
            val image = value.optJSONObject(index) ?: continue
            val area = image.optLong("width", 0L) * image.optLong("height", 0L)
            if (image.optString("url").isNotBlank() && area >= selectedArea) {
                selected = image.optString("url")
                selectedArea = area
            }
        }
        return selected
    }

    private fun token(): String {
        if (accessToken.isNotBlank()) return accessToken
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header(
                "Authorization",
                Credentials.basic(providers.spotifyClientId.trim(), providers.spotifyClientSecret.trim()),
            )
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        accessToken = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("error_description") }.getOrDefault("")
                error("Spotify yetkilendirmesi başarısız: HTTP ${response.code}${detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}")
            }
            JSONObject(body).optString("access_token").takeIf(String::isNotBlank)
                ?: error("Spotify erişim anahtarı boş döndü.")
        }
        return accessToken
    }

    private fun spotifyJson(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = "https://api.spotify.com/v1$path".toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return spotifyRequest(url.toString())
    }

    private fun spotifyAbsoluteJson(url: String): JSONObject = spotifyRequest(url)

    private fun spotifyRequest(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${token()}")
            .header("User-Agent", "AuroraStudioMobile/0.3.0")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 429 -> error("Spotify hız sınırı. ${response.header("Retry-After", "birkaç")} saniye sonra tekrar deneyin.")
                !response.isSuccessful -> {
                    val message = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    error("Spotify isteği başarısız: HTTP ${response.code}${message.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}")
                }
            }
            JSONObject(body)
        }
    }
}
