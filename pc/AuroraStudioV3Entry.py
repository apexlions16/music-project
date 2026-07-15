from __future__ import annotations

import re
import sys
import time
import unicodedata
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
    """Spotify 2026 Development Mode ile uyumlu, yalnız Spotify kullanan metadata istemcisi."""

    def __init__(self, settings: base.StudioSettings):
        super().__init__(settings)
        self._token_expires_at = 0.0

    @staticmethod
    def _normalize(value: str) -> str:
        value = unicodedata.normalize("NFD", value or "")
        value = "".join(character for character in value if unicodedata.category(character) != "Mn")
        value = value.casefold().replace("ı", "i")
        value = re.sub(r"[^a-z0-9]+", " ", value)
        return re.sub(r"\s+", " ", value).strip()

    @staticmethod
    def _artist_names(row: dict[str, Any]) -> list[str]:
        return base.ordered_unique(
            [str(artist.get("name") or "").strip() for artist in row.get("artists") or [] if artist.get("name")]
        )

    @classmethod
    def _score(cls, kind: str, row: dict[str, Any], query: str, index: int) -> int:
        normalized_query = cls._normalize(query)
        name = cls._normalize(str(row.get("name") or ""))
        artists = cls._normalize(" ".join(cls._artist_names(row)))
        combined = f"{name} {artists}".strip()
        query_tokens = [token for token in normalized_query.split() if token]
        matched = sum(1 for token in query_tokens if token in combined)
        if combined == normalized_query:
            score = 4000
        elif name == normalized_query:
            score = 3600
        elif combined.startswith(normalized_query):
            score = 2400
        elif name.startswith(normalized_query):
            score = 2200
        elif normalized_query and normalized_query in combined:
            score = 1600
        elif query_tokens and matched == len(query_tokens):
            score = 1300
        else:
            score = matched * 180
        if kind == "track":
            score += 120
        if kind == "album" and int(row.get("total_tracks") or 1) > 1:
            score += 180
        if "album" in normalized_query or "albumu" in normalized_query:
            score += 500 if kind == "album" else -200
        return score - index * 7

    @staticmethod
    def parse_resource(value: str) -> tuple[str, str]:
        value = value.strip()
        match = re.search(
            r"(?:open\.spotify\.com/(?:intl-[a-z]{2}/)?(?:embed/)?|spotify:)(track|album)[/:]([A-Za-z0-9]{10,})",
            value,
            flags=re.IGNORECASE,
        )
        if not match:
            raise ValueError("Geçerli bir Spotify şarkı veya albüm bağlantısı girin.")
        return match.group(1).lower(), match.group(2)

    def _resolve_shared_url(self, value: str) -> str:
        value = value.strip()
        if not value.lower().startswith(("http://", "https://")):
            return value
        host = (urlparse(value).hostname or "").casefold()
        if host not in {"spotify.link", "spoti.fi", "spotify.app.link", "link.spotify.com"}:
            return value
        response = requests.get(
            value,
            timeout=30,
            allow_redirects=True,
            headers={"User-Agent": f"Mozilla/5.0 AuroraStudio/{APP_VERSION}"},
        )
        response.raise_for_status()
        return response.url

    def token(self, force_refresh: bool = False) -> str:
        now = time.time()
        if not force_refresh and self._token and now < self._token_expires_at - 60:
            return self._token
        response = self.session.post(
            self.TOKEN_URL,
            data={"grant_type": "client_credentials"},
            auth=(self.settings.spotify_client_id.strip(), self.settings.spotify_client_secret.strip()),
            timeout=30,
        )
        if not response.ok:
            try:
                payload = response.json()
                detail = payload.get("error_description") or payload.get("error") or ""
            except Exception:
                detail = response.text[:240]
            raise RuntimeError(f"Spotify yetkilendirmesi başarısız: HTTP {response.status_code} • {detail}")
        payload = response.json()
        self._token = str(payload.get("access_token") or "")
        if not self._token:
            raise RuntimeError("Spotify erişim anahtarı boş döndü.")
        self._token_expires_at = now + int(payload.get("expires_in") or 3600)
        self.session.headers["Authorization"] = f"Bearer {self._token}"
        return self._token

    def _request_json(
        self,
        path_or_url: str,
        *,
        params: dict[str, Any] | None = None,
        allow_404: bool = False,
        retry_401: bool = True,
    ) -> dict[str, Any] | None:
        self.token()
        url = path_or_url if path_or_url.startswith("http") else f"{self.API_BASE}{path_or_url}"
        request_params = dict(params or {})
        if not path_or_url.startswith("http") and path_or_url.startswith(("/tracks/", "/albums/", "/search")):
            request_params.setdefault("market", self.settings.spotify_market or "TR")
        response = self.session.get(url, params=request_params or None, timeout=30)
        if response.status_code == 401 and retry_401:
            self._token = ""
            self._token_expires_at = 0.0
            self.token(force_refresh=True)
            return self._request_json(path_or_url, params=params, allow_404=allow_404, retry_401=False)
        if response.status_code == 404 and allow_404:
            return None
        if response.status_code == 429:
            raise RuntimeError(
                f"Spotify hız sınırı. {response.headers.get('Retry-After', 'birkaç')} saniye sonra tekrar deneyin."
            )
        if response.status_code == 403:
            raise RuntimeError(
                "Spotify isteği reddedildi (HTTP 403). Developer Dashboard uygulamasının sahibi aktif Premium kullanmalı; "
                "Client ID ve Client Secret aynı uygulamaya ait olmalıdır."
            )
        if not response.ok:
            try:
                payload = response.json()
                error_data = payload.get("error")
                detail = error_data.get("message", "") if isinstance(error_data, dict) else payload.get("error_description", "")
            except Exception:
                detail = response.text[:240]
            raise RuntimeError(f"Spotify isteği başarısız: HTTP {response.status_code} • {detail}")
        return response.json()

    def get(self, path: str, **params: Any) -> dict[str, Any]:
        return self._request_json(path, params=params) or {}

    def artist_details(self, ids: list[str]) -> dict[str, dict[str, Any]]:
        # Spotify 2026 Development Mode'da toplu GET /artists kaldırıldı.
        result: dict[str, dict[str, Any]] = {}
        for spotify_id in base.ordered_unique([value for value in ids if value]):
            artist = self._request_json(f"/artists/{spotify_id}", allow_404=True)
            if artist and artist.get("id"):
                result[str(artist["id"])] = artist
        return result

    def resolve_search(self, value: str) -> tuple[str, str]:
        resolved = self._resolve_shared_url(value)
        try:
            return self.parse_resource(resolved)
        except ValueError:
            pass

        raw_id = resolved.strip()
        if re.fullmatch(r"[A-Za-z0-9]{22}", raw_id):
            track = self._request_json(f"/tracks/{raw_id}", allow_404=True)
            if track:
                return "track", raw_id
            album = self._request_json(f"/albums/{raw_id}", allow_404=True)
            if album:
                return "album", raw_id

        payload = self.get("/search", q=resolved.strip(), type="track,album", limit=10)
        candidates: list[tuple[int, str, dict[str, Any]]] = []
        for index, row in enumerate((payload.get("tracks") or {}).get("items") or []):
            if row and row.get("id"):
                candidates.append((self._score("track", row, resolved, index), "track", row))
        for index, row in enumerate((payload.get("albums") or {}).get("items") or []):
            if row and row.get("id"):
                candidates.append((self._score("album", row, resolved, index), "album", row))
        if not candidates:
            raise RuntimeError("Spotify üzerinde uygun albüm veya şarkı bulunamadı.")
        _score, kind, row = max(candidates, key=lambda candidate: candidate[0])
        return kind, str(row["id"])

    def _album_tracks(self, album: dict[str, Any]) -> list[dict[str, Any]]:
        simplified = list((album.get("tracks") or {}).get("items") or [])
        next_url = (album.get("tracks") or {}).get("next")
        if not simplified and album.get("id"):
            page = self._request_json(
                f"/albums/{album['id']}/tracks",
                params={"limit": 50, "market": self.settings.spotify_market or "TR"},
            ) or {}
            simplified.extend(page.get("items") or [])
            next_url = page.get("next")
        while next_url:
            page = self._request_json(str(next_url)) or {}
            simplified.extend(page.get("items") or [])
            next_url = page.get("next")

        # Spotify 2026 Development Mode'da toplu GET /tracks kaldırıldı.
        tracks: list[dict[str, Any]] = []
        for spotify_id in base.ordered_unique([str(row.get("id") or "") for row in simplified if row.get("id")]):
            track = self._request_json(f"/tracks/{spotify_id}", allow_404=True)
            if track:
                tracks.append(track)
        return tracks

    def fetch_release(self, value: str, include_lyrics: bool = False) -> dict[str, Any]:
        kind, spotify_id = self.resolve_search(value)
        if kind == "track":
            track = self.get(f"/tracks/{spotify_id}")
            album_id = str((track.get("album") or {}).get("id") or "")
            if not album_id:
                raise RuntimeError("Spotify şarkısının albüm bilgisi alınamadı.")
            album = self.get(f"/albums/{album_id}")
            tracks = [track]
        else:
            album = self.get(f"/albums/{spotify_id}")
            tracks = self._album_tracks(album)
        if not tracks:
            raise RuntimeError("Spotify yayınında kullanılabilir parça bulunamadı.")

        artist_ids = [str(artist.get("id") or "") for artist in album.get("artists") or []]
        for track in tracks:
            artist_ids.extend(str(artist.get("id") or "") for artist in track.get("artists") or [])
        details = self.artist_details(artist_ids)
        album_artist_ids = {str(artist.get("id") or "") for artist in album.get("artists") or []}

        parsed_tracks: list[dict[str, Any]] = []
        for track in sorted(tracks, key=lambda row: (row.get("disc_number", 1), row.get("track_number", 1))):
            artists: list[dict[str, Any]] = []
            for artist in track.get("artists") or []:
                detail = details.get(str(artist.get("id") or ""), artist)
                images = detail.get("images") or []
                artists.append(
                    {
                        "spotifyId": artist.get("id", ""),
                        "name": artist.get("name", ""),
                        "url": (artist.get("external_urls") or {}).get("spotify", ""),
                        "image": (images[0] if images else {}).get("url", ""),
                        "isAlbumArtist": artist.get("id", "") in album_artist_ids,
                    }
                )
            parsed_tracks.append(
                {
                    "spotifyId": track.get("id", ""),
                    "title": track.get("name", ""),
                    "isrc": (track.get("external_ids") or {}).get("isrc", ""),
                    "durationSeconds": round((track.get("duration_ms") or 0) / 1000),
                    "disc": track.get("disc_number", 1),
                    "position": track.get("track_number", 1),
                    "explicit": bool(track.get("explicit", False)),
                    "url": (track.get("external_urls") or {}).get("spotify", ""),
                    "artists": artists,
                    "lyrics": "",
                    "syncedLyrics": "",
                }
            )

        album_images = album.get("images") or []
        release_artists = []
        for artist in album.get("artists") or []:
            detail = details.get(str(artist.get("id") or ""), {})
            images = detail.get("images") or []
            release_artists.append(
                {
                    "spotifyId": artist.get("id", ""),
                    "name": artist.get("name", ""),
                    "url": (artist.get("external_urls") or {}).get("spotify", ""),
                    "image": (images[0] if images else {}).get("url", ""),
                }
            )

        track_mode = kind == "track"
        first_track = parsed_tracks[0]
        first_track_artists = first_track.get("artists") or []
        return {
            "spotifyId": spotify_id if track_mode else album.get("id", ""),
            "title": first_track.get("title", "") if track_mode else album.get("name", ""),
            "type": "single" if track_mode else album.get("album_type", "album"),
            "releaseDate": album.get("release_date", ""),
            "cover": (album_images[0] if album_images else {}).get("url", ""),
            "label": album.get("label", ""),
            "copyright": " • ".join(row.get("text", "") for row in album.get("copyrights") or [] if row.get("text")),
            "url": (album.get("external_urls") or {}).get("spotify", ""),
            "artists": first_track_artists[:1] if track_mode and first_track_artists else release_artists,
            "tracks": parsed_tracks,
            "metadataSource": "spotify",
        }


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
