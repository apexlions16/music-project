from __future__ import annotations

import re
import sys
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests
from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication

import AuroraStudio as base
from AuroraStudioV3 import APP_NAME, APP_VERSION, AuroraStudioV3


class SpotifyOnlyMetadataClient(base.SpotifyMetadataClient):
    """Spotify bağlantısı veya düz arama metniyle çalışan, harici söz servisi çağırmayan istemci."""

    @staticmethod
    def _best(items: list[dict[str, Any]], query: str) -> dict[str, Any] | None:
        normalized = query.strip().casefold()
        best: dict[str, Any] | None = None
        best_score = -10_000
        for index, row in enumerate(items):
            if not row or not row.get("id"):
                continue
            name = str(row.get("name") or "").casefold()
            score = (
                1000 if name == normalized
                else 700 if name.startswith(normalized)
                else 500 if normalized in name
                else 100 - index
            ) + int(row.get("popularity") or 0)
            if score > best_score:
                best = row
                best_score = score
        return best

    def resolve_search(self, value: str) -> str:
        try:
            self.parse_resource(value)
            return value
        except ValueError:
            pass
        payload = self.get("/search", q=value.strip(), type="album,track", limit=10)
        album = self._best((payload.get("albums") or {}).get("items") or [], value)
        if album:
            return f"spotify:album:{album['id']}"
        track = self._best((payload.get("tracks") or {}).get("items") or [], value)
        if track:
            return f"spotify:track:{track['id']}"
        raise RuntimeError("Spotify üzerinde uygun albüm veya şarkı bulunamadı.")

    def fetch_release(self, value: str, include_lyrics: bool = False) -> dict[str, Any]:
        result = super().fetch_release(self.resolve_search(value), include_lyrics=False)
        for track in result.get("tracks", []):
            track["lyrics"] = ""
            track["syncedLyrics"] = ""
        result["metadataSource"] = "spotify"
        return result


base.SpotifyMetadataClient = SpotifyOnlyMetadataClient


