package com.apexlions.aurorastudio

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal class UnifiedMetadataClient(
    private val providers: ProviderConfig,
) {
    private val http = AuroraHttp().client

    fun importRelease(
        rawQuery: String,
        includeLyrics: Boolean = true,
        progress: (String) -> Unit = {},
    ): ImportedRelease {
        require(rawQuery.isNotBlank()) { "Spotify albüm/single/şarkı bağlantısı veya arama metni gerekli." }
        require(providers.spotifyClientId.isNotBlank() && providers.spotifyClientSecret.isNotBlank()) {
            "Spotify Client ID ve Client Secret ayarlardan girilmeli."
        }
        progress("Spotify erişim anahtarı alınıyor…")
        val token = spotifyToken()
        val target = resolveTarget(rawQuery.trim(), token)
        return when (target.first) {
            "track" -> importTrack(target.second, token, includeLyrics, progress)
            else -> importAlbum(target.second, token, includeLyrics, progress)
        }
    }

    private fun importAlbum(
        albumId: String,
        token: String,
        includeLyrics: Boolean,
        progress: (String) -> Unit,
    ): ImportedRelease {
        progress("Spotify albüm bilgileri alınıyor…")
        val album = spotifyGet("https://api.spotify.com/v1/albums/$albumId", token)
        val simplified = mutableListOf<JSONObject>()
        var page: JSONObject? = album.optJSONObject("tracks")
        while (page != null) {
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(simplified::add)
            val next = page.optString("next")
            page = if (next.isBlank()) null else spotifyGet(next, token)
        }
        if (simplified.isEmpty()) error("Spotify albümünde parça bulunamadı.")

        val fullTracks = mutableMapOf<String, JSONObject>()
        simplified.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            .chunked(50)
            .forEach { ids ->
                val url = "https://api.spotify.com/v1/tracks".toHttpUrl().newBuilder()
                    .addQueryParameter("ids", ids.joinToString(","))
                    .addQueryParameter("market", providers.spotifyMarket.ifBlank { "TR" })
                    .build().toString()
                val rows = spotifyGet(url, token).optJSONArray("tracks") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    row.optString("id").takeIf(String::isNotBlank)?.let { fullTracks[it] = row }
                }
            }

        val albumArtists = artistNames(album.optJSONArray("artists"))
        val mainArtist = albumArtists.firstOrNull().orEmpty()
        val albumTitle = album.optString("name")
        val cover = bestImage(album.optJSONArray("images"))
        val releaseUrl = album.optJSONObject("external_urls")?.optString("spotify").orEmpty()
        val copyrights = renderCopyrights(album.optJSONArray("copyrights"))
        val drafts = simplified.mapIndexed { index, simple ->
            val row = fullTracks[simple.optString("id")] ?: simple
            progress("Metadata ve sözler: ${row.optString("name")} (${index + 1}/${simplified.size})")
            trackDraft(row, albumTitle, mainArtist, includeLyrics)
        }
        return ImportedRelease(
            title = albumTitle,
            type = spotifyReleaseType(album.optString("album_type"), drafts.size),
            releaseDate = album.optString("release_date"),
            mainArtist = mainArtist,
            label = album.optString("label"),
            copyright = copyrights,
            coverUrl = cover,
            tracks = drafts,
            source = "spotify+lrclib",
            sourceId = albumId,
            spotifyUrl = releaseUrl,
        )
    }

    private fun importTrack(
        trackId: String,
        token: String,
        includeLyrics: Boolean,
        progress: (String) -> Unit,
    ): ImportedRelease {
        progress("Spotify şarkı bilgileri alınıyor…")
        val url = "https://api.spotify.com/v1/tracks/$trackId".toHttpUrl().newBuilder()
            .addQueryParameter("market", providers.spotifyMarket.ifBlank { "TR" })
            .build().toString()
        val track = spotifyGet(url, token)
        val album = track.optJSONObject("album") ?: JSONObject()
        val artists = artistNames(track.optJSONArray("artists"))
        val mainArtist = artists.firstOrNull().orEmpty()
        val title = track.optString("name")
        val draft = trackDraft(track, album.optString("name", title), mainArtist, includeLyrics)
        return ImportedRelease(
            title = title,
            type = "single",
            releaseDate = album.optString("release_date"),
            mainArtist = mainArtist,
            label = "",
            copyright = "",
            coverUrl = bestImage(album.optJSONArray("images")),
            tracks = listOf(draft),
            source = "spotify+lrclib",
            sourceId = trackId,
            spotifyUrl = track.optJSONObject("external_urls")?.optString("spotify").orEmpty(),
        )
    }

    private fun trackDraft(row: JSONObject, albumTitle: String, fallbackArtist: String, includeLyrics: Boolean): V2TrackDraft {
        val names = artistNames(row.optJSONArray("artists"))
        val primary = names.firstOrNull().orEmpty().ifBlank { fallbackArtist }
        val title = row.optString("name")
        val lyric = if (includeLyrics) runCatching { lookupLyrics(title, primary, albumTitle, row.optLong("duration_ms")) }.getOrDefault("" to "") else "" to ""
        return V2TrackDraft(
            title = title,
            isrc = row.optJSONObject("external_ids")?.optString("isrc").orEmpty(),
            primaryArtist = primary,
            featuredArtists = names.drop(1).joinToString(", "),
            explicit = row.optBoolean("explicit"),
            lyrics = lyric.first,
            syncedLyrics = lyric.second,
            creditsText = names.takeIf(List<String>::isNotEmpty)?.let { "Sanatçılar: ${it.joinToString(", ")}" }.orEmpty(),
            durationSeconds = (row.optLong("duration_ms") / 1000L).toInt(),
            disc = row.optInt("disc_number", 1).coerceAtLeast(1),
            position = row.optInt("track_number", 1).coerceAtLeast(1),
            spotifyId = row.optString("id"),
            spotifyUrl = row.optJSONObject("external_urls")?.optString("spotify").orEmpty(),
        )
    }

    private fun resolveTarget(query: String, token: String): Pair<String, String> {
        Regex("open\\.spotify\\.com/(album|track)/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(query)?.let { return it.groupValues[1].lowercase() to it.groupValues[2] }
        Regex("spotify:(album|track):([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(query)?.let { return it.groupValues[1].lowercase() to it.groupValues[2] }
        if (query.matches(Regex("[A-Za-z0-9]{15,30}"))) return "album" to query

        val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "album,track")
            .addQueryParameter("limit", "5")
            .addQueryParameter("market", providers.spotifyMarket.ifBlank { "TR" })
            .build().toString()
        val result = spotifyGet(url, token)
        result.optJSONObject("albums")?.optJSONArray("items")?.optJSONObject(0)?.let { album ->
            album.optString("id").takeIf(String::isNotBlank)?.let { return "album" to it }
        }
        result.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)?.let { track ->
            track.optString("id").takeIf(String::isNotBlank)?.let { return "track" to it }
        }
        error("Spotify üzerinde uygun albüm veya şarkı bulunamadı.")
    }

    private fun spotifyToken(): String {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", Credentials.basic(providers.spotifyClientId.trim(), providers.spotifyClientSecret.trim()))
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Spotify yetkilendirmesi başarısız: HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty()).optString("access_token").ifBlank { error("Spotify access token alınamadı.") }
        }
    }

    private fun spotifyGet(rawUrl: String, token: String): JSONObject {
        val parsed = rawUrl.toHttpUrl().newBuilder()
        if (rawUrl.startsWith("https://api.spotify.com/") && !rawUrl.contains("market=")) {
            parsed.addQueryParameter("market", providers.spotifyMarket.ifBlank { "TR" })
        }
        val request = Request.Builder().url(parsed.build()).header("Authorization", "Bearer $token").header("Accept", "application/json").get().build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Spotify isteği başarısız: HTTP ${response.code} ${response.body?.string().orEmpty().take(400)}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun lookupLyrics(track: String, artist: String, album: String, durationMs: Long): Pair<String, String> {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("album_name", album)
            .apply { if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000L).toString()) }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "AuroraStudioMobile/0.2.1").get().build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "" to ""
            val json = JSONObject(response.body?.string().orEmpty())
            json.optString("plainLyrics") to json.optString("syncedLyrics")
        }
    }

    private fun artistNames(value: JSONArray?): List<String> = buildList {
        if (value == null) return@buildList
        for (i in 0 until value.length()) {
            val name = value.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }.distinctBy(String::lowercase)

    private fun bestImage(images: JSONArray?): String {
        if (images == null) return ""
        var best = ""
        var area = -1L
        for (i in 0 until images.length()) {
            val item = images.optJSONObject(i) ?: continue
            val candidate = item.optString("url")
            val candidateArea = item.optLong("width") * item.optLong("height")
            if (candidate.isNotBlank() && candidateArea > area) { best = candidate; area = candidateArea }
        }
        return best
    }

    private fun renderCopyrights(rows: JSONArray?): String = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.optString("text")?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct().joinToString(" • ")

    private fun spotifyReleaseType(albumType: String, count: Int): String = when (albumType.lowercase()) {
        "single" -> if (count <= 1) "single" else if (count <= 3) "maxi_single" else "ep"
        "compilation", "album" -> if (count <= 6) "ep" else "album"
        else -> if (count <= 1) "single" else if (count <= 6) "ep" else "album"
    }
}
