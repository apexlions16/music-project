from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: beklenen 1 eşleşme, bulunan {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


activity = "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt"
replace_once(
    activity,
    '    var metadataSourceId by remember { mutableStateOf("") }\n',
    '    var metadataSourceId by remember { mutableStateOf("") }\n    var releaseSpotifyUrl by remember { mutableStateOf("") }\n',
    "release spotify state",
)
replace_once(
    activity,
    '                metadataSourceId = imported.sourceId\n',
    '                metadataSourceId = imported.sourceId\n                releaseSpotifyUrl = imported.spotifyUrl\n',
    "import spotify url",
)
replace_once(
    activity,
    '            metadataSourceId = metadataSourceId,\n        )',
    '            metadataSourceId = metadataSourceId,\n            spotifyUrl = releaseSpotifyUrl,\n        )',
    "draft spotify url",
)
replace_once(
    activity,
    '                metadataSourceId = ""\n                releaseTracks.clear()',
    '                metadataSourceId = ""\n                releaseSpotifyUrl = ""\n                releaseTracks.clear()',
    "clear spotify url",
)
replace_once(
    activity,
    '                    Text("Spotify ile eşleştir, MusicBrainz + CAA + LRCLIB\'den doldur")',
    '                    Text("Spotify metadata + ISRC + kapak + LRCLIB sözlerini getir")',
    "metadata button",
)
replace_once(
    activity,
    '''                OutlinedTextField(providers.spotifyClientSecret, { onProviders(providers.copy(spotifyClientSecret = it)) }, label = { Text("Spotify Client Secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(providers.musicBrainzContact, { onProviders(providers.copy(musicBrainzContact = it)) }, label = { Text("MusicBrainz iletişim e-postası veya URL") }, modifier = Modifier.fillMaxWidth())
                Text("Spotify yalnızca doğru albümü eşleştirmeye yardım eder. Kalıcı katalog verisi MusicBrainz, Cover Art Archive ve LRCLIB kaynaklarından oluşturulur.", color = StudioMuted, fontSize = 11.sp)''',
    '''                OutlinedTextField(providers.spotifyClientSecret, { onProviders(providers.copy(spotifyClientSecret = it)) }, label = { Text("Spotify Client Secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(providers.spotifyMarket, { onProviders(providers.copy(spotifyMarket = it.uppercase())) }, label = { Text("Spotify market (TR)") }, modifier = Modifier.fillMaxWidth())
                Text("Albüm, şarkı sırası, sanatçılar, ISRC, explicit, süre, tarih, label/telif ve kapak URL'si doğrudan Spotify'dan alınır. Sözler LRCLIB ile eşleştirilir.", color = StudioMuted, fontSize = 11.sp)''',
    "provider settings",
)

catalog = "studioapp/src/main/kotlin/com/apexlions/aurorastudio/CatalogV2Manager.kt"
replace_once(
    catalog,
    '''            if (existing != null) {
                reusedCount++
                releaseRows.put(trackRef(existing.getString("id"), index + 1))
                return@forEachIndexed
            }''',
    '''            if (existing != null) {
                reusedCount++
                if (row.audio != null && !isPlayable(existing)) {
                    val remote = hub.allocate(storage, "audio-source", extensionOf(row.audio.displayName))
                    pending += Pending(row.audio, remote)
                    val sourceUrl = hub.resolveUrl(remote)
                    val sources = existing.optJSONArray("sources") ?: JSONArray().also { existing.put("sources", it) }
                    sources.put(sourceJson(row.audio.displayName, sourceUrl, "original"))
                    jobs.put(qualityJob(existing.getString("id"), remote, sourceUrl))
                    existing
                        .put("playable", true)
                        .put("availability", "available")
                        .put("qualityState", "queued")
                        .put("durationSeconds", row.durationSeconds)
                        .put("spotifyId", row.spotifyId)
                        .put("spotifyUrl", row.spotifyUrl)
                }
                releaseRows.put(trackRef(existing.getString("id"), row.disc, row.position.ifBlankPosition(index + 1)))
                return@forEachIndexed
            }''',
    "existing isrc fill",
)
replace_once(
    catalog,
    '                .put("durationSeconds", 0)\n',
    '                .put("durationSeconds", row.durationSeconds)\n                .put("spotifyId", row.spotifyId)\n                .put("spotifyUrl", row.spotifyUrl)\n',
    "track spotify fields",
)
replace_once(
    catalog,
    '            releaseRows.put(trackRef(trackId, index + 1))\n',
    '            releaseRows.put(trackRef(trackId, row.disc, row.position.ifBlankPosition(index + 1)))\n',
    "new track order",
)
replace_once(
    catalog,
    '            .put("metadataSourceId", draft.metadataSourceId)\n            .put("tracks", releaseRows)',
    '            .put("metadataSourceId", draft.metadataSourceId)\n            .put("spotifyUrl", draft.spotifyUrl)\n            .put("spotifyCoverUrl", draft.coverUrl)\n            .put("tracks", releaseRows)',
    "release spotify fields",
)
replace_once(
    catalog,
    '    private fun trackRef(id: String, position: Int): JSONObject = JSONObject().put("trackId", id).put("disc", 1).put("position", position)\n',
    '''    private fun trackRef(id: String, position: Int): JSONObject = trackRef(id, 1, position)
    private fun trackRef(id: String, disc: Int, position: Int): JSONObject = JSONObject()
        .put("trackId", id)
        .put("disc", disc.coerceAtLeast(1))
        .put("position", position.coerceAtLeast(1))
    private fun Int.ifBlankPosition(fallback: Int): Int = if (this > 0) this else fallback
''',
    "track ref overload",
)

build = "studioapp/build.gradle.kts"
replace_once(build, 'versionCode = 2\n        versionName = "0.2.0"', 'versionCode = 3\n        versionName = "0.2.1"', "studio version")

print("Spotify completion patch applied")
