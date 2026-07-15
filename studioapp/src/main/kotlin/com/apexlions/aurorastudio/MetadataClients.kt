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
        require(rawQuery.isNotBlank()) { "Albüm veya single adı gerekli." }
        progress("Metadata eşleşmesi aranıyor…")
        val spotifyHint = runCatching { spotifyAlbumHint(rawQuery) }.getOrNull()
        val titleHint = spotifyHint?.first ?: rawQuery.trim()
        val artistHint = spotifyHint?.second.orEmpty()

        val releaseId = searchMusicBrainzRelease(titleHint, artistHint)
            ?: error("MusicBrainz üzerinde uygun yayın bulunamadı.")
        progress("MusicBrainz yayın bilgileri alınıyor…")
        val release = getJson(
            "https://musicbrainz.org/ws/2/release/$releaseId".toHttpUrl().newBuilder()
                .addQueryParameter("inc", "recordings+artist-credits+labels+isrcs+release-groups")
                .addQueryParameter("fmt", "json")
                .build().toString(),
            musicBrainzUserAgent(),
        )

        val title = release.optString("title", titleHint)
        val mainArtist = artistCreditName(release.optJSONArray("artist-credit")).ifBlank { artistHint }
        val releaseDate = release.optString("date")
        val labelInfo = release.optJSONArray("label-info")
        val label = labelInfo?.optJSONObject(0)?.optJSONObject("label")?.optString("name").orEmpty()
        val group = release.optJSONObject("release-group")
        val type = when (group?.optString("primary-type")?.lowercase()) {
            "single" -> "single"
            "ep" -> "ep"
            "album" -> "album"
            else -> if (release.optJSONArray("media")?.let(::trackCount) == 1) "single" else "album"
        }
        val coverUrl = "https://coverartarchive.org/release/$releaseId/front-1200"
        val trackRows = mutableListOf<V2TrackDraft>()
        val media = release.optJSONArray("media") ?: JSONArray()
        for (m in 0 until media.length()) {
            val medium = media.optJSONObject(m) ?: continue
            val tracks = medium.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracks.length()) {
                val row = tracks.optJSONObject(i) ?: continue
                val recording = row.optJSONObject("recording") ?: JSONObject()
                val trackTitle = row.optString("title", recording.optString("title"))
                val credits = recording.optJSONArray("artist-credit") ?: row.optJSONArray("artist-credit")
                val names = artistCreditNames(credits)
                val primary = names.firstOrNull().orEmpty().ifBlank { mainArtist }
                val featured = names.drop(1).joinToString(", ")
                val isrcs = recording.optJSONArray("isrcs")
                val isrc = isrcs?.optString(0).orEmpty()
                val lyric = if (includeLyrics) {
                    progress("Sözler aranıyor: $trackTitle")
                    runCatching { lookupLyrics(trackTitle, primary, title) }.getOrDefault("" to "")
                } else "" to ""
                trackRows += V2TrackDraft(
                    title = trackTitle,
                    isrc = isrc,
                    primaryArtist = primary,
                    featuredArtists = featured,
                    lyrics = lyric.first,
                    syncedLyrics = lyric.second,
                    creditsText = names.takeIf { it.isNotEmpty() }?.let { "Sanatçılar: ${it.joinToString(", ")}" }.orEmpty(),
                )
            }
        }
        if (trackRows.isEmpty()) error("Yayında parça listesi bulunamadı.")

        val source = if (spotifyHint != null) "spotify-match+musicbrainz+coverartarchive+lrclib" else "musicbrainz+coverartarchive+lrclib"
        return ImportedRelease(
            title = title,
            type = type,
            releaseDate = releaseDate,
            mainArtist = mainArtist,
            label = label,
            copyright = "",
            coverUrl = coverUrl,
            tracks = trackRows,
            source = source,
            sourceId = releaseId,
        )
    }

    private fun searchMusicBrainzRelease(title: String, artist: String): String? {
        val query = buildString {
            append("release:\"").append(title.replace("\"", "")).append("\"")
            if (artist.isNotBlank()) append(" AND artist:\"").append(artist.replace("\"", "")).append("\"")
        }
        val url = "https://musicbrainz.org/ws/2/release/".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", "10")
            .addQueryParameter("fmt", "json")
            .build().toString()
        val rows = getJson(url, musicBrainzUserAgent()).optJSONArray("releases") ?: JSONArray()
        var bestId: String? = null
        var bestScore = -1
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val score = row.optInt("score", 0)
            if (score > bestScore) {
                bestScore = score
                bestId = row.optString("id").takeIf(String::isNotBlank)
            }
        }
        return bestId
    }

    private fun spotifyAlbumHint(query: String): Pair<String, String>? {
        if (providers.spotifyClientId.isBlank() || providers.spotifyClientSecret.isBlank()) return null
        val tokenBody = FormBody.Builder().add("grant_type", "client_credentials").build()
        val tokenRequest = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", Credentials.basic(providers.spotifyClientId.trim(), providers.spotifyClientSecret.trim()))
            .post(tokenBody)
            .build()
        val token = http.newCall(tokenRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            JSONObject(response.body?.string().orEmpty()).optString("access_token").takeIf(String::isNotBlank)
        } ?: return null
        val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "album")
            .addQueryParameter("limit", "5")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val item = JSONObject(response.body?.string().orEmpty())
                .optJSONObject("albums")?.optJSONArray("items")?.optJSONObject(0) ?: return null
            val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            item.optString("name").takeIf(String::isNotBlank)?.let { it to artist }
        }
    }

    private fun lookupLyrics(track: String, artist: String, album: String): Pair<String, String> {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("album_name", album)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "AuroraStudioMobile/0.2.0").get().build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "" to ""
            val json = JSONObject(response.body?.string().orEmpty())
            json.optString("plainLyrics") to json.optString("syncedLyrics")
        }
    }

    private fun getJson(url: String, userAgent: String): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", userAgent).get().build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Metadata isteği başarısız: HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun musicBrainzUserAgent(): String {
        val contact = providers.musicBrainzContact.trim().ifBlank { "https://github.com/apexlions16/music-project" }
        return "AuroraStudioMobile/0.2.0 ($contact)"
    }

    private fun artistCreditNames(value: JSONArray?): List<String> = buildList {
        if (value == null) return@buildList
        for (i in 0 until value.length()) {
            val item = value.opt(i)
            if (item is JSONObject) {
                val name = item.optString("name").ifBlank { item.optJSONObject("artist")?.optString("name").orEmpty() }
                if (name.isNotBlank()) add(name)
            }
        }
    }.distinctBy(String::lowercase)

    private fun artistCreditName(value: JSONArray?): String = artistCreditNames(value).joinToString(" & ")

    private fun trackCount(media: JSONArray): Int {
        var count = 0
        for (i in 0 until media.length()) count += media.optJSONObject(i)?.optJSONArray("tracks")?.length() ?: 0
        return count
    }
}
