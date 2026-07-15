package com.apexlions.aurorastudio

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
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

private class SpotifyNotFoundException(message: String) : RuntimeException(message)

internal class UnifiedMetadataClient(
    private val providers: ProviderConfig,
) {
    private val http = AuroraHttp().client
    private var accessToken: String = ""
    private var accessTokenExpiresAtMs: Long = 0L
    private val market = "TR"

    fun importRelease(
        rawQuery: String,
        includeLyrics: Boolean = false,
        progress: (String) -> Unit = {},
    ): ImportedRelease {
        require(rawQuery.isNotBlank()) { "Spotify albüm/şarkı bağlantısı veya arama metni gerekli." }
        require(providers.spotifyClientId.trim().isNotBlank() && providers.spotifyClientSecret.trim().isNotBlank()) {
            "Spotify Client ID ve Client Secret ayarlanmamış."
        }

        progress("Spotify erişim anahtarı alınıyor…")
        token()
        progress("Spotify üzerinde yayın eşleştiriliyor…")
        val resource = resolveResource(rawQuery.trim())
        val album = spotifyJson("/albums/${resource.albumId}", mapOf("market" to market))
        val albumArtists = album.optJSONArray("artists") ?: JSONArray()
        val albumArtistIds = buildList {
            for (index in 0 until albumArtists.length()) {
                albumArtists.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }

        progress("Spotify parça metadata bilgileri tek tek alınıyor…")
        val fullTracks = if (resource.trackId != null) {
            listOf(spotifyJson("/tracks/${resource.trackId}", mapOf("market" to market)))
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

        progress("Spotify sanatçı görselleri alınıyor…")
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
            title = if (resource.trackId == null) title else tracks.firstOrNull()?.title.orEmpty().ifBlank { title },
            type = if (resource.trackId == null) type else "single",
            releaseDate = album.optString("release_date"),
            mainArtist = if (resource.trackId == null) mainArtist else tracks.firstOrNull()?.primaryArtist.orEmpty().ifBlank { mainArtist },
            label = album.optString("label"),
            copyright = copyright,
            coverUrl = coverUrl,
            tracks = tracks,
            source = "spotify",
            sourceId = resource.trackId ?: album.optString("id", resource.albumId),
        )
    }

    private data class SpotifyResource(val albumId: String, val trackId: String? = null)
    private data class SearchCandidate(val kind: String, val row: JSONObject, val score: Int)

    private fun resolveResource(value: String): SpotifyResource {
        val resolved = resolveSharedUrl(value)
        parseSpotifyResource(resolved)?.let { (kind, id) ->
            return resourceFromKind(kind, id)
        }

        val rawId = resolved.trim()
        if (Regex("^[A-Za-z0-9]{22}$").matches(rawId)) {
            spotifyJsonOrNull("/tracks/$rawId", mapOf("market" to market))?.let { track ->
                val albumId = track.optJSONObject("album")?.optString("id").orEmpty()
                if (albumId.isNotBlank()) return SpotifyResource(albumId, rawId)
            }
            spotifyJsonOrNull("/albums/$rawId", mapOf("market" to market))?.let {
                return SpotifyResource(rawId)
            }
        }

        val search = spotifyJson(
            "/search",
            mapOf(
                "q" to resolved.trim(),
                "type" to "track,album",
                "limit" to "10",
                "market" to market,
            ),
        )
        val candidates = mutableListOf<SearchCandidate>()
        val trackItems = search.optJSONObject("tracks")?.optJSONArray("items")
        if (trackItems != null) {
            for (index in 0 until trackItems.length()) {
                val row = trackItems.optJSONObject(index) ?: continue
                if (row.optString("id").isBlank()) continue
                candidates += SearchCandidate("track", row, searchScore("track", row, resolved, index))
            }
        }
        val albumItems = search.optJSONObject("albums")?.optJSONArray("items")
        if (albumItems != null) {
            for (index in 0 until albumItems.length()) {
                val row = albumItems.optJSONObject(index) ?: continue
                if (row.optString("id").isBlank()) continue
                candidates += SearchCandidate("album", row, searchScore("album", row, resolved, index))
            }
        }
        val best = candidates.maxByOrNull(SearchCandidate::score)
            ?: error("Spotify üzerinde uygun albüm veya şarkı bulunamadı.")
        return if (best.kind == "album") {
            SpotifyResource(best.row.optString("id"))
        } else {
            val albumId = best.row.optJSONObject("album")?.optString("id").orEmpty()
            if (albumId.isBlank()) error("Spotify arama sonucunun albüm bilgisi bulunamadı.")
            SpotifyResource(albumId, best.row.optString("id"))
        }
    }

    private fun resourceFromKind(kind: String, id: String): SpotifyResource {
        return if (kind == "album") {
            SpotifyResource(albumId = id)
        } else {
            val track = spotifyJson("/tracks/$id", mapOf("market" to market))
            val albumId = track.optJSONObject("album")?.optString("id").orEmpty()
            if (albumId.isBlank()) error("Spotify şarkısının albüm bilgisi alınamadı.")
            SpotifyResource(albumId = albumId, trackId = id)
        }
    }

    private fun resolveSharedUrl(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return trimmed
        val parsed = runCatching { trimmed.toHttpUrl() }.getOrNull() ?: return trimmed
        val host = parsed.host.lowercase(Locale.ROOT)
        val redirectHosts = setOf("spotify.link", "spoti.fi", "spotify.app.link", "link.spotify.com")
        if (host !in redirectHosts) return trimmed
        val request = Request.Builder()
            .url(parsed)
            .header("User-Agent", "Mozilla/5.0 AuroraStudioMobile/0.4.0")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Spotify paylaşım bağlantısı çözülemedi: HTTP ${response.code}")
            response.request.url.toString()
        }
    }

    private fun parseSpotifyResource(value: String): Pair<String, String>? {
        val direct = Regex(
            "(?:open\\.spotify\\.com/(?:intl-[a-z]{2}/)?(?:embed/)?|spotify:)(album|track)[/:]([A-Za-z0-9]{10,})",
            RegexOption.IGNORE_CASE,
        ).find(value)
        return direct?.let { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
    }

    private fun searchScore(kind: String, row: JSONObject, query: String, index: Int): Int {
        val normalizedQuery = normalizeSearch(query)
        val name = normalizeSearch(row.optString("name"))
        val artists = normalizeSearch(artistNames(row.optJSONArray("artists")).joinToString(" "))
        val combined = "$name $artists".trim()
        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank)
        val matchedTokens = queryTokens.count { it in combined }
        var score = when {
            combined == normalizedQuery -> 4000
            name == normalizedQuery -> 3600
            combined.startsWith(normalizedQuery) -> 2400
            name.startsWith(normalizedQuery) -> 2200
            normalizedQuery.isNotBlank() && normalizedQuery in combined -> 1600
            queryTokens.isNotEmpty() && matchedTokens == queryTokens.size -> 1300
            else -> matchedTokens * 180
        }
        if (kind == "track") score += 120
        if (kind == "album" && row.optInt("total_tracks", 1) > 1) score += 180
        if ("album" in normalizedQuery || "albüm" in normalizedQuery) score += if (kind == "album") 500 else -200
        return score - index * 7
    }

    private fun normalizeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace('ı', 'i')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun hydrateAlbumTracks(album: JSONObject): List<JSONObject> {
        val simplified = mutableListOf<JSONObject>()
        var page: JSONObject? = album.optJSONObject("tracks")
        if (page == null) {
            page = spotifyJson(
                "/albums/${album.optString("id")}/tracks",
                mapOf("market" to market, "limit" to "50"),
            )
        }
        while (page != null) {
            val items = page.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(simplified::add)
            val next = page.optString("next").takeUnless { it.isBlank() || it == "null" } ?: break
            page = spotifyAbsoluteJson(next)
        }

        // Spotify Development Mode 2026'da toplu GET /tracks kaldırıldı.
        // Her parçayı ayrı çağırmak tüm yeni ve eski geliştirici uygulamalarında çalışır.
        return simplified.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            .distinct()
            .mapNotNull { id -> spotifyJsonOrNull("/tracks/$id", mapOf("market" to market)) }
    }

    private fun fetchArtists(ids: List<String>): Map<String, JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        // Spotify Development Mode 2026'da toplu GET /artists kaldırıldı.
        ids.distinct().filter(String::isNotBlank).forEach { id ->
            spotifyJsonOrNull("/artists/$id")?.let { row ->
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
            val url = image.optString("url")
            val area = image.optLong("width", 0L) * image.optLong("height", 0L)
            if (url.isNotBlank() && (selected.isBlank() || area > selectedArea)) {
                selected = url
                selectedArea = area
            }
        }
        return selected
    }

    private fun token(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && accessToken.isNotBlank() && now < accessTokenExpiresAtMs - 60_000L) return accessToken
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
                val payload = runCatching { JSONObject(body) }.getOrNull()
                val detail = payload?.optString("error_description").orEmpty().ifBlank { payload?.optString("error").orEmpty() }
                error("Spotify yetkilendirmesi başarısız: HTTP ${response.code}${detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}")
            }
            val payload = JSONObject(body)
            accessTokenExpiresAtMs = now + payload.optLong("expires_in", 3600L) * 1000L
            payload.optString("access_token").takeIf(String::isNotBlank)
                ?: error("Spotify erişim anahtarı boş döndü.")
        }
        return accessToken
    }

    private fun spotifyJson(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        spotifyJsonOrNull(path, params) ?: throw SpotifyNotFoundException("Spotify kaynağı bulunamadı: $path")

    private fun spotifyJsonOrNull(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val url = "https://api.spotify.com/v1$path".toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return spotifyRequest(url.toString(), allowNotFound = true)
    }

    private fun spotifyAbsoluteJson(url: String): JSONObject =
        spotifyRequest(url, allowNotFound = false) ?: throw SpotifyNotFoundException("Spotify sayfası bulunamadı.")

    private fun spotifyRequest(url: String, allowNotFound: Boolean, retriedAfter401: Boolean = false): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${token()}")
            .header("User-Agent", "AuroraStudioMobile/0.4.0")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 && !retriedAfter401 -> {
                    accessToken = ""
                    accessTokenExpiresAtMs = 0L
                    token(forceRefresh = true)
                    return spotifyRequest(url, allowNotFound, retriedAfter401 = true)
                }
                response.code == 404 && allowNotFound -> return null
                response.code == 429 -> error("Spotify hız sınırı. ${response.header("Retry-After", "birkaç")} saniye sonra tekrar deneyin.")
                response.code == 403 -> error(
                    "Spotify isteği reddedildi (HTTP 403). Spotify Developer Dashboard uygulamasının sahibi aktif Premium kullanmalı; Client ID/Secret aynı uygulamaya ait olmalı.",
                )
                !response.isSuccessful -> {
                    val message = runCatching {
                        val payload = JSONObject(body)
                        payload.optJSONObject("error")?.optString("message").orEmpty().ifBlank { payload.optString("error_description") }
                    }.getOrNull().orEmpty()
                    error("Spotify isteği başarısız: HTTP ${response.code}${message.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}")
                }
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }
}
