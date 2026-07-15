from __future__ import annotations

from pathlib import Path

path = Path("studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt")
text = path.read_text(encoding="utf-8")

text = text.replace('Text("v0.5.0 • ${screen.title}"', 'Text("v0.6.0 • ${screen.title}"')

if "val bulkAudioPicker" not in text:
    marker = "\n    fun loadCatalog() {"
    block = '''
    val bulkAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val files = uris.map(::asset)
        val matches = MediaMatcher.match(releaseTracks.map(V2TrackDraft::title), files.map(AssetDraft::displayName))
        matches.forEach { match ->
            val fileIndex = match.fileIndex ?: return@forEach
            if (match.targetIndex in releaseTracks.indices && fileIndex in files.indices) {
                releaseTracks[match.targetIndex] = releaseTracks[match.targetIndex].copy(audio = files[fileIndex])
            }
        }
        status = "${files.size} dosya isim benzerliğiyle eşleştirildi; kalanlar albüm sırasına yerleştirildi. Kartlardan tek tek değiştirebilirsiniz."
    }
'''
    if marker not in text:
        raise SystemExit("loadCatalog işareti bulunamadı")
    text = text.replace(marker, "\n" + block + marker, 1)

start = text.find("\n    fun fetchCover() {")
end = text.find("\n    fun publishRelease() {", start)
if start >= 0 and end > start:
    text = text[:start] + "\n" + text[end:]

text = text.replace("                    fetchCover = ::fetchCover,\n", "")
if "                    pickBulkAudio = {" not in text:
    text = text.replace(
        "                    pickTrackAudio = { index -> audioTarget = AudioTarget.NewTrack(index); audioPicker.launch(arrayOf(\"audio/*\", \"application/octet-stream\")) },\n",
        "                    pickBulkAudio = { bulkAudioPicker.launch(arrayOf(\"audio/*\", \"application/octet-stream\")) },\n"
        "                    pickTrackAudio = { index -> audioTarget = AudioTarget.NewTrack(index); audioPicker.launch(arrayOf(\"audio/*\", \"application/octet-stream\")) },\n",
        1,
    )
text = text.replace("    fetchCover: () -> Unit,\n", "")
if "    pickBulkAudio: () -> Unit," not in text:
    text = text.replace(
        "    tracks: List<V2TrackDraft>,\n    addTrack: () -> Unit,\n",
        "    tracks: List<V2TrackDraft>,\n    addTrack: () -> Unit,\n    pickBulkAudio: () -> Unit,\n",
        1,
    )

# Önceki yama birden fazla kez çalıştıysa tekrarları tek satıra indir.
call_line = '                    pickBulkAudio = { bulkAudioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },\n'
while call_line + call_line in text:
    text = text.replace(call_line + call_line, call_line)
param_line = "    pickBulkAudio: () -> Unit,\n"
while param_line + param_line in text:
    text = text.replace(param_line + param_line, param_line)

old_cover = '''                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pickCover, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Image, null); Text(" Dosya seç") }
                    OutlinedButton(onClick = fetchCover, enabled = coverUrl.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Text("Görsel Fetch") }
                }
'''
new_cover = '''                OutlinedButton(onClick = pickCover, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Image, null)
                    Text(" Yerel kapak seç • isteğe bağlı")
                }
'''
if old_cover in text:
    text = text.replace(old_cover, new_cover, 1)
elif "Görsel Fetch" in text:
    raise SystemExit("Görsel Fetch bloğu beklenen biçimde bulunamadı")

old_tracks = '''                Button(onClick = addTrack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Text(" Metadata parçası ekle")
                }
'''
new_tracks = old_tracks + '''                OutlinedButton(onClick = pickBulkAudio, enabled = tracks.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.UploadFile, null)
                    Text(" Ses dosyalarını toplu seç ve eşleştir")
                }
'''
if "Ses dosyalarını toplu seç ve eşleştir" not in text:
    if old_tracks not in text:
        raise SystemExit("Parça ekleme bloğu bulunamadı")
    text = text.replace(old_tracks, new_tracks, 1)

# Yayınlanmış içerik yönetimi artık yalnız StudioHub > Yayın Kütüphanesi içindedir.
text = text.replace('    CATALOG("Katalog ve Düzenle"),\n', "")
text = text.replace('                        V2Screen.CATALOG -> Icons.Rounded.LibraryMusic\n', "")
catalog_branch = '''                V2Screen.CATALOG -> V2CatalogScreen(
                    snapshot = snapshot,
                    selected = selectedEdit,
                    onSelected = { selectedEdit = it; editAudio = null },
                    editAudio = editAudio,
                    pickAudio = { audioTarget = AudioTarget.ExistingTrack; audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    save = ::saveTrackEdit,
                    reload = ::loadCatalog,
                    busy = busy,
                )
'''
text = text.replace(catalog_branch, "")

if "Görsel Fetch" in text:
    raise SystemExit("Görsel Fetch metni kaynakta kaldı")
if text.count("                    pickBulkAudio = {") != 1:
    raise SystemExit("Toplu ses çağrısı birden fazla veya eksik")
if text.count("    pickBulkAudio: () -> Unit,") != 1:
    raise SystemExit("Toplu ses parametresi birden fazla veya eksik")
if "bulkAudioPicker" not in text:
    raise SystemExit("Toplu ses seçici eklenemedi")
if "V2Screen.CATALOG" in text:
    raise SystemExit("Eski katalog sekmesi kaynakta kaldı")

path.write_text(text, encoding="utf-8")
print("StudioV2Activity v0.6 sadeleştirmesi uygulandı")
