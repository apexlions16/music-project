from __future__ import annotations

from pathlib import Path


def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Beklenen kaynak metni bulunamadı: {path}\n{old[:160]}")
    path.write_text(text.replace(old, new), encoding="utf-8")


activity = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt")
replacements = [
    (
        "UnifiedMetadataClient(providerConfig).importRelease(metadataQuery, includeLyrics = true)",
        "UnifiedMetadataClient(providerConfig).importRelease(metadataQuery, includeLyrics = false)",
    ),
    (
        'status = "Metadata hazır: ${imported.tracks.size} parça. Ses dosyalarını istediğiniz zaman ekleyebilirsiniz."',
        'status = "Spotify metadata hazır: ${imported.tracks.size} parça. Kapak ve sanatçı görselleri yayın sırasında Hugging Face\'e taşınacak."',
    ),
    ('Text("v0.2.0 • ${screen.title}"', 'Text("v0.3.0 • ${screen.title}"'),
    ('V2Card("Tek dokunuşla metadata")', 'V2Card("Tek dokunuşla Spotify metadata")'),
    (
        'Text("Albüm / single adı veya Spotify bağlantısı")',
        'Text("Spotify albüm / şarkı bağlantısı veya arama metni")',
    ),
    (
        'Text("Spotify ile eşleştir, MusicBrainz + CAA + LRCLIB\'den doldur")',
        'Text("Yalnızca Spotify\'dan metadata bilgilerini doldur")',
    ),
    (
        'Text(coverAsset?.let { "Kalıcı yükleme hazır: ${it.displayName} • ${sizeLabel(it.size)}" } ?: "URL geçicidir; Görsel Fetch ile Hugging Face\'e kalıcı aktarım hazırlayın.", color = StudioMuted, fontSize = 11.sp)',
        'Text(coverAsset?.let { "Kalıcı yükleme hazır: ${it.displayName} • ${sizeLabel(it.size)}" } ?: "Spotify kapağı yayın sırasında otomatik indirilip Hugging Face\'e yüklenecek.", color = StudioMuted, fontSize = 11.sp)',
    ),
    (
        'V2Card("Metadata sağlayıcıları")',
        'V2Card("Spotify metadata")',
    ),
    (
        '                OutlinedTextField(providers.musicBrainzContact, { onProviders(providers.copy(musicBrainzContact = it)) }, label = { Text("MusicBrainz iletişim e-postası veya URL") }, modifier = Modifier.fillMaxWidth())\n',
        '',
    ),
    (
        'Text("Spotify yalnızca doğru albümü eşleştirmeye yardım eder. Kalıcı katalog verisi MusicBrainz, Cover Art Archive ve LRCLIB kaynaklarından oluşturulur.", color = StudioMuted, fontSize = 11.sp)',
        'Text("Tüm metadata Spotify Web API\'den alınır. Spotify kapak ve sanatçı görselleri indirilip Hugging Face\'e taşınır; GitHub kataloğunda dış görsel URL\'si bırakılmaz. Spotify şarkı sözü sağlamadığı için söz alanları elle düzenlenebilir.", color = StudioMuted, fontSize = 11.sp)',
    ),
]
for old, new in replacements:
    replace_required(activity, old, new)

metadata = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/MetadataClients.kt")
replace_required(
    metadata,
    """        val albumArtistIds = buildList {
            for (index in 0 until albumArtists.length()) {
                albumArtists.optJSONObject(index)?.optString(\"id\")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val artistDetails = fetchArtists(albumArtistIds)
        artistDetails.values.forEach { artist ->
            SpotifyMetadataCache.rememberArtist(
                artist.optString(\"name\"),
                largestImage(artist.optJSONArray(\"images\")),
            )
        }

        progress(\"Spotify parça metadata bilgileri alınıyor…\")
        val fullTracks = if (resource.trackId != null) {
            listOf(spotifyJson(\"/tracks/${resource.trackId}\"))
        } else {
            hydrateAlbumTracks(album)
        }
        if (fullTracks.isEmpty()) error(\"Spotify yayınında kullanılabilir parça bulunamadı.\")
""",
    """        val albumArtistIds = buildList {
            for (index in 0 until albumArtists.length()) {
                albumArtists.optJSONObject(index)?.optString(\"id\")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }

        progress(\"Spotify parça metadata bilgileri alınıyor…\")
        val fullTracks = if (resource.trackId != null) {
            listOf(spotifyJson(\"/tracks/${resource.trackId}\"))
        } else {
            hydrateAlbumTracks(album)
        }
        if (fullTracks.isEmpty()) error(\"Spotify yayınında kullanılabilir parça bulunamadı.\")

        val allArtistIds = buildList {
            addAll(albumArtistIds)
            fullTracks.forEach { track ->
                val rows = track.optJSONArray(\"artists\") ?: JSONArray()
                for (index in 0 until rows.length()) {
                    rows.optJSONObject(index)?.optString(\"id\")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.distinct()
        val artistDetails = fetchArtists(allArtistIds)
        artistDetails.values.forEach { artist ->
            SpotifyMetadataCache.rememberArtist(
                artist.optString(\"name\"),
                largestImage(artist.optJSONArray(\"images\")),
            )
        }
""",
)
replace_required(metadata, "includeLyrics: Boolean = true", "includeLyrics: Boolean = false")
replace_required(metadata, "names.takeIf(List<String>::isNotEmpty)", "names.takeIf { it.isNotEmpty() }")

print("Spotify-only mobil kaynak temizliği tamamlandı.")
