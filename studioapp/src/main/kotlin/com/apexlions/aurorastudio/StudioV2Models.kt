package com.apexlions.aurorastudio

import java.util.UUID

internal data class V2TrackDraft(
    val localId: String = UUID.randomUUID().toString(),
    val title: String = "",
    val isrc: String = "",
    val primaryArtist: String = "",
    val featuredArtists: String = "",
    val explicit: Boolean = false,
    val lyrics: String = "",
    val syncedLyrics: String = "",
    val creditsText: String = "",
    val durationSeconds: Int = 0,
    val disc: Int = 1,
    val position: Int = 1,
    val spotifyId: String = "",
    val spotifyUrl: String = "",
    val audio: AssetDraft? = null,
)

internal data class V2ReleaseDraft(
    val title: String = "",
    val type: String = "album",
    val releaseDate: String = "",
    val mainArtist: String = "",
    val label: String = "",
    val copyright: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val coverAsset: AssetDraft? = null,
    val animatedCoverUrl: String = "",
    val featured: Boolean = false,
    val tracks: List<V2TrackDraft> = emptyList(),
    val metadataSource: String = "manual",
    val metadataSourceId: String = "",
    val spotifyUrl: String = "",
)

internal data class ExistingTrackDraft(
    val id: String,
    val title: String,
    val isrc: String,
    val primaryArtist: String,
    val featuredArtists: String,
    val explicit: Boolean,
    val lyrics: String,
    val syncedLyrics: String,
    val creditsText: String,
    val playable: Boolean,
)

internal data class ImportedRelease(
    val title: String,
    val type: String,
    val releaseDate: String,
    val mainArtist: String,
    val label: String,
    val copyright: String,
    val coverUrl: String,
    val tracks: List<V2TrackDraft>,
    val source: String,
    val sourceId: String,
    val spotifyUrl: String = "",
)

internal data class ProviderConfig(
    val spotifyClientId: String = "",
    val spotifyClientSecret: String = "",
    val spotifyMarket: String = "TR",
    val musicBrainzContact: String = "",
)

internal fun parseCreditsText(value: String): org.json.JSONArray {
    val rows = org.json.JSONArray()
    value.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
        val role = line.substringBefore(':', "Künye").trim().ifBlank { "Künye" }
        val names = line.substringAfter(':', line).split(',', ';').map(String::trim).filter(String::isNotBlank)
        if (names.isNotEmpty()) rows.put(org.json.JSONObject().put("role", role).put("names", org.json.JSONArray(names)))
    }
    return rows
}

internal fun creditsToText(value: org.json.JSONArray?): String {
    if (value == null) return ""
    return buildList {
        for (i in 0 until value.length()) {
            val row = value.optJSONObject(i) ?: continue
            val names = row.optJSONArray("names") ?: org.json.JSONArray()
            val rendered = buildList { for (j in 0 until names.length()) add(names.optString(j)) }.filter(String::isNotBlank)
            if (rendered.isNotEmpty()) add("${row.optString("role", "Künye")}: ${rendered.joinToString(", ")}")
        }
    }.joinToString("\n")
}
