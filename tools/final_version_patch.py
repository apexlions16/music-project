from __future__ import annotations

from pathlib import Path


def replace_required(path: str, old: str, new: str, count: int | None = None) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    found = text.count(old)
    if found == 0:
        raise SystemExit(f"Beklenen metin bulunamadı: {path}: {old}")
    if count is not None and found != count:
        raise SystemExit(f"Beklenen tekrar sayısı farklı: {path}: {old}: {found} != {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace_required(
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioHubActivity.kt",
    "Mobile v0.3.0",
    "Mobile v0.4.0",
    1,
)
replace_required(
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioCurationActivity.kt",
    "Studio Mobile v0.3.0",
    "Studio Mobile v0.4.0",
    1,
)
replace_required(
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/StudioV2Activity.kt",
    'Text("v0.3.0 • ${screen.title}"',
    'Text("v0.4.0 • ${screen.title}"',
    1,
)
replace_required(
    "studioapp/src/main/kotlin/com/apexlions/aurorastudio/CatalogV2Manager.kt",
    "AuroraStudioMobile/0.3.0",
    "AuroraStudioMobile/0.4.0",
    1,
)
replace_required(
    "pc/AuroraStudioV3.py",
    'base.APP_VERSION = "0.3.0"',
    'base.APP_VERSION = "0.4.0"',
    1,
)
replace_required(
    "pc/AuroraStudioV3.py",
    'APP_VERSION = "0.3.0"',
    'APP_VERSION = "0.4.0"',
    1,
)

workflow = ".github/workflows/aurora-v030-build.yml"
replace_required(
    workflow,
    "Aurora Music 0.4.0 / Studio Mobile 0.3.0 / Studio Windows 0.3.0 Build",
    "Aurora Music 0.5.0 / Studio Mobile 0.4.0 / Studio Windows 0.4.0 Build",
    1,
)
replace_required(workflow, "Aurora Music Android v0.4.0", "Aurora Music Android v0.5.0")
replace_required(workflow, "Aurora-Music-Android-v0.4.0.apk", "Aurora-Music-Android-v0.5.0.apk")
replace_required(workflow, "aurora-music-android-v0.4.0", "aurora-music-android-v0.5.0")
replace_required(workflow, "gh release delete v0.4.0", "gh release delete v0.5.0", 1)
replace_required(workflow, "gh release create v0.4.0", "gh release create v0.5.0", 1)
replace_required(
    workflow,
    '"Spotify benzeri dinamik ana sayfa bölümleri, sanatçı popülerleri, sanatçı seçkileri, üç yayınlık diskografi önizlemesi, iki sütun Tüm Diskografi ekranı, metadata-only Yakında ve kısmi yayın desteği, kilitli parçalar ve yalnızca hazır parçaları oynatma. Kalıcı Android imzası sayesinde uygulama ayarları ve şifreli token güncellemede korunur."',
    '"Kesintisiz arka plan oynatma, MediaSession medya bildirimi, kilit ekranı/araç kontrolleri, 10 saniye ileri-geri sarma, ağ uyandırma kilidi ve otomatik yeniden deneme. Spotify benzeri dinamik ana sayfa, sanatçı popülerleri/seçkileri, iki sütun diskografi, Yakında ve kısmi yayın desteği korunur."',
    1,
)
replace_required(workflow, "Aurora Studio Mobile v0.3.0", "Aurora Studio Mobile v0.4.0")
replace_required(workflow, "Aurora-Studio-Mobile-v0.3.0.apk", "Aurora-Studio-Mobile-v0.4.0.apk")
replace_required(workflow, "aurora-studio-mobile-v0.3.0", "aurora-studio-mobile-v0.4.0")
replace_required(
    workflow,
    '"Yalnızca Spotify metadata kaynağı; bağlantı veya düz metin araması; Spotify kapak ve sanatçı görsellerini indirip Hugging Face\'e taşıma; GitHub kataloğuna yalnız HF görsel adresleri yazma; mevcut şarkı düzenleme, metadata-only/kısmi yayın, ISRC tekrar kullanımı, otomatik çoklu kalite kuyruğu, sanatçı popülerleri, sanatçı listeleri ve ana sayfa bölüm yönetimi. Tokenlar şifreli saklanır ve kalıcı imzalı güncellemede korunur."',
    '"Spotify 2026 Development Mode uyumluluğu: kaldırılan toplu uç noktalar yerine tekil track/artist çağrıları, albüm/şarkı URL-URI, kısa paylaşım bağlantısı, ham ID ve düz metin araması. Kapak/sanatçı görselleri Hugging Face\'e taşınır; düzenleme, kısmi yayın, ISRC tekrar kullanımı, kalite kuyruğu ve sunum yönetimi korunur."',
    1,
)
replace_required(workflow, "Aurora Studio Windows v0.3.0", "Aurora Studio Windows v0.4.0")
replace_required(workflow, "Aurora-Studio-Windows-x64-v0.3.0.zip", "Aurora-Studio-Windows-x64-v0.4.0.zip")
replace_required(workflow, "aurora-studio-windows-v0.3.0", "aurora-studio-windows-v0.4.0")
replace_required(workflow, "aurora-studio-v0.3.0", "aurora-studio-v0.4.0")
replace_required(
    workflow,
    'APP_VERSION = "0.3.0"',
    'APP_VERSION = "0.4.0"',
    1,
)
replace_required(
    workflow,
    '"Yalnızca Spotify metadata kaynağı; Spotify bağlantısı veya düz metin araması; kapak ve sanatçı görsellerini indirip Hugging Face\'e taşıma; GitHub kataloğunda yalnız HF görsel adresleri; metadata-only/kısmi yayın, ISRC tekrar kullanımı, hareketli medya URL alanı, sanatçı popüler sıralaması, sanatçı listeleri ve dinamik ana sayfa bölüm yönetimi. Tokenlar Windows DPAPI ile korunur."',
    '"Spotify 2026 Development Mode uyumluluğu: tekil track/artist uç noktaları, albüm/şarkı URL-URI, kısa paylaşım bağlantısı, ham ID ve düz metin araması. Spotify kapak/sanatçı görselleri Hugging Face\'e taşınır; kısmi yayın, ISRC tekrar kullanımı ve sunum yönetimi korunur. Tokenlar Windows DPAPI ile korunur."',
    1,
)

print("Nihai Aurora sürüm metinleri güncellendi.")
