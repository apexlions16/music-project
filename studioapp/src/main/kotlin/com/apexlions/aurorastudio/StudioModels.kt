package com.apexlions.aurorastudio

import android.net.Uri
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal data class TrackDraft(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val title: String,
    val isrc: String = "",
    val primaryArtist: String = "",
    val featuredArtists: String = "",
    val explicit: Boolean = false,
    val lyrics: String = "",
)

internal data class AssetDraft(
    val uri: Uri,
    val displayName: String,
    val size: Long,
)

internal data class CatalogSnapshot(
    val json: org.json.JSONObject,
    val sha: String,
)

internal data class PreparedUpload(
    val uri: Uri,
    val displayName: String,
    val remotePath: String,
    val size: Long,
    val sha256: String,
)

internal fun nowIso(): String = Instant.now().toString()
internal fun opaqueId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}" 

internal fun normalizeIsrc(value: String): String = value.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

internal fun slugify(value: String): String {
    val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace('ı', 'i')
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    return ascii.ifBlank { UUID.randomUUID().toString().take(10) }
}

internal fun splitNames(value: String): List<String> = value
    .split(',', ';')
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinctBy { it.lowercase(Locale.ROOT) }

internal fun extensionOf(name: String): String = name.substringAfterLast('.', "bin").lowercase(Locale.ROOT)

internal data class SourceDescriptor(
    val kind: String,
    val label: String,
    val codec: String,
)

internal fun sourceDescriptor(name: String): SourceDescriptor = when (extensionOf(name)) {
    "flac" -> SourceDescriptor("lossless", "Lossless", "FLAC")
    "wav", "wave", "aiff", "aif" -> SourceDescriptor("lossless", "Kayıpsız Kaynak", extensionOf(name).uppercase(Locale.ROOT))
    "m4a", "aac" -> SourceDescriptor("high", "Yüksek Kalite", "AAC")
    "mp3" -> SourceDescriptor("high", "Yüksek Kalite", "MP3")
    "ogg", "opus" -> SourceDescriptor("high", "Yüksek Kalite", extensionOf(name).uppercase(Locale.ROOT))
    else -> SourceDescriptor("original", "Orijinal", extensionOf(name).uppercase(Locale.ROOT))
}