class AuroraStudioV3Final(AuroraStudioV3):
    """Spotify-only metadata, HF görsel yerelleştirme ve sunum yönetimi giriş sınıfı."""

    def __init__(self):
        self._spotify_artist_hf_cache: dict[str, str] = {}
        super().__init__()

    def build_ui(self) -> None:
        # V3'teki MusicBrainz sayfasını kurmadan, çalışan temel Studio ekranlarını kullan.
        base.AuroraStudio.build_ui(self)
        self.nav.insertItem(5, base.QListWidgetItem("Sunum ve Listeler"))
        self.pages.insertWidget(5, self.make_curation_page())
        self.nav.currentRowChanged.disconnect()
        self.nav.currentRowChanged.connect(self.pages_set_index)
        self.spotify_auto_lyrics.setChecked(False)
        self.spotify_auto_lyrics.setEnabled(False)
        self.spotify_auto_lyrics.setText("Spotify Web API şarkı sözü sağlamaz • sözler daha sonra elle eklenebilir")
        if hasattr(self, "s_spotify_lyrics"):
            self.s_spotify_lyrics.setChecked(False)
            self.s_spotify_lyrics.setEnabled(False)
            self.s_spotify_lyrics.setText("Kapalı • yalnızca Spotify kullanılacak")

    def pages_set_index(self, index: int) -> None:
        if index < 0:
            return
        self.pages.setCurrentIndex(index)
        if index == 6:
            self.json_editor.setPlainText(base.json.dumps(self.catalog, ensure_ascii=False, indent=2))

    def make_artist_lists_tab(self):
        page = super().make_artist_lists_tab()
        self.artist_list_artist.currentIndexChanged.disconnect()
        self.artist_list_artist.currentIndexChanged.connect(lambda _index: self.refresh_artist_list_tracks(None))
        return page

    def make_home_sections_tab(self):
        page = super().make_home_sections_tab()
        self.home_type.currentIndexChanged.disconnect()
        self.home_type.currentIndexChanged.connect(lambda _index: self.refresh_home_content(None))
        return page

    @staticmethod
    def _is_huggingface_url(url: str) -> bool:
        host = urlparse(url or "").hostname or ""
        host = host.casefold()
        return host in {"huggingface.co", "hf.co"} or host.endswith(".huggingface.co") or host.endswith(".hf.co")

    @staticmethod
    def _image_extension(response: requests.Response) -> str:
        content_type = response.headers.get("Content-Type", "").casefold()
        if "png" in content_type:
            return ".png"
        if "webp" in content_type:
            return ".webp"
        return ".jpg"

    def _download_spotify_image(self, url: str, prefix: str) -> Path:
        if not url.startswith("https://"):
            raise RuntimeError("Spotify görsel adresi geçersiz.")
        response = requests.get(url, timeout=45, stream=True, headers={"User-Agent": f"AuroraStudio/{APP_VERSION}"})
        response.raise_for_status()
        folder = base.CONFIG_DIR / "spotify-image-cache"
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f"{prefix}-{uuid.uuid4().hex}{self._image_extension(response)}"
        with path.open("wb") as handle:
            for chunk in response.iter_content(1024 * 256):
                if chunk:
                    handle.write(chunk)
        return path

    def _upload_spotify_artist_image(self, spotify_id: str, name: str, url: str) -> str:
        key = spotify_id or name.casefold()
        cached = self._spotify_artist_hf_cache.get(key)
        if cached:
            return cached
        local = self._download_spotify_image(url, "artist")
        storage = base.HuggingFaceStorage(self.settings)
        remote = storage.allocate_remote("artist-artwork", local.suffix, lambda message: self.append_log(message))
        hf_url = storage.upload_one(
            local,
            remote,
            f"Aurora Spotify artist artwork: {name}",
            progress=lambda message, _percent: self.append_log(message),
        )
        self._spotify_artist_hf_cache[key] = hf_url
        return hf_url

    def ensure_spotify_artist(self, data: dict[str, Any]) -> str:
        artist_id = base.AuroraStudio.ensure_spotify_artist(self, data)
        artist = self.find_by_id("artists", artist_id)
        if not artist:
            return artist_id
        external_image = str(data.get("image") or "").strip()
        current = str(artist.get("image") or "").strip()
        if self._is_huggingface_url(current):
            return artist_id
        # Spotify CDN adresini hiçbir zaman katalogda bırakma.
        artist["image"] = ""
        if external_image:
            try:
                hf_url = self._upload_spotify_artist_image(
                    str(data.get("spotifyId") or ""),
                    str(data.get("name") or "Sanatçı"),
                    external_image,
                )
                artist["image"] = hf_url
                if not self._is_huggingface_url(str(artist.get("heroImage") or "")):
                    artist["heroImage"] = hf_url
                if not self._is_huggingface_url(str(artist.get("backgroundImage") or "")):
                    artist["backgroundImage"] = hf_url
            except Exception as exc:
                artist["image"] = ""
                artist["heroImage"] = "" if not self._is_huggingface_url(str(artist.get("heroImage") or "")) else artist.get("heroImage", "")
                artist["backgroundImage"] = "" if not self._is_huggingface_url(str(artist.get("backgroundImage") or "")) else artist.get("backgroundImage", "")
                self.append_log(f"Spotify sanatçı görseli HF'ye taşınamadı ({data.get('name')}): {exc}")
        return artist_id

    def build_import_request(self):
        request = super().build_import_request()
        if request.cover_path is None and request.cover_url and not self._is_huggingface_url(request.cover_url):
            self.publish_status.setText("Spotify kapağı indiriliyor; yayın sırasında Hugging Face'e yüklenecek")
            request.cover_path = self._download_spotify_image(request.cover_url, "cover")
            request.cover_url = ""
        return request

    def import_from_open_metadata(self) -> None:
        base.QMessageBox.information(self, APP_NAME, "Bu sürümde yalnızca Spotify metadata kaynağı kullanılır.")


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV3Final()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
