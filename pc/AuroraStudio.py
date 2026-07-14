from __future__ import annotations

import base64
import concurrent.futures
import copy
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote

# Hugging Face'in Xet/LFS seçimi otomatik bırakılır. Aurora Studio Xet'i kapatmaz.
# Yalnızca indirme zaman aşımı için güvenli bir varsayılan kullanılır.
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")

import requests
from huggingface_hub import CommitOperationAdd, HfApi
from PySide6.QtCore import QDate, QObject, Qt, QThread, Signal
from PySide6.QtGui import QColor, QFont, QIcon, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDateEdit,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QSplitter,
    QStackedWidget,
    QStatusBar,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QTextEdit,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

APP_NAME = "Aurora Studio"
APP_VERSION = "0.2.2"
DEFAULT_REPO = "apexlions16/music-project"
DEFAULT_BRANCH = "main"
DEFAULT_CATALOG_PATH = "catalog/catalog.json"
CONFIG_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "AuroraStudio"
CONFIG_PATH = CONFIG_DIR / "settings.json"
HF_COMMIT_LEDGER_PATH = CONFIG_DIR / "hf-commit-history.json"
HF_STORAGE_INDEX_PATH = "aurora/.aurora-storage-index.json"
HF_SHARD_FILE_LIMIT = 9000
HF_COMMIT_HOURLY_LIMIT = 128
HF_COMMIT_SOFT_LIMIT = 120
HF_UPLOAD_TIMEOUT_SECONDS = 90 * 60
HF_FILE_UPLOAD_TIMEOUT_SECONDS = 60 * 60
HF_COMMIT_FINALIZE_TIMEOUT_SECONDS = 15 * 60


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def slugify(value: str) -> str:
    table = str.maketrans("çğıöşüÇĞİÖŞÜ", "cgiosuCGIOSU")
    value = value.translate(table).lower().strip()
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value or uuid.uuid4().hex[:10]


def opaque_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def normalize_isrc(value: str) -> str:
    """ISRC karşılaştırmasını boşluk ve tirelerden bağımsız yapar."""
    return re.sub(r"[^A-Z0-9]", "", (value or "").upper())


def natural_sort_key(value: str) -> list[Any]:
    return [int(part) if part.isdigit() else part.casefold() for part in re.split(r"(\d+)", value)]


def ordered_unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def resource_path(*parts: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base.joinpath(*parts)


def executable_dir() -> Path:
    return Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent


def protect_text(value: str) -> str:
    if not value:
        return ""
    data = value.encode("utf-8")
    if sys.platform == "win32":
        try:
            import win32crypt

            encrypted = win32crypt.CryptProtectData(data, APP_NAME, None, None, None, 0)[1]
            return "dpapi:" + base64.b64encode(encrypted).decode("ascii")
        except Exception:
            pass
    return "plain:" + base64.b64encode(data).decode("ascii")


def unprotect_text(value: str) -> str:
    if not value:
        return ""
    try:
        prefix, payload = value.split(":", 1)
        raw = base64.b64decode(payload)
        if prefix == "dpapi" and sys.platform == "win32":
            import win32crypt

            return win32crypt.CryptUnprotectData(raw, None, None, None, 0)[1].decode("utf-8")
        return raw.decode("utf-8")
    except Exception:
        return ""


@dataclass
class StudioSettings:
    github_repo: str = DEFAULT_REPO
    github_branch: str = DEFAULT_BRANCH
    github_catalog_path: str = DEFAULT_CATALOG_PATH
    github_token: str = ""
    hf_repo: str = ""
    hf_repo_type: str = "dataset"
    hf_token: str = ""
    spotify_client_id: str = ""
    spotify_client_secret: str = ""
    spotify_market: str = "TR"
    spotify_auto_lyrics: bool = True
    single_max: int = 1
    maxi_max: int = 3
    ep_max: int = 6
    upload_master: bool = True
    make_standard: bool = True
    make_high: bool = True
    make_lossless: bool = True
    make_hires: bool = True

    @classmethod
    def load(cls) -> "StudioSettings":
        if not CONFIG_PATH.exists():
            return cls()
        try:
            raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            raw["github_token"] = unprotect_text(raw.get("github_token", ""))
            raw["hf_token"] = unprotect_text(raw.get("hf_token", ""))
            raw["spotify_client_secret"] = unprotect_text(raw.get("spotify_client_secret", ""))
            return cls(**{key: raw[key] for key in cls.__dataclass_fields__ if key in raw})
        except Exception:
            return cls()

    def save(self) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        raw = self.__dict__.copy()
        raw["github_token"] = protect_text(self.github_token)
        raw["hf_token"] = protect_text(self.hf_token)
        raw["spotify_client_secret"] = protect_text(self.spotify_client_secret)
        CONFIG_PATH.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")

    def classify_release(self, count: int) -> str:
        if count <= self.single_max:
            return "single"
        if count <= self.maxi_max:
            return "maxi_single"
        if count <= self.ep_max:
            return "ep"
        return "album"


class GitHubCatalogClient:
    def __init__(self, settings: StudioSettings):
        self.settings = settings
        self.base = f"https://api.github.com/repos/{settings.github_repo}"
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": f"AuroraStudio/{APP_VERSION}",
            }
        )
        if settings.github_token:
            self.session.headers["Authorization"] = f"Bearer {settings.github_token}"

    def load_catalog(self) -> tuple[dict[str, Any], str]:
        url = f"{self.base}/contents/{self.settings.github_catalog_path}"
        response = self.session.get(url, params={"ref": self.settings.github_branch}, timeout=30)
        response.raise_for_status()
        payload = response.json()
        content = base64.b64decode(payload["content"]).decode("utf-8")
        return json.loads(content), payload["sha"]

    def commit_catalog(self, catalog: dict[str, Any], sha: str, message: str) -> str:
        catalog["updatedAt"] = now_iso()
        raw = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
        url = f"{self.base}/contents/{self.settings.github_catalog_path}"
        response = self.session.put(
            url,
            json={
                "message": message,
                "content": base64.b64encode(raw.encode("utf-8")).decode("ascii"),
                "sha": sha,
                "branch": self.settings.github_branch,
            },
            timeout=45,
        )
        if response.status_code == 409:
            raise RuntimeError("GitHub kataloğu başka bir işlem tarafından değiştirildi. Önce GitHub'dan yenileyin.")
        response.raise_for_status()
        return response.json()["content"]["sha"]

    def test(self) -> str:
        response = self.session.get(self.base, timeout=20)
        response.raise_for_status()
        data = response.json()
        return f"GitHub bağlantısı başarılı: {data['full_name']}"


class SpotifyMetadataClient:
    API_BASE = "https://api.spotify.com/v1"
    TOKEN_URL = "https://accounts.spotify.com/api/token"

    def __init__(self, settings: StudioSettings):
        if not settings.spotify_client_id or not settings.spotify_client_secret:
            raise RuntimeError("Spotify Client ID ve Client Secret ayarlanmamış.")
        self.settings = settings
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": f"AuroraStudio/{APP_VERSION}"})
        self._token = ""

    @staticmethod
    def parse_resource(value: str) -> tuple[str, str]:
        value = value.strip()
        match = re.search(r"(?:open\.spotify\.com/|spotify:)(track|album)[/:]([A-Za-z0-9]+)", value)
        if not match:
            raise ValueError("Geçerli bir Spotify şarkı veya albüm bağlantısı girin.")
        return match.group(1), match.group(2)

    def token(self) -> str:
        if self._token:
            return self._token
        response = self.session.post(
            self.TOKEN_URL,
            data={"grant_type": "client_credentials"},
            auth=(self.settings.spotify_client_id, self.settings.spotify_client_secret),
            timeout=30,
        )
        response.raise_for_status()
        self._token = response.json()["access_token"]
        self.session.headers["Authorization"] = f"Bearer {self._token}"
        return self._token

    def get(self, path: str, **params: Any) -> dict[str, Any]:
        self.token()
        params.setdefault("market", self.settings.spotify_market or "TR")
        response = self.session.get(f"{self.API_BASE}{path}", params=params, timeout=30)
        if response.status_code == 429:
            raise RuntimeError(f"Spotify hız sınırı. {response.headers.get('Retry-After', 'birkaç')} saniye sonra tekrar deneyin.")
        response.raise_for_status()
        return response.json()

    def artist_details(self, ids: list[str]) -> dict[str, dict[str, Any]]:
        ids = ordered_unique([value for value in ids if value])
        result: dict[str, dict[str, Any]] = {}
        for start in range(0, len(ids), 50):
            payload = self.get("/artists", ids=",".join(ids[start:start + 50]))
            for artist in payload.get("artists", []):
                if artist:
                    result[artist.get("id", "")] = artist
        return result

    def fetch_release(self, value: str, include_lyrics: bool = True) -> dict[str, Any]:
        kind, spotify_id = self.parse_resource(value)
        if kind == "track":
            track = self.get(f"/tracks/{spotify_id}")
            album = self.get(f"/albums/{track['album']['id']}")
            tracks = [track]
        else:
            album = self.get(f"/albums/{spotify_id}")
            simplified = list(album.get("tracks", {}).get("items", []))
            next_url = album.get("tracks", {}).get("next")
            while next_url:
                response = self.session.get(next_url, timeout=30)
                response.raise_for_status()
                page = response.json()
                simplified.extend(page.get("items", []))
                next_url = page.get("next")
            tracks = []
            for start in range(0, len(simplified), 50):
                ids = [row.get("id") for row in simplified[start:start + 50] if row.get("id")]
                if ids:
                    tracks.extend([row for row in self.get("/tracks", ids=",".join(ids)).get("tracks", []) if row])

        artist_ids = [artist.get("id", "") for artist in album.get("artists", [])]
        for track in tracks:
            artist_ids.extend(artist.get("id", "") for artist in track.get("artists", []))
        details = self.artist_details(artist_ids)
        album_artist_ids = {artist.get("id", "") for artist in album.get("artists", [])}
        parsed_tracks: list[dict[str, Any]] = []
        for track in sorted(tracks, key=lambda row: (row.get("disc_number", 1), row.get("track_number", 1))):
            artists = []
            for artist in track.get("artists", []):
                detail = details.get(artist.get("id", ""), artist)
                artists.append({
                    "spotifyId": artist.get("id", ""),
                    "name": artist.get("name", ""),
                    "url": artist.get("external_urls", {}).get("spotify", ""),
                    "image": (detail.get("images") or [{}])[0].get("url", ""),
                    "isAlbumArtist": artist.get("id", "") in album_artist_ids,
                })
            plain = synced = ""
            if include_lyrics:
                plain, synced = self.fetch_lyrics(
                    track.get("name", ""),
                    artists[0]["name"] if artists else "",
                    album.get("name", ""),
                    round(track.get("duration_ms", 0) / 1000),
                )
            parsed_tracks.append({
                "spotifyId": track.get("id", ""),
                "title": track.get("name", ""),
                "isrc": track.get("external_ids", {}).get("isrc", ""),
                "durationSeconds": round(track.get("duration_ms", 0) / 1000),
                "disc": track.get("disc_number", 1),
                "position": track.get("track_number", 1),
                "explicit": bool(track.get("explicit", False)),
                "url": track.get("external_urls", {}).get("spotify", ""),
                "artists": artists,
                "lyrics": plain,
                "syncedLyrics": synced,
            })
        images = album.get("images") or []
        return {
            "spotifyId": album.get("id", ""),
            "title": album.get("name", ""),
            "type": album.get("album_type", "album"),
            "releaseDate": album.get("release_date", ""),
            "cover": images[0].get("url", "") if images else "",
            "label": album.get("label", ""),
            "copyright": " • ".join(row.get("text", "") for row in album.get("copyrights", []) if row.get("text")),
            "url": album.get("external_urls", {}).get("spotify", ""),
            "artists": [
                {
                    "spotifyId": artist.get("id", ""),
                    "name": artist.get("name", ""),
                    "url": artist.get("external_urls", {}).get("spotify", ""),
                    "image": (details.get(artist.get("id", ""), {}).get("images") or [{}])[0].get("url", ""),
                }
                for artist in album.get("artists", [])
            ],
            "tracks": parsed_tracks,
        }

    def fetch_lyrics(self, title: str, artist: str, album: str, duration: int) -> tuple[str, str]:
        if not title or not artist:
            return "", ""
        try:
            response = self.session.get(
                "https://lrclib.net/api/get",
                params={"track_name": title, "artist_name": artist, "album_name": album, "duration": duration},
                timeout=15,
                headers={"User-Agent": f"AuroraStudio/{APP_VERSION} (personal music catalog)"},
            )
            if response.status_code == 200:
                payload = response.json()
                return payload.get("plainLyrics") or "", payload.get("syncedLyrics") or ""
        except Exception:
            pass
        return "", ""


class HuggingFaceStorage:
    """Hugging Face medya yükleme, klasör shard ve commit bütçesi yöneticisi."""

    def __init__(self, settings: StudioSettings):
        self.settings = settings
        self.api = HfApi(token=settings.hf_token)
        self._storage_index: dict[str, Any] | None = None
        self._storage_index_dirty = False
        self._index_temp_path: Path | None = None

    def ensure_repo(self) -> None:
        if not self.settings.hf_repo:
            raise RuntimeError("Hugging Face depo adı ayarlanmamış.")
        self.api.create_repo(
            repo_id=self.settings.hf_repo,
            repo_type=self.settings.hf_repo_type,
            private=False,
            exist_ok=True,
            token=self.settings.hf_token,
        )

    def _auth_headers(self) -> dict[str, str]:
        headers = {"User-Agent": f"AuroraStudio/{APP_VERSION}", "Cache-Control": "no-cache"}
        if self.settings.hf_token:
            headers["Authorization"] = f"Bearer {self.settings.hf_token}"
        return headers

    def _empty_index(self) -> dict[str, Any]:
        return {
            "version": 1,
            "shardLimit": HF_SHARD_FILE_LIMIT,
            "counts": {},
            "updatedAt": now_iso(),
        }

    def _scan_existing_shards(self, progress: Callable[[str], None] | None = None) -> dict[str, Any]:
        if progress:
            progress("Hugging Face klasörleri ilk kez taranıyor; sonraki yüklemelerde bu işlem tekrarlanmayacak…")
        index = self._empty_index()
        pattern = re.compile(r"^aurora/(?P<category>[a-z0-9-]+?)(?P<shard>[1-9][0-9]*)/[^/]+$")
        try:
            files = self.api.list_repo_files(
                repo_id=self.settings.hf_repo,
                repo_type=self.settings.hf_repo_type,
                token=self.settings.hf_token,
            )
        except Exception as exc:
            raise RuntimeError(f"Hugging Face klasör listesi alınamadı: {exc}") from exc
        for path in files:
            match = pattern.match(path)
            if not match:
                continue
            category = match.group("category")
            shard = match.group("shard")
            category_counts = index["counts"].setdefault(category, {})
            category_counts[shard] = int(category_counts.get(shard, 0)) + 1
        self._storage_index_dirty = True
        return index

    def _load_storage_index(self, progress: Callable[[str], None] | None = None) -> dict[str, Any]:
        if self._storage_index is not None:
            return self._storage_index
        self.ensure_repo()
        try:
            response = requests.get(
                self.resolve_url(HF_STORAGE_INDEX_PATH),
                headers=self._auth_headers(),
                params={"aurora_cache_bust": int(time.time())},
                timeout=(15, 60),
            )
            if response.status_code == 200:
                payload = response.json()
                if isinstance(payload, dict) and isinstance(payload.get("counts"), dict):
                    payload["shardLimit"] = HF_SHARD_FILE_LIMIT
                    self._storage_index = payload
                    return payload
            elif response.status_code not in {401, 403, 404}:
                response.raise_for_status()
        except (ValueError, requests.RequestException):
            pass
        self._storage_index = self._scan_existing_shards(progress)
        return self._storage_index

    @staticmethod
    def _safe_category(category: str) -> str:
        value = re.sub(r"[^a-z0-9-]+", "-", (category or "media").strip().lower()).strip("-")
        return value or "media"

    def allocate_remote(
        self,
        category: str,
        suffix: str,
        progress: Callable[[str], None] | None = None,
    ) -> str:
        index = self._load_storage_index(progress)
        category = self._safe_category(category)
        suffix = suffix.lower().strip()
        if suffix and not suffix.startswith("."):
            suffix = "." + suffix
        counts = index.setdefault("counts", {}).setdefault(category, {})
        shard = 1
        while int(counts.get(str(shard), 0)) >= HF_SHARD_FILE_LIMIT:
            shard += 1
        counts[str(shard)] = int(counts.get(str(shard), 0)) + 1
        index["updatedAt"] = now_iso()
        index["shardLimit"] = HF_SHARD_FILE_LIMIT
        self._storage_index_dirty = True
        return f"aurora/{category}{shard}/{uuid.uuid4().hex}{suffix}"

    def _recent_local_commits(self) -> list[float]:
        cutoff = time.time() - 3600
        try:
            payload = json.loads(HF_COMMIT_LEDGER_PATH.read_text(encoding="utf-8"))
            values = [float(value) for value in payload if float(value) >= cutoff]
        except Exception:
            values = []
        return sorted(values)

    def _save_local_commits(self, values: list[float]) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        HF_COMMIT_LEDGER_PATH.write_text(json.dumps(values[-HF_COMMIT_HOURLY_LIMIT:], indent=2), encoding="utf-8")

    def _check_commit_budget(self) -> None:
        values = self._recent_local_commits()
        if len(values) >= HF_COMMIT_SOFT_LIMIT:
            wait_seconds = max(1, int(values[0] + 3600 - time.time()))
            wait_minutes = max(1, (wait_seconds + 59) // 60)
            raise RuntimeError(
                f"Hugging Face commit güvenlik sınırına ulaşıldı: son saatte {len(values)} commit. "
                f"Platformun 128 commit/saat sınırını aşmamak için yaklaşık {wait_minutes} dakika bekleyin."
            )

    def _record_commit(self) -> None:
        values = self._recent_local_commits()
        values.append(time.time())
        self._save_local_commits(values)

    def _index_operation(self) -> CommitOperationAdd | None:
        if not self._storage_index_dirty or self._storage_index is None:
            return None
        if self._index_temp_path:
            self._index_temp_path.unlink(missing_ok=True)
        handle = tempfile.NamedTemporaryFile(prefix="aurora-hf-index-", suffix=".json", delete=False)
        handle.close()
        self._index_temp_path = Path(handle.name)
        self._index_temp_path.write_text(
            json.dumps(self._storage_index, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return CommitOperationAdd(path_in_repo=HF_STORAGE_INDEX_PATH, path_or_fileobj=str(self._index_temp_path))

    def _start_commit(self, operations: list[CommitOperationAdd], message: str):
        kwargs = {
            "repo_id": self.settings.hf_repo,
            "repo_type": self.settings.hf_repo_type,
            "operations": operations,
            "commit_message": message,
            "token": self.settings.hf_token,
        }
        try:
            return self.api.create_commit(**kwargs, run_as_future=True)
        except TypeError:
            executor = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="aurora-hf-upload")
            future = executor.submit(self.api.create_commit, **kwargs)
            future._aurora_executor = executor  # type: ignore[attr-defined]
            return future

    @staticmethod
    def _human_size(size: int) -> str:
        value = float(max(0, size))
        units = ["B", "KB", "MB", "GB", "TB"]
        unit = units[0]
        for unit in units:
            if value < 1024 or unit == units[-1]:
                break
            value /= 1024
        return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"

    def create_commit(
        self,
        files: list[tuple[Path, str]],
        message: str,
        progress: Callable[[str, int], None] | None = None,
    ) -> None:
        self.ensure_repo()
        index_operation = self._index_operation()
        if not files and index_operation is None:
            return
        self._check_commit_budget()

        total_bytes = sum(local.stat().st_size for local, _remote in files)
        completed_bytes = 0
        operations: list[CommitOperationAdd] = []
        upload_started = time.monotonic()

        if progress:
            progress(
                f"Hugging Face Xet yüklemesi hazırlanıyor: {len(files)} dosya • "
                f"toplam {self._human_size(total_bytes)} • finalde tek commit",
                0,
            )

        for file_index, (local, remote) in enumerate(files, start=1):
            file_size = local.stat().st_size
            operation: CommitOperationAdd | None = None
            last_error: Exception | None = None

            for attempt in range(1, 3):
                operation = CommitOperationAdd(path_in_repo=remote, path_or_fileobj=str(local))
                if progress:
                    progress(
                        f"Dosya {file_index}/{len(files)}: {local.name} "
                        f"({self._human_size(file_size)}) Xet ile yükleniyor • deneme {attempt}/2 • "
                        f"tamamlanan {self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                        int((completed_bytes / max(total_bytes, 1)) * 92),
                    )

                executor = concurrent.futures.ThreadPoolExecutor(
                    max_workers=1,
                    thread_name_prefix="aurora-hf-xet-upload",
                )
                future = executor.submit(
                    self.api.preupload_lfs_files,
                    repo_id=self.settings.hf_repo,
                    repo_type=self.settings.hf_repo_type,
                    additions=[operation],
                    token=self.settings.hf_token,
                    num_threads=1,
                    free_memory=True,
                )
                file_started = time.monotonic()
                last_heartbeat = -1
                try:
                    while not future.done():
                        elapsed_file = int(time.monotonic() - file_started)
                        elapsed_total = int(time.monotonic() - upload_started)
                        if elapsed_file >= HF_FILE_UPLOAD_TIMEOUT_SECONDS:
                            future.cancel()
                            raise TimeoutError(
                                f"{local.name} dosyası 60 dakika boyunca tamamlanamadı. "
                                "Xet yüklemesi durduruldu; bağlantıyı kontrol edip yeniden deneyin."
                            )
                        if elapsed_total >= HF_UPLOAD_TIMEOUT_SECONDS:
                            future.cancel()
                            raise TimeoutError(
                                "Hugging Face yüklemesi 90 dakikalık toplam güvenlik süresini aştı."
                            )
                        heartbeat = elapsed_file // 5
                        if progress and heartbeat != last_heartbeat:
                            last_heartbeat = heartbeat
                            progress(
                                f"Dosya {file_index}/{len(files)}: {local.name} "
                                f"({self._human_size(file_size)}) Xet ile işleniyor/yükleniyor • "
                                f"{elapsed_file // 60:02d}:{elapsed_file % 60:02d} • "
                                f"tamamlanan {self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                                int((completed_bytes / max(total_bytes, 1)) * 92),
                            )
                        time.sleep(1)
                    future.result()
                    last_error = None
                    break
                except Exception as exc:
                    last_error = exc
                    text = str(exc).lower()
                    transient = any(
                        marker in text
                        for marker in (
                            "429", "500", "502", "503", "504", "connection",
                            "temporarily", "timeout", "timed out",
                        )
                    )
                    if not transient or attempt >= 2:
                        raise
                    if progress:
                        progress(
                            f"{local.name} geçici Hugging Face hatası verdi. "
                            f"8 saniye sonra Xet ile son kez denenecek: {exc}",
                            int((completed_bytes / max(total_bytes, 1)) * 92),
                        )
                    time.sleep(8)
                finally:
                    executor.shutdown(wait=False, cancel_futures=True)

            if last_error is not None or operation is None:
                raise RuntimeError(f"{local.name} yüklenemedi: {last_error}")

            operations.append(operation)
            completed_bytes += file_size
            if progress:
                progress(
                    f"Dosya {file_index}/{len(files)} tamamlandı: {local.name} • "
                    f"{self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                    int((completed_bytes / max(total_bytes, 1)) * 92),
                )

        if index_operation:
            operations.append(index_operation)

        last_error: Exception | None = None
        for attempt in range(1, 3):
            future = self._start_commit(operations, message)
            commit_started = time.monotonic()
            last_heartbeat = -1
            try:
                while not future.done():
                    elapsed = int(time.monotonic() - commit_started)
                    if elapsed >= HF_COMMIT_FINALIZE_TIMEOUT_SECONDS:
                        future.cancel()
                        raise TimeoutError(
                            "Medya verileri gönderildi ancak Hugging Face final commit işlemi "
                            "15 dakika içinde tamamlanamadı."
                        )
                    heartbeat = elapsed // 5
                    if progress and heartbeat != last_heartbeat:
                        last_heartbeat = heartbeat
                        progress(
                            f"Tüm dosyalar Xet ile gönderildi; tek Hugging Face commit'i oluşturuluyor • "
                            f"{elapsed // 60:02d}:{elapsed % 60:02d} • deneme {attempt}/2",
                            96,
                        )
                    time.sleep(1)
                future.result()
                self._record_commit()
                self._storage_index_dirty = False
                if progress:
                    progress("Hugging Face Xet yüklemesi ve tek commit tamamlandı.", 100)
                return
            except Exception as exc:
                last_error = exc
                text = str(exc).lower()
                transient = any(
                    marker in text
                    for marker in (
                        "429", "500", "502", "503", "504", "connection",
                        "temporarily", "timeout", "timed out",
                    )
                )
                if not transient or attempt >= 2:
                    if "429" in text:
                        raise RuntimeError(
                            "Hugging Face saatlik commit veya hız sınırına ulaştı. "
                            "Bir süre bekleyip yeniden deneyin."
                        ) from exc
                    raise RuntimeError(f"Hugging Face yüklemesi başarısız: {exc}") from exc
                if progress:
                    progress(
                        f"Final commit geçici hata verdi. 8 saniye sonra son kez deneniyor: {exc}",
                        96,
                    )
                time.sleep(8)
            finally:
                executor = getattr(future, "_aurora_executor", None)
                if executor:
                    executor.shutdown(wait=False, cancel_futures=True)

        if last_error:
            raise last_error

    def upload_one(
        self,
        local_path: Path,
        remote_path: str,
        message: str,
        progress: Callable[[str, int], None] | None = None,
    ) -> str:
        self.create_commit([(local_path, remote_path)], message, progress=progress)
        return self.resolve_url(remote_path)

    def resolve_url(self, path: str) -> str:
        kind = "datasets" if self.settings.hf_repo_type == "dataset" else "models"
        return f"https://huggingface.co/{kind}/{self.settings.hf_repo}/resolve/main/{quote(path, safe='/')}"

    def test(self) -> str:
        who = self.api.whoami(token=self.settings.hf_token)
        values = self._recent_local_commits()
        return (
            f"Hugging Face bağlantısı başarılı: {who.get('name', 'hesap')} • "
            f"Aurora son saatte {len(values)}/{HF_COMMIT_SOFT_LIMIT} güvenli commit kullandı"
        )


class MediaProcessor:
    LOSSLESS_CODECS = {"flac", "alac", "wavpack", "ape", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le"}

    def __init__(self):
        tools = executable_dir() / "tools"
        self.ffmpeg = tools / "ffmpeg.exe"
        self.ffprobe = tools / "ffprobe.exe"
        if not self.ffmpeg.exists():
            found = shutil.which("ffmpeg")
            if found:
                self.ffmpeg = Path(found)
        if not self.ffprobe.exists():
            found = shutil.which("ffprobe")
            if found:
                self.ffprobe = Path(found)

    def check(self) -> None:
        if not self.ffmpeg.exists() or not self.ffprobe.exists():
            raise RuntimeError("FFmpeg araçları bulunamadı. Aurora Studio paketini eksiksiz çıkardığınızdan emin olun.")

    def probe(self, path: Path) -> dict[str, Any]:
        self.check()
        result = subprocess.run(
            [str(self.ffprobe), "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=True,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0,
        )
        data = json.loads(result.stdout)
        stream = next((row for row in data.get("streams", []) if row.get("codec_type") == "audio"), {})
        duration = float(stream.get("duration") or data.get("format", {}).get("duration") or 0)
        sample_rate = int(stream.get("sample_rate") or 0)
        bits = int(stream.get("bits_per_raw_sample") or stream.get("bits_per_sample") or 0)
        codec = str(stream.get("codec_name") or "unknown")
        channels = int(stream.get("channels") or 0)
        bitrate = int(stream.get("bit_rate") or data.get("format", {}).get("bit_rate") or 0)
        return {
            "duration": max(0, round(duration)),
            "sample_rate": sample_rate,
            "bits": bits,
            "codec": codec,
            "channels": channels,
            "bitrate": bitrate,
            "lossless": codec in self.LOSSLESS_CODECS or path.suffix.lower() in {".wav", ".flac", ".aiff", ".alac"},
        }

    def _run(self, args: list[str]) -> None:
        command = [str(self.ffmpeg), "-hide_banner", "-loglevel", "error", "-y", *args]
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "FFmpeg dönüştürme hatası")

    def process(self, source: Path, output_dir: Path, settings: StudioSettings) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        info = self.probe(source)
        output_dir.mkdir(parents=True, exist_ok=True)
        variants: list[dict[str, Any]] = []

        def add_variant(kind: str, label: str, codec: str, ext: str, args: list[str], **meta: Any) -> None:
            target = output_dir / f"{uuid.uuid4().hex}.{ext}"
            self._run(["-i", str(source), "-map_metadata", "-1", "-vn", *args, str(target)])
            variants.append({"kind": kind, "label": label, "codec": codec, "path": target, **meta})

        if settings.make_standard:
            add_variant("standard", "Standart", "AAC 128 kbps", "m4a", ["-c:a", "aac", "-b:a", "128k", "-ar", "44100"], bitrateKbps=128, sampleRateKhz=44.1)
        if settings.make_high:
            add_variant("high", "Yüksek Kalite", "AAC 256 kbps", "m4a", ["-c:a", "aac", "-b:a", "256k", "-ar", "48000"], bitrateKbps=256, sampleRateKhz=48.0)
        if info["lossless"] and settings.make_lossless:
            add_variant(
                "lossless",
                "Lossless",
                "FLAC 16-bit/44.1 kHz",
                "flac",
                ["-c:a", "flac", "-compression_level", "8", "-ar", "44100", "-sample_fmt", "s16"],
                sampleRateKhz=44.1,
                bitDepth=16,
            )
        if info["lossless"] and settings.make_hires and (info["sample_rate"] > 48000 or info["bits"] >= 24):
            add_variant(
                "hires",
                "Hi-Res Lossless",
                f"FLAC {info['bits'] or 24}-bit/{info['sample_rate'] / 1000:g} kHz",
                "flac",
                ["-c:a", "flac", "-compression_level", "8"],
                sampleRateKhz=round(info["sample_rate"] / 1000, 1),
                bitDepth=info["bits"] or 24,
            )
        return info, variants


@dataclass
class ImportTrack:
    path: Path
    title: str
    isrc: str = ""
    primary_artist_ids: list[str] = field(default_factory=list)
    featured_artist_ids: list[str] = field(default_factory=list)
    spotify_id: str = ""
    spotify_url: str = ""
    duration_seconds: int = 0
    disc: int = 1
    position: int = 1
    explicit: bool = False
    lyrics: str = ""
    synced_lyrics: str = ""
    credits: list[dict[str, Any]] = field(default_factory=list)
    atmos_path: Path | None = None

    @property
    def has_master(self) -> bool:
        return self.path.is_file()


@dataclass
class ImportRequest:
    artist_id: str
    release_artist_ids: list[str]
    release_title: str
    release_type: str
    release_date: str
    label: str
    copyright_text: str
    description: str
    cover_url: str
    cover_path: Path | None
    hero_url: str
    animated_cover_path: Path | None
    tracks: list[ImportTrack]
    featured: bool
    spotify_id: str = ""
    spotify_url: str = ""


class TaskThread(QThread):
    progress = Signal(str, int)
    succeeded = Signal(object)
    failed = Signal(str)

    def __init__(self, task: Callable[[Callable[[str, int], None]], Any], parent: QObject | None = None):
        super().__init__(parent)
        self.task = task

    def run(self) -> None:
        try:
            result = self.task(lambda text, value: self.progress.emit(text, value))
            self.succeeded.emit(result)
        except Exception as exc:
            self.failed.emit(f"{exc}\n\n{traceback.format_exc()}")


class LyricsDialog(QDialog):
    def __init__(self, track: ImportTrack, parent: QWidget | None = None):
        super().__init__(parent)
        self.setWindowTitle("Şarkı Sözleri ve Künye")
        self.resize(760, 650)
        self.track = track
        layout = QVBoxLayout(self)
        tabs = QTabWidget()
        self.plain = QPlainTextEdit(track.lyrics)
        self.synced = QPlainTextEdit(track.synced_lyrics)
        self.synced.setPlaceholderText("[00:12.40] İlk satır\n[00:17.20] İkinci satır")
        self.credits = QPlainTextEdit(json.dumps(track.credits, ensure_ascii=False, indent=2))
        self.credits.setPlaceholderText('[{"role": "Söz ve Müzik", "names": ["Sanatçı"]}]')
        artists_page = QWidget()
        artists_form = QFormLayout(artists_page)
        self.primary_artists = QLineEdit(", ".join(track.primary_artist_ids))
        self.featured_artists = QLineEdit(", ".join(track.featured_artist_ids))
        self.primary_artists.setPlaceholderText("artist_id, artist_id")
        self.featured_artists.setPlaceholderText("feat artist_id, feat artist_id")
        artists_form.addRow("Ana sanatçı ID'leri", self.primary_artists)
        artists_form.addRow("Feat sanatçı ID'leri", self.featured_artists)
        tabs.addTab(artists_page, "Sanatçılar / Feat")
        tabs.addTab(self.plain, "Normal Sözler")
        tabs.addTab(self.synced, "Senkronize LRC")
        tabs.addTab(self.credits, "Künye (JSON)")
        layout.addWidget(tabs)
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def accept(self) -> None:
        try:
            credits = json.loads(self.credits.toPlainText().strip() or "[]")
            if not isinstance(credits, list):
                raise ValueError("Künye JSON dizisi olmalıdır.")
            self.track.primary_artist_ids = ordered_unique([value.strip() for value in self.primary_artists.text().split(",") if value.strip()])
            self.track.featured_artist_ids = [value for value in ordered_unique([value.strip() for value in self.featured_artists.text().split(",") if value.strip()]) if value not in self.track.primary_artist_ids]
            self.track.lyrics = self.plain.toPlainText()
            self.track.synced_lyrics = self.synced.toPlainText()
            self.track.credits = credits
            super().accept()
        except Exception as exc:
            QMessageBox.warning(self, "Künye Hatası", str(exc))


class AuroraStudio(QMainWindow):
    def __init__(self):
        super().__init__()
        self.settings = StudioSettings.load()
        self.catalog = self.empty_catalog()
        self.catalog_sha = ""
        self.dirty = False
        self.active_thread: TaskThread | None = None
        self.import_tracks: list[ImportTrack] = []
        self.import_release_artist_ids: list[str] = []
        self.import_spotify_id = ""
        self.import_spotify_url = ""
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self.resize(1480, 900)
        self.setMinimumSize(1180, 720)
        self.setStatusBar(QStatusBar())
        self.build_ui()
        self.apply_style()
        self.refresh_all_views()
        if self.settings.github_token:
            self.load_from_github()

    @staticmethod
    def empty_catalog() -> dict[str, Any]:
        return {
            "schemaVersion": 3,
            "updatedAt": now_iso(),
            "brand": {"name": "Aurora Music", "subtitle": "Kişisel yüksek çözünürlüklü müzik arşivi", "logoText": "A"},
            "features": {"syncedLyrics": True, "animatedCovers": True, "artistBackgrounds": True, "offlineDownloads": True},
            "featuredReleaseIds": [],
            "artists": [],
            "tracks": [],
            "releases": [],
        }

    def build_ui(self) -> None:
        central = QWidget()
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(230)
        side_layout = QVBoxLayout(sidebar)
        side_layout.setContentsMargins(18, 22, 18, 18)
        logo = QLabel("A")
        logo.setObjectName("logo")
        logo.setAlignment(Qt.AlignCenter)
        logo.setFixedSize(48, 48)
        title = QLabel("Aurora Studio")
        title.setObjectName("brandTitle")
        subtitle = QLabel("Müzik dağıtım ve katalog merkezi")
        subtitle.setWordWrap(True)
        subtitle.setObjectName("muted")
        side_layout.addWidget(logo, alignment=Qt.AlignLeft)
        side_layout.addSpacing(8)
        side_layout.addWidget(title)
        side_layout.addWidget(subtitle)
        side_layout.addSpacing(24)

        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        self.nav.setSpacing(4)
        for text in ["Genel Bakış", "Yeni Yayın", "Sanatçılar", "Yayınlar", "Şarkılar", "Katalog JSON", "Ayarlar"]:
            self.nav.addItem(QListWidgetItem(text))
        self.nav.currentRowChanged.connect(self.pages_set_index)
        side_layout.addWidget(self.nav, 1)
        version = QLabel(f"v{APP_VERSION}\nTokenlar Windows hesabında şifreli saklanır")
        version.setObjectName("muted")
        version.setWordWrap(True)
        side_layout.addWidget(version)

        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(24, 18, 24, 20)
        toolbar = QHBoxLayout()
        self.connection_label = QLabel("Bağlantı bekleniyor")
        self.connection_label.setObjectName("connectionLabel")
        toolbar.addWidget(self.connection_label)
        toolbar.addStretch()
        self.reload_btn = QPushButton("GitHub'dan Yenile")
        self.reload_btn.clicked.connect(self.load_from_github)
        self.commit_btn = QPushButton("Değişiklikleri Commit Et")
        self.commit_btn.setObjectName("primaryButton")
        self.commit_btn.clicked.connect(self.commit_catalog)
        toolbar.addWidget(self.reload_btn)
        toolbar.addWidget(self.commit_btn)
        content_layout.addLayout(toolbar)

        self.pages = QStackedWidget()
        self.pages.addWidget(self.make_dashboard_page())
        self.pages.addWidget(self.make_import_page())
        self.pages.addWidget(self.make_artists_page())
        self.pages.addWidget(self.make_releases_page())
        self.pages.addWidget(self.make_tracks_page())
        self.pages.addWidget(self.make_json_page())
        self.pages.addWidget(self.make_settings_page())
        content_layout.addWidget(self.pages, 1)

        root.addWidget(sidebar)
        root.addWidget(content, 1)
        self.setCentralWidget(central)
        self.nav.setCurrentRow(0)

    def pages_set_index(self, index: int) -> None:
        if index < 0:
            return
        self.pages.setCurrentIndex(index)
        if index == 5:
            self.json_editor.setPlainText(json.dumps(self.catalog, ensure_ascii=False, indent=2))

    def page_container(self, title: str, subtitle: str) -> tuple[QWidget, QVBoxLayout]:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 8, 0, 0)
        heading = QLabel(title)
        heading.setObjectName("pageTitle")
        desc = QLabel(subtitle)
        desc.setObjectName("muted")
        desc.setWordWrap(True)
        layout.addWidget(heading)
        layout.addWidget(desc)
        layout.addSpacing(12)
        return page, layout

    def make_dashboard_page(self) -> QWidget:
        page, layout = self.page_container("Genel Bakış", "Aurora Music kataloğunun canlı durumu ve hızlı işlemler")
        cards = QGridLayout()
        self.artist_count = QLabel("0")
        self.release_count = QLabel("0")
        self.track_count = QLabel("0")
        self.source_count = QLabel("0")
        for col, (label, widget) in enumerate(
            [("Sanatçı", self.artist_count), ("Yayın", self.release_count), ("Şarkı", self.track_count), ("Ses Sürümü", self.source_count)]
        ):
            card = QFrame()
            card.setObjectName("card")
            box = QVBoxLayout(card)
            widget.setObjectName("metric")
            box.addWidget(widget)
            box.addWidget(QLabel(label))
            cards.addWidget(card, 0, col)
        layout.addLayout(cards)

        quick = QGroupBox("Akış")
        qlayout = QVBoxLayout(quick)
        info = QLabel(
            "1. Ses dosyalarını Yeni Yayın ekranına bırakın.\n"
            "2. Aurora Studio geçerli kalite sürümlerini FFmpeg ile üretir.\n"
            "3. Dosyalar opak adlarla Hugging Face'e yüklenir.\n"
            "4. Katalog GitHub'a commit edilir ve Android uygulaması yenilemede görür."
        )
        info.setObjectName("muted")
        qlayout.addWidget(info)
        buttons = QHBoxLayout()
        new_btn = QPushButton("Yeni Yayın Oluştur")
        new_btn.setObjectName("primaryButton")
        new_btn.clicked.connect(lambda: self.nav.setCurrentRow(1))
        validate_btn = QPushButton("Kataloğu Doğrula")
        validate_btn.clicked.connect(self.validate_catalog_dialog)
        buttons.addWidget(new_btn)
        buttons.addWidget(validate_btn)
        buttons.addStretch()
        qlayout.addLayout(buttons)
        layout.addWidget(quick)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(1000)
        layout.addWidget(QLabel("İşlem Günlüğü"))
        layout.addWidget(self.log, 1)
        return page

    def make_import_page(self) -> QWidget:
        page, layout = self.page_container("Yeni Yayın", "Spotify metadata, sıralı toplu master eşleştirme, ISRC tekrar engeli ve otomatik yayınlama")
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        body = QWidget()
        body_layout = QVBoxLayout(body)

        spotify = QGroupBox("Spotify'dan Otomatik Metadata")
        spotify_form = QFormLayout(spotify)
        self.spotify_url_input = QLineEdit()
        self.spotify_url_input.setPlaceholderText("Spotify albüm veya şarkı bağlantısını yapıştırın")
        self.spotify_auto_lyrics = QCheckBox("Uygun normal/senkronize sözleri otomatik ara")
        self.spotify_auto_lyrics.setChecked(self.settings.spotify_auto_lyrics)
        spotify_btn = QPushButton("Metadata ve Feat Sanatçıları Çek")
        spotify_btn.setObjectName("primaryButton")
        spotify_btn.clicked.connect(self.import_from_spotify)
        spotify_row = QHBoxLayout()
        spotify_row.addWidget(self.spotify_url_input, 1)
        spotify_row.addWidget(spotify_btn)
        spotify_form.addRow("Spotify bağlantısı", spotify_row)
        spotify_form.addRow("", self.spotify_auto_lyrics)
        body_layout.addWidget(spotify)

        metadata = QGroupBox("Yayın Bilgileri")
        form = QFormLayout(metadata)
        self.import_artist = QComboBox()
        self.import_title = QLineEdit()
        self.import_date = QDateEdit(QDate.currentDate())
        self.import_date.setCalendarPopup(True)
        self.import_type = QComboBox()
        self.import_type.addItem("Otomatik", "auto")
        self.import_type.addItem("Single", "single")
        self.import_type.addItem("Maxi Single", "maxi_single")
        self.import_type.addItem("EP", "ep")
        self.import_type.addItem("Albüm", "album")
        self.type_hint = QLabel("Dosya sayısına göre belirlenecek")
        self.type_hint.setObjectName("accentText")
        self.import_label = QLineEdit("Bağımsız")
        self.import_copyright = QLineEdit()
        self.import_description = QTextEdit()
        self.import_description.setFixedHeight(80)
        self.import_featured = QCheckBox("Ana sayfada öne çıkar")
        form.addRow("Sanatçı", self.import_artist)
        form.addRow("Yayın adı", self.import_title)
        form.addRow("Yayın tarihi", self.import_date)
        type_row = QHBoxLayout()
        type_row.addWidget(self.import_type)
        type_row.addWidget(self.type_hint)
        form.addRow("Yayın türü", type_row)
        form.addRow("Label", self.import_label)
        form.addRow("Telif", self.import_copyright)
        form.addRow("Açıklama", self.import_description)
        form.addRow("", self.import_featured)
        body_layout.addWidget(metadata)

        artwork = QGroupBox("Görseller ve Hareketli Kapak")
        aform = QFormLayout(artwork)
        self.cover_url = QLineEdit()
        self.cover_path = QLineEdit()
        self.cover_path.setReadOnly(True)
        cover_btn = QPushButton("Kapak Dosyası Seç")
        cover_btn.clicked.connect(self.pick_cover)
        cover_row = QHBoxLayout()
        cover_row.addWidget(self.cover_path, 1)
        cover_row.addWidget(cover_btn)
        self.hero_url = QLineEdit()
        self.animated_path = QLineEdit()
        self.animated_path.setReadOnly(True)
        animated_btn = QPushButton("MP4/WebM Seç")
        animated_btn.clicked.connect(self.pick_animated_cover)
        animated_row = QHBoxLayout()
        animated_row.addWidget(self.animated_path, 1)
        animated_row.addWidget(animated_btn)
        aform.addRow("Kapak HTTPS URL", self.cover_url)
        aform.addRow("veya yerel kapak", cover_row)
        aform.addRow("Hero görsel URL", self.hero_url)
        aform.addRow("Hareketli kapak", animated_row)
        body_layout.addWidget(artwork)

        tracks_group = QGroupBox("Şarkılar")
        tracks_layout = QVBoxLayout(tracks_group)
        track_buttons = QHBoxLayout()
        add_files = QPushButton("Dosyaları Toplu Seç")
        add_files.setObjectName("primaryButton")
        add_files.clicked.connect(self.add_audio_files)
        add_folder = QPushButton("Klasörü Sıralı Ekle")
        add_folder.clicked.connect(self.add_audio_folder)
        move_up = QPushButton("Yukarı")
        move_up.clicked.connect(lambda: self.move_import_track(-1))
        move_down = QPushButton("Aşağı")
        move_down.clicked.connect(lambda: self.move_import_track(1))
        remove_file = QPushButton("Seçiliyi Kaldır")
        remove_file.clicked.connect(self.remove_import_track)
        details = QPushButton("Detay / Feat / Söz")
        details.clicked.connect(self.edit_import_lyrics)
        atmos = QPushButton("Atmos Master Ekle")
        atmos.clicked.connect(self.add_atmos_file)
        for button in [add_files, add_folder, move_up, move_down, remove_file, details, atmos]:
            track_buttons.addWidget(button)
        track_buttons.addStretch()
        tracks_layout.addLayout(track_buttons)
        self.import_table = QTableWidget(0, 9)
        self.import_table.setHorizontalHeaderLabels(["#", "Master", "Şarkı", "Ana Sanatçı", "Feat", "ISRC", "Durum", "Söz", "Atmos"])
        self.import_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.import_table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.import_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.import_table.itemChanged.connect(self.import_table_changed)
        tracks_layout.addWidget(self.import_table)
        body_layout.addWidget(tracks_group)

        quality = QGroupBox("Kalite Üretimi")
        qgrid = QGridLayout(quality)
        self.q_master = QCheckBox("Orijinal masterı arşivle")
        self.q_standard = QCheckBox("AAC 128 kbps")
        self.q_high = QCheckBox("AAC 256 kbps")
        self.q_lossless = QCheckBox("Lossless FLAC")
        self.q_hires = QCheckBox("Uygunsa Hi-Res FLAC")
        for widget, value in [
            (self.q_master, self.settings.upload_master),
            (self.q_standard, self.settings.make_standard),
            (self.q_high, self.settings.make_high),
            (self.q_lossless, self.settings.make_lossless),
            (self.q_hires, self.settings.make_hires),
        ]:
            widget.setChecked(value)
        qgrid.addWidget(self.q_master, 0, 0)
        qgrid.addWidget(self.q_standard, 0, 1)
        qgrid.addWidget(self.q_high, 0, 2)
        qgrid.addWidget(self.q_lossless, 1, 0)
        qgrid.addWidget(self.q_hires, 1, 1)
        warning = QLabel("MP3/AAC gibi kayıplı kaynaklardan Lossless veya Hi-Res etiketi üretilmez. Stereo dosyadan gerçek Dolby Atmos oluşturulamaz.")
        warning.setWordWrap(True)
        warning.setObjectName("warning")
        qgrid.addWidget(warning, 2, 0, 1, 3)
        body_layout.addWidget(quality)

        publish_box = QFrame()
        publish_box.setObjectName("card")
        publish_layout = QHBoxLayout(publish_box)
        self.publish_progress = QProgressBar()
        self.publish_progress.setRange(0, 100)
        self.publish_status = QLabel("Hazır")
        self.publish_btn = QPushButton("Hugging Face'e Yükle ve GitHub'a Commit Et")
        self.publish_btn.setObjectName("primaryButton")
        self.publish_btn.clicked.connect(self.publish_release)
        publish_layout.addWidget(self.publish_status)
        publish_layout.addWidget(self.publish_progress, 1)
        publish_layout.addWidget(self.publish_btn)
        body_layout.addWidget(publish_box)
        body_layout.addStretch()
        scroll.setWidget(body)
        layout.addWidget(scroll, 1)
        return page

    def make_artists_page(self) -> QWidget:
        page, layout = self.page_container("Sanatçılar", "Profil fotoğrafı, Spotify benzeri arka plan, video ve biyografi yönetimi")
        splitter = QSplitter()
        left = QWidget()
        left_layout = QVBoxLayout(left)
        self.artist_list = QListWidget()
        self.artist_list.currentRowChanged.connect(self.load_artist_form)
        add = QPushButton("Yeni Sanatçı")
        add.setObjectName("primaryButton")
        add.clicked.connect(self.new_artist)
        delete = QPushButton("Sanatçıyı Sil")
        delete.clicked.connect(self.delete_artist)
        left_layout.addWidget(self.artist_list)
        left_layout.addWidget(add)
        left_layout.addWidget(delete)

        right = QWidget()
        form = QFormLayout(right)
        self.artist_name = QLineEdit()
        self.artist_slug = QLineEdit()
        self.artist_image = QLineEdit()
        self.artist_hero = QLineEdit()
        self.artist_background = QLineEdit()
        self.artist_background_video = QLineEdit()
        self.artist_bio = QTextEdit()
        upload_image = QPushButton("Profil Dosyası Yükle")
        upload_image.clicked.connect(lambda: self.upload_asset_to_field(self.artist_image, "artist-profile"))
        upload_background = QPushButton("Arka Plan Dosyası Yükle")
        upload_background.clicked.connect(lambda: self.upload_asset_to_field(self.artist_background, "artist-background"))
        upload_video = QPushButton("Arka Plan Videosu Yükle")
        upload_video.clicked.connect(lambda: self.upload_asset_to_field(self.artist_background_video, "artist-video"))
        image_row = QHBoxLayout(); image_row.addWidget(self.artist_image, 1); image_row.addWidget(upload_image)
        bg_row = QHBoxLayout(); bg_row.addWidget(self.artist_background, 1); bg_row.addWidget(upload_background)
        video_row = QHBoxLayout(); video_row.addWidget(self.artist_background_video, 1); video_row.addWidget(upload_video)
        save = QPushButton("Sanatçı Bilgilerini Kaydet")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_artist)
        form.addRow("Ad", self.artist_name)
        form.addRow("Slug", self.artist_slug)
        form.addRow("Profil görseli", image_row)
        form.addRow("Hero görsel URL", self.artist_hero)
        form.addRow("Arka plan", bg_row)
        form.addRow("Arka plan videosu", video_row)
        form.addRow("Biyografi", self.artist_bio)
        form.addRow("", save)
        splitter.addWidget(left)
        splitter.addWidget(right)
        splitter.setStretchFactor(1, 2)
        layout.addWidget(splitter, 1)
        return page

    def make_releases_page(self) -> QWidget:
        page, layout = self.page_container("Yayınlar", "Single, maxi single, EP ve albüm metadata düzenleyicisi")
        splitter = QSplitter()
        left = QWidget(); l = QVBoxLayout(left)
        self.release_list = QListWidget(); self.release_list.currentRowChanged.connect(self.load_release_form)
        delete = QPushButton("Yayını Sil"); delete.clicked.connect(self.delete_release)
        l.addWidget(self.release_list); l.addWidget(delete)
        right = QWidget(); form = QFormLayout(right)
        self.release_title = QLineEdit()
        self.release_slug = QLineEdit()
        self.release_type = QComboBox()
        for label, value in [("Single", "single"), ("Maxi Single", "maxi_single"), ("EP", "ep"), ("Albüm", "album")]:
            self.release_type.addItem(label, value)
        self.release_artist = QComboBox()
        self.release_date = QDateEdit(); self.release_date.setCalendarPopup(True)
        self.release_cover = QLineEdit()
        self.release_hero = QLineEdit()
        self.release_animated = QLineEdit()
        self.release_label = QLineEdit()
        self.release_copyright = QLineEdit()
        self.release_description = QTextEdit()
        self.release_track_ids = QPlainTextEdit()
        self.release_track_ids.setPlaceholderText("Her satıra bir track_id. Sıra satır sırasıdır.")
        save = QPushButton("Yayın Bilgilerini Kaydet"); save.setObjectName("primaryButton"); save.clicked.connect(self.save_release)
        form.addRow("Başlık", self.release_title)
        form.addRow("Slug", self.release_slug)
        form.addRow("Tür", self.release_type)
        form.addRow("Sanatçı", self.release_artist)
        form.addRow("Tarih", self.release_date)
        form.addRow("Kapak URL", self.release_cover)
        form.addRow("Hero URL", self.release_hero)
        form.addRow("Hareketli kapak URL", self.release_animated)
        form.addRow("Label", self.release_label)
        form.addRow("Telif", self.release_copyright)
        form.addRow("Açıklama", self.release_description)
        form.addRow("Şarkı ID sırası", self.release_track_ids)
        form.addRow("", save)
        splitter.addWidget(left); splitter.addWidget(right); splitter.setStretchFactor(1, 2)
        layout.addWidget(splitter, 1)
        return page

    def make_tracks_page(self) -> QWidget:
        page, layout = self.page_container("Şarkılar", "Görünen ad, normal/senkronize sözler, künye ve ses sürümleri")
        splitter = QSplitter()
        left = QWidget(); l = QVBoxLayout(left)
        self.track_list = QListWidget(); self.track_list.currentRowChanged.connect(self.load_track_form)
        delete = QPushButton("Şarkıyı Sil"); delete.clicked.connect(self.delete_track)
        l.addWidget(self.track_list); l.addWidget(delete)
        right = QWidget(); form = QFormLayout(right)
        self.track_title = QLineEdit()
        self.track_slug = QLineEdit()
        self.track_artists = QLineEdit()
        self.track_featured_artists = QLineEdit()
        self.track_duration = QSpinBox(); self.track_duration.setRange(0, 60 * 60 * 10)
        self.track_isrc = QLineEdit()
        self.track_lyrics = QPlainTextEdit()
        self.track_synced = QPlainTextEdit()
        self.track_credits = QPlainTextEdit()
        self.track_sources = QPlainTextEdit(); self.track_sources.setReadOnly(True)
        save = QPushButton("Şarkı Bilgilerini Kaydet"); save.setObjectName("primaryButton"); save.clicked.connect(self.save_track)
        form.addRow("Başlık", self.track_title)
        form.addRow("Slug", self.track_slug)
        form.addRow("Ana sanatçı ID'leri (virgül)", self.track_artists)
        form.addRow("Feat sanatçı ID'leri (virgül)", self.track_featured_artists)
        form.addRow("Süre (saniye)", self.track_duration)
        form.addRow("ISRC", self.track_isrc)
        form.addRow("Normal sözler", self.track_lyrics)
        form.addRow("Senkronize LRC", self.track_synced)
        form.addRow("Künye JSON", self.track_credits)
        form.addRow("Ses kaynakları", self.track_sources)
        form.addRow("", save)
        splitter.addWidget(left); splitter.addWidget(right); splitter.setStretchFactor(1, 2)
        layout.addWidget(splitter, 1)
        return page

    def make_json_page(self) -> QWidget:
        page, layout = self.page_container("Katalog JSON", "İleri düzey kullanım için katalogun tamamı. Görsel editörle eş zamanlıdır.")
        self.json_editor = QPlainTextEdit()
        apply_btn = QPushButton("JSON'u Uygula")
        apply_btn.setObjectName("primaryButton")
        apply_btn.clicked.connect(self.apply_json)
        layout.addWidget(self.json_editor, 1)
        layout.addWidget(apply_btn)
        return page

    def make_settings_page(self) -> QWidget:
        page, layout = self.page_container("Ayarlar", "GitHub, Hugging Face, yayın sınıflandırması ve kalite kuralları")
        scroll = QScrollArea(); scroll.setWidgetResizable(True)
        body = QWidget(); body_layout = QVBoxLayout(body)

        github = QGroupBox("GitHub Kataloğu")
        gf = QFormLayout(github)
        self.s_github_repo = QLineEdit(self.settings.github_repo)
        self.s_github_branch = QLineEdit(self.settings.github_branch)
        self.s_catalog_path = QLineEdit(self.settings.github_catalog_path)
        self.s_github_token = QLineEdit(self.settings.github_token); self.s_github_token.setEchoMode(QLineEdit.Password)
        test_github = QPushButton("GitHub Bağlantısını Test Et"); test_github.clicked.connect(self.test_github)
        gf.addRow("Repo (owner/name)", self.s_github_repo)
        gf.addRow("Dal", self.s_github_branch)
        gf.addRow("Katalog yolu", self.s_catalog_path)
        gf.addRow("Fine-grained token", self.s_github_token)
        gf.addRow("", test_github)
        body_layout.addWidget(github)

        hf = QGroupBox("Hugging Face Medya Deposu")
        hf_form = QFormLayout(hf)
        self.s_hf_repo = QLineEdit(self.settings.hf_repo)
        self.s_hf_type = QComboBox(); self.s_hf_type.addItem("Dataset", "dataset"); self.s_hf_type.addItem("Model", "model")
        self.s_hf_type.setCurrentIndex(max(0, self.s_hf_type.findData(self.settings.hf_repo_type)))
        self.s_hf_token = QLineEdit(self.settings.hf_token); self.s_hf_token.setEchoMode(QLineEdit.Password)
        test_hf = QPushButton("Hugging Face Bağlantısını Test Et"); test_hf.clicked.connect(self.test_hf)
        hf_form.addRow("Repo (kullanıcı/ad)", self.s_hf_repo)
        hf_form.addRow("Repo türü", self.s_hf_type)
        hf_form.addRow("Write token", self.s_hf_token)
        hf_form.addRow("", test_hf)
        body_layout.addWidget(hf)

        spotify = QGroupBox("Spotify Metadata API")
        sf = QFormLayout(spotify)
        self.s_spotify_client_id = QLineEdit(self.settings.spotify_client_id)
        self.s_spotify_client_secret = QLineEdit(self.settings.spotify_client_secret); self.s_spotify_client_secret.setEchoMode(QLineEdit.Password)
        self.s_spotify_market = QLineEdit(self.settings.spotify_market or "TR")
        self.s_spotify_lyrics = QCheckBox("LRCLIB üzerinden sözleri uygun olduğunda otomatik getir")
        self.s_spotify_lyrics.setChecked(self.settings.spotify_auto_lyrics)
        test_spotify = QPushButton("Spotify Bağlantısını Test Et"); test_spotify.clicked.connect(self.test_spotify)
        sf.addRow("Client ID", self.s_spotify_client_id)
        sf.addRow("Client Secret", self.s_spotify_client_secret)
        sf.addRow("Pazar", self.s_spotify_market)
        sf.addRow("", self.s_spotify_lyrics)
        sf.addRow("", test_spotify)
        body_layout.addWidget(spotify)

        rules = QGroupBox("Otomatik Yayın Türü")
        rg = QGridLayout(rules)
        self.s_single = QSpinBox(); self.s_single.setRange(1, 10); self.s_single.setValue(self.settings.single_max)
        self.s_maxi = QSpinBox(); self.s_maxi.setRange(1, 20); self.s_maxi.setValue(self.settings.maxi_max)
        self.s_ep = QSpinBox(); self.s_ep.setRange(1, 30); self.s_ep.setValue(self.settings.ep_max)
        rg.addWidget(QLabel("Single en fazla"), 0, 0); rg.addWidget(self.s_single, 0, 1)
        rg.addWidget(QLabel("Maxi Single en fazla"), 1, 0); rg.addWidget(self.s_maxi, 1, 1)
        rg.addWidget(QLabel("EP en fazla"), 2, 0); rg.addWidget(self.s_ep, 2, 1)
        rg.addWidget(QLabel("Bunun üzeri Albüm olur. Tür yeni yayın ekranında elle değiştirilebilir."), 3, 0, 1, 2)
        body_layout.addWidget(rules)

        save = QPushButton("Ayarları Güvenli Kaydet")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_settings)
        body_layout.addWidget(save)
        body_layout.addStretch()
        scroll.setWidget(body)
        layout.addWidget(scroll, 1)
        return page

    def apply_style(self) -> None:
        self.setStyleSheet(
            """
            QMainWindow, QWidget { background: #0a0b10; color: #f5f5f7; font-family: 'Segoe UI'; font-size: 13px; }
            #sidebar { background: #11131a; border-right: 1px solid #262936; }
            #logo { background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #bd6bff, stop:1 #4da8ff); border-radius: 14px; font-size: 26px; font-weight: 800; color: white; }
            #brandTitle { font-size: 20px; font-weight: 700; }
            #pageTitle { font-size: 30px; font-weight: 750; }
            #muted { color: #9fa3b1; }
            #accentText { color: #c47aff; font-weight: 650; }
            #warning { color: #ffbd66; background: #2a2114; padding: 9px; border-radius: 8px; }
            #connectionLabel { color: #b7bac5; }
            QListWidget#nav { background: transparent; border: 0; outline: 0; }
            QListWidget#nav::item { padding: 11px 12px; border-radius: 9px; color: #b8bbc7; }
            QListWidget#nav::item:selected { background: #2b1b3c; color: #d397ff; font-weight: 650; }
            QFrame#card, QGroupBox { background: #151821; border: 1px solid #292d3a; border-radius: 14px; }
            QGroupBox { margin-top: 12px; padding: 14px; font-weight: 650; }
            QGroupBox::title { subcontrol-origin: margin; left: 14px; padding: 0 6px; color: #d7a6ff; }
            #metric { font-size: 34px; font-weight: 800; color: #d08bff; }
            QPushButton { background: #232631; border: 1px solid #363a49; border-radius: 9px; padding: 9px 13px; }
            QPushButton:hover { background: #2d3140; }
            QPushButton#primaryButton { background: #a84eff; border-color: #b96cff; color: white; font-weight: 700; }
            QPushButton#primaryButton:hover { background: #ba6bff; }
            QLineEdit, QTextEdit, QPlainTextEdit, QComboBox, QDateEdit, QSpinBox, QTableWidget, QListWidget { background: #101219; border: 1px solid #303442; border-radius: 8px; padding: 7px; selection-background-color: #8e3fca; }
            QTableWidget { gridline-color: #2b2e39; }
            QHeaderView::section { background: #1d202a; color: #c8cad2; padding: 8px; border: 0; border-right: 1px solid #303442; }
            QProgressBar { background: #1b1e27; border: 0; border-radius: 6px; text-align: center; }
            QProgressBar::chunk { background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #a84eff, stop:1 #4da8ff); border-radius: 6px; }
            QScrollBar:vertical { background: transparent; width: 10px; }
            QScrollBar::handle:vertical { background: #383c49; border-radius: 5px; min-height: 24px; }
            """
        )

    def append_log(self, text: str) -> None:
        self.log.appendPlainText(f"[{datetime.now().strftime('%H:%M:%S')}] {text}")
        self.statusBar().showMessage(text, 5000)

    def set_dirty(self, value: bool = True) -> None:
        self.dirty = value
        self.commit_btn.setText("Değişiklikleri Commit Et" + (" •" if value else ""))

    def run_task(self, task: Callable[[Callable[[str, int], None]], Any], on_success: Callable[[Any], None], title: str) -> None:
        if self.active_thread and self.active_thread.isRunning():
            QMessageBox.information(self, APP_NAME, "Başka bir işlem devam ediyor.")
            return
        self.append_log(title)
        self.active_thread = TaskThread(task, self)
        self.active_thread.progress.connect(lambda text, value: (self.publish_status.setText(text), self.publish_progress.setValue(value), self.append_log(text)))
        self.active_thread.succeeded.connect(on_success)
        self.active_thread.failed.connect(self.task_failed)
        self.active_thread.finished.connect(lambda: self.publish_btn.setEnabled(True))
        self.active_thread.start()

    def task_failed(self, message: str) -> None:
        self.publish_btn.setEnabled(True)
        self.publish_status.setText("Hata")
        self.append_log(message.splitlines()[0])
        QMessageBox.critical(self, "İşlem Başarısız", message)

    def load_from_github(self) -> None:
        self.save_settings_from_ui_if_available()
        if not self.settings.github_token:
            self.connection_label.setText("GitHub tokenı Ayarlar ekranında gerekli")
            return

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str]:
            progress("GitHub kataloğu indiriliyor…", 20)
            return GitHubCatalogClient(self.settings).load_catalog()

        def done(result: tuple[dict[str, Any], str]) -> None:
            self.catalog, self.catalog_sha = result
            self.set_dirty(False)
            self.connection_label.setText(f"Bağlı • {self.settings.github_repo} • {self.settings.github_branch}")
            self.publish_progress.setValue(100)
            self.publish_status.setText("Katalog güncel")
            self.refresh_all_views()
            self.append_log("GitHub kataloğu yüklendi.")

        self.run_task(task, done, "GitHub kataloğu yükleniyor")

    def commit_catalog(self) -> None:
        errors = self.validate_catalog()
        if errors:
            QMessageBox.warning(self, "Katalog Doğrulama", "\n".join(errors[:20]))
            return
        if not self.catalog_sha:
            QMessageBox.warning(self, APP_NAME, "Önce GitHub'dan katalog yüklenmelidir.")
            return
        message, ok = self.simple_text_dialog("Commit Mesajı", "Katalog commit mesajı", "Aurora Studio katalog güncellemesi")
        if not ok:
            return

        def task(progress: Callable[[str, int], None]) -> str:
            progress("Katalog GitHub'a commit ediliyor…", 50)
            return GitHubCatalogClient(self.settings).commit_catalog(self.catalog, self.catalog_sha, message)

        def done(new_sha: str) -> None:
            self.catalog_sha = new_sha
            self.set_dirty(False)
            self.publish_progress.setValue(100)
            self.publish_status.setText("Commit tamamlandı")
            self.append_log("Katalog GitHub'a commit edildi. Android uygulamasında yenileme ile görünür.")
            QMessageBox.information(self, APP_NAME, "Katalog başarıyla GitHub'a commit edildi.")

        self.run_task(task, done, "Katalog commit işlemi başladı")

    def refresh_all_views(self) -> None:
        artists = self.catalog.get("artists", [])
        releases = self.catalog.get("releases", [])
        tracks = self.catalog.get("tracks", [])
        self.artist_count.setText(str(len(artists)))
        self.release_count.setText(str(len(releases)))
        self.track_count.setText(str(len(tracks)))
        self.source_count.setText(str(sum(len(t.get("sources", [])) for t in tracks)))

        self.artist_list.blockSignals(True)
        self.artist_list.clear()
        for artist in artists:
            item = QListWidgetItem(artist.get("name", "Adsız")); item.setData(Qt.UserRole, artist.get("id")); self.artist_list.addItem(item)
        self.artist_list.blockSignals(False)

        self.release_list.blockSignals(True)
        self.release_list.clear()
        for release in releases:
            item = QListWidgetItem(f"{release.get('title', 'Adsız')}  •  {self.release_label_name(release.get('type', 'album'))}")
            item.setData(Qt.UserRole, release.get("id")); self.release_list.addItem(item)
        self.release_list.blockSignals(False)

        self.track_list.blockSignals(True)
        self.track_list.clear()
        for track in tracks:
            item = QListWidgetItem(track.get("title", "Adsız")); item.setData(Qt.UserRole, track.get("id")); self.track_list.addItem(item)
        self.track_list.blockSignals(False)

        for combo in [self.import_artist, self.release_artist]:
            current = combo.currentData()
            combo.blockSignals(True); combo.clear()
            for artist in artists:
                combo.addItem(artist.get("name", "Adsız"), artist.get("id"))
            if current:
                combo.setCurrentIndex(max(0, combo.findData(current)))
            combo.blockSignals(False)
        self.json_editor.setPlainText(json.dumps(self.catalog, ensure_ascii=False, indent=2))

    @staticmethod
    def release_label_name(value: str) -> str:
        return {"single": "Single", "maxi_single": "Maxi Single", "ep": "EP", "album": "Albüm"}.get(value, value)

    def validate_catalog(self) -> list[str]:
        errors: list[str] = []
        artists = self.catalog.get("artists", [])
        tracks = self.catalog.get("tracks", [])
        releases = self.catalog.get("releases", [])
        artist_ids = [row.get("id") for row in artists]
        track_ids = [row.get("id") for row in tracks]
        release_ids = [row.get("id") for row in releases]
        for name, ids in [("Sanatçı", artist_ids), ("Şarkı", track_ids), ("Yayın", release_ids)]:
            duplicates = {x for x in ids if x and ids.count(x) > 1}
            if duplicates:
                errors.append(f"{name} ID tekrarları: {', '.join(duplicates)}")
        isrc_rows: dict[str, list[str]] = {}
        for row in tracks:
            normalized = normalize_isrc(row.get("isrc", ""))
            if normalized:
                isrc_rows.setdefault(normalized, []).append(row.get("title", row.get("id", "")))
        for normalized, names in isrc_rows.items():
            if len(names) > 1:
                errors.append(f"Aynı ISRC birden fazla şarkıda kayıtlı ({normalized}): {', '.join(names)}")
        for track in tracks:
            if not track.get("title"):
                errors.append(f"Başlıksız şarkı: {track.get('id')}")
            for artist_id in track.get("artistIds", []):
                if artist_id not in artist_ids:
                    errors.append(f"{track.get('title')} bilinmeyen sanatçıya bağlı: {artist_id}")
            if not track.get("sources"):
                errors.append(f"{track.get('title')} için ses kaynağı yok.")
        for release in releases:
            for artist_id in release.get("artistIds", []):
                if artist_id not in artist_ids:
                    errors.append(f"{release.get('title')} bilinmeyen sanatçıya bağlı: {artist_id}")
            for row in release.get("tracks", []):
                if row.get("trackId") not in track_ids:
                    errors.append(f"{release.get('title')} bilinmeyen şarkı içeriyor: {row.get('trackId')}")
        for release_id in self.catalog.get("featuredReleaseIds", []):
            if release_id not in release_ids:
                errors.append(f"Öne çıkan yayın bulunamadı: {release_id}")
        return errors

    def validate_catalog_dialog(self) -> None:
        errors = self.validate_catalog()
        QMessageBox.information(self, "Katalog Doğrulama", "Katalog geçerli." if not errors else "\n".join(errors[:30]))

    def new_artist(self) -> None:
        artist = {
            "id": opaque_id("artist"),
            "slug": "yeni-sanatci",
            "name": "Yeni Sanatçı",
            "image": "",
            "heroImage": "",
            "backgroundImage": "",
            "backgroundVideoUrl": "",
            "bio": "",
        }
        self.catalog.setdefault("artists", []).append(artist)
        self.set_dirty()
        self.refresh_all_views()
        self.artist_list.setCurrentRow(self.artist_list.count() - 1)

    def current_id(self, widget: QListWidget) -> str | None:
        item = widget.currentItem()
        return item.data(Qt.UserRole) if item else None

    def find_by_id(self, collection: str, item_id: str | None) -> dict[str, Any] | None:
        return next((row for row in self.catalog.get(collection, []) if row.get("id") == item_id), None)

    def load_artist_form(self) -> None:
        artist = self.find_by_id("artists", self.current_id(self.artist_list))
        if not artist:
            return
        self.artist_name.setText(artist.get("name", ""))
        self.artist_slug.setText(artist.get("slug", ""))
        self.artist_image.setText(artist.get("image", ""))
        self.artist_hero.setText(artist.get("heroImage", ""))
        self.artist_background.setText(artist.get("backgroundImage", ""))
        self.artist_background_video.setText(artist.get("backgroundVideoUrl", ""))
        self.artist_bio.setPlainText(artist.get("bio", ""))

    def save_artist(self) -> None:
        artist = self.find_by_id("artists", self.current_id(self.artist_list))
        if not artist:
            return
        if not self.artist_name.text().strip():
            QMessageBox.warning(self, APP_NAME, "Sanatçı adı gerekli.")
            return
        artist.update(
            {
                "name": self.artist_name.text().strip(),
                "slug": self.artist_slug.text().strip() or slugify(self.artist_name.text()),
                "image": self.artist_image.text().strip(),
                "heroImage": self.artist_hero.text().strip() or self.artist_background.text().strip(),
                "backgroundImage": self.artist_background.text().strip() or self.artist_hero.text().strip(),
                "backgroundVideoUrl": self.artist_background_video.text().strip(),
                "bio": self.artist_bio.toPlainText(),
            }
        )
        self.set_dirty(); self.refresh_all_views(); self.append_log(f"Sanatçı güncellendi: {artist['name']}")

    def delete_artist(self) -> None:
        artist_id = self.current_id(self.artist_list)
        artist = self.find_by_id("artists", artist_id)
        if not artist:
            return
        used = [r.get("title") for r in self.catalog.get("releases", []) if artist_id in r.get("artistIds", [])]
        if used:
            QMessageBox.warning(self, APP_NAME, "Bu sanatçı yayınlarda kullanılıyor:\n" + "\n".join(used))
            return
        if QMessageBox.question(self, APP_NAME, f"{artist.get('name')} silinsin mi?") == QMessageBox.Yes:
            self.catalog["artists"] = [a for a in self.catalog.get("artists", []) if a.get("id") != artist_id]
            self.set_dirty(); self.refresh_all_views()

    def load_release_form(self) -> None:
        release = self.find_by_id("releases", self.current_id(self.release_list))
        if not release:
            return
        self.release_title.setText(release.get("title", ""))
        self.release_slug.setText(release.get("slug", ""))
        self.release_type.setCurrentIndex(max(0, self.release_type.findData(release.get("type", "album"))))
        self.release_artist.setCurrentIndex(max(0, self.release_artist.findData((release.get("artistIds") or [""])[0])))
        self.release_date.setDate(QDate.fromString(release.get("releaseDate", ""), "yyyy-MM-dd") or QDate.currentDate())
        self.release_cover.setText(release.get("cover", ""))
        self.release_hero.setText(release.get("heroImage", ""))
        self.release_animated.setText(release.get("animatedCoverUrl", ""))
        self.release_label.setText(release.get("label", ""))
        self.release_copyright.setText(release.get("copyright", ""))
        self.release_description.setPlainText(release.get("description", ""))
        self.release_track_ids.setPlainText("\n".join(row.get("trackId", "") for row in release.get("tracks", [])))

    def save_release(self) -> None:
        release = self.find_by_id("releases", self.current_id(self.release_list))
        if not release:
            return
        track_ids = [line.strip() for line in self.release_track_ids.toPlainText().splitlines() if line.strip()]
        known = {t.get("id") for t in self.catalog.get("tracks", [])}
        missing = [tid for tid in track_ids if tid not in known]
        if missing:
            QMessageBox.warning(self, APP_NAME, "Bilinmeyen şarkı ID'leri:\n" + "\n".join(missing))
            return
        release.update(
            {
                "title": self.release_title.text().strip(),
                "slug": self.release_slug.text().strip() or slugify(self.release_title.text()),
                "type": self.release_type.currentData(),
                "artistIds": [self.release_artist.currentData()] if self.release_artist.currentData() else [],
                "releaseDate": self.release_date.date().toString("yyyy-MM-dd"),
                "cover": self.release_cover.text().strip(),
                "heroImage": self.release_hero.text().strip() or self.release_cover.text().strip(),
                "animatedCoverUrl": self.release_animated.text().strip(),
                "label": self.release_label.text().strip(),
                "copyright": self.release_copyright.text().strip(),
                "description": self.release_description.toPlainText(),
                "tracks": [{"trackId": tid, "disc": 1, "position": idx + 1} for idx, tid in enumerate(track_ids)],
            }
        )
        self.set_dirty(); self.refresh_all_views()

    def delete_release(self) -> None:
        release_id = self.current_id(self.release_list)
        release = self.find_by_id("releases", release_id)
        if not release:
            return
        if QMessageBox.question(self, APP_NAME, f"{release.get('title')} yayını silinsin mi? Şarkı kayıtları korunur.") == QMessageBox.Yes:
            self.catalog["releases"] = [r for r in self.catalog.get("releases", []) if r.get("id") != release_id]
            self.catalog["featuredReleaseIds"] = [x for x in self.catalog.get("featuredReleaseIds", []) if x != release_id]
            self.set_dirty(); self.refresh_all_views()

    def load_track_form(self) -> None:
        track = self.find_by_id("tracks", self.current_id(self.track_list))
        if not track:
            return
        self.track_title.setText(track.get("title", ""))
        self.track_slug.setText(track.get("slug", ""))
        primary_ids = track.get("primaryArtistIds") or track.get("artistIds", [])
        featured_ids = track.get("featuredArtistIds", [])
        self.track_artists.setText(", ".join(primary_ids))
        self.track_featured_artists.setText(", ".join(featured_ids))
        self.track_duration.setValue(int(track.get("durationSeconds", 0)))
        self.track_isrc.setText(track.get("isrc", ""))
        self.track_lyrics.setPlainText(track.get("lyrics", ""))
        self.track_synced.setPlainText(track.get("syncedLyrics", ""))
        self.track_credits.setPlainText(json.dumps(track.get("credits", []), ensure_ascii=False, indent=2))
        self.track_sources.setPlainText(json.dumps(track.get("sources", []), ensure_ascii=False, indent=2))

    def save_track(self) -> None:
        track = self.find_by_id("tracks", self.current_id(self.track_list))
        if not track:
            return
        try:
            credits = json.loads(self.track_credits.toPlainText().strip() or "[]")
        except Exception as exc:
            QMessageBox.warning(self, APP_NAME, f"Künye JSON hatası: {exc}")
            return
        track.update(
            {
                "title": self.track_title.text().strip(),
                "slug": self.track_slug.text().strip() or slugify(self.track_title.text()),
                "primaryArtistIds": ordered_unique([x.strip() for x in self.track_artists.text().split(",") if x.strip()]),
                "featuredArtistIds": [x for x in ordered_unique([x.strip() for x in self.track_featured_artists.text().split(",") if x.strip()]) if x not in [y.strip() for y in self.track_artists.text().split(",") if y.strip()]],
                "artistIds": ordered_unique([x.strip() for x in self.track_artists.text().split(",") if x.strip()] + [x.strip() for x in self.track_featured_artists.text().split(",") if x.strip()]),
                "durationSeconds": self.track_duration.value(),
                "isrc": self.track_isrc.text().strip(),
                "lyrics": self.track_lyrics.toPlainText(),
                "syncedLyrics": self.track_synced.toPlainText(),
                "credits": credits,
            }
        )
        self.set_dirty(); self.refresh_all_views()

    def delete_track(self) -> None:
        track_id = self.current_id(self.track_list)
        track = self.find_by_id("tracks", track_id)
        if not track:
            return
        used = [r.get("title") for r in self.catalog.get("releases", []) if any(t.get("trackId") == track_id for t in r.get("tracks", []))]
        if used:
            QMessageBox.warning(self, APP_NAME, "Şarkı şu yayınlarda kullanılıyor:\n" + "\n".join(used))
            return
        if QMessageBox.question(self, APP_NAME, f"{track.get('title')} şarkısı katalogdan silinsin mi? Hugging Face dosyaları otomatik silinmez.") == QMessageBox.Yes:
            self.catalog["tracks"] = [t for t in self.catalog.get("tracks", []) if t.get("id") != track_id]
            self.set_dirty(); self.refresh_all_views()

    def apply_json(self) -> None:
        try:
            value = json.loads(self.json_editor.toPlainText())
            if not isinstance(value, dict):
                raise ValueError("Katalog JSON nesnesi olmalıdır.")
            self.catalog = value
            errors = self.validate_catalog()
            if errors:
                raise ValueError("\n".join(errors[:20]))
            self.set_dirty(); self.refresh_all_views()
            QMessageBox.information(self, APP_NAME, "JSON katalog görsel editöre uygulandı.")
        except Exception as exc:
            QMessageBox.warning(self, "JSON Hatası", str(exc))

    def artist_name_for_id(self, artist_id: str) -> str:
        artist = self.find_by_id("artists", artist_id)
        return artist.get("name", artist_id) if artist else artist_id

    def ensure_spotify_artist(self, data: dict[str, Any]) -> str:
        spotify_id = data.get("spotifyId", "")
        name = data.get("name", "").strip() or "Bilinmeyen Sanatçı"
        existing = next(
            (
                artist
                for artist in self.catalog.get("artists", [])
                if (spotify_id and artist.get("spotifyId") == spotify_id)
                or artist.get("name", "").casefold() == name.casefold()
            ),
            None,
        )
        if existing:
            if spotify_id and not existing.get("spotifyId"):
                existing["spotifyId"] = spotify_id
            if data.get("url") and not existing.get("spotifyUrl"):
                existing["spotifyUrl"] = data["url"]
            if data.get("image") and not existing.get("image"):
                existing["image"] = data["image"]
                existing.setdefault("heroImage", data["image"])
                existing.setdefault("backgroundImage", "")
            return existing["id"]
        artist = {
            "id": opaque_id("artist"),
            "slug": slugify(name),
            "name": name,
            "image": data.get("image", ""),
            "heroImage": data.get("image", ""),
            "backgroundImage": "",
            "backgroundVideoUrl": "",
            "bio": "",
            "spotifyId": spotify_id,
            "spotifyUrl": data.get("url", ""),
        }
        self.catalog.setdefault("artists", []).append(artist)
        return artist["id"]

    def import_from_spotify(self) -> None:
        value = self.spotify_url_input.text().strip()
        if not value:
            QMessageBox.warning(self, APP_NAME, "Spotify albüm veya şarkı bağlantısı gerekli.")
            return
        self.save_settings_from_ui_if_available()
        settings = copy.deepcopy(self.settings)
        include_lyrics = self.spotify_auto_lyrics.isChecked()
        self.publish_btn.setEnabled(False)

        def task(progress: Callable[[str, int], None]) -> dict[str, Any]:
            progress("Spotify erişim anahtarı alınıyor…", 10)
            client = SpotifyMetadataClient(settings)
            progress("Albüm ve şarkı metadata bilgileri çekiliyor…", 35)
            result = client.fetch_release(value, include_lyrics=include_lyrics)
            progress("Metadata hazır", 100)
            return result

        def done(data: dict[str, Any]) -> None:
            release_artist_ids = [self.ensure_spotify_artist(row) for row in data.get("artists", [])]
            imported: list[ImportTrack] = []
            for index, row in enumerate(data.get("tracks", [])):
                primary_ids: list[str] = []
                featured_ids: list[str] = []
                for artist_index, artist in enumerate(row.get("artists", [])):
                    artist_id = self.ensure_spotify_artist(artist)
                    if artist.get("isAlbumArtist") or (artist_index == 0 and not primary_ids):
                        primary_ids.append(artist_id)
                    else:
                        featured_ids.append(artist_id)
                primary_ids = ordered_unique(primary_ids or release_artist_ids[:1])
                featured_ids = [value for value in ordered_unique(featured_ids) if value not in primary_ids]
                imported.append(
                    ImportTrack(
                        path=Path("__AURORA_MASTER_SECILMEDI__"),
                        title=row.get("title", f"Şarkı {index + 1}"),
                        isrc=row.get("isrc", ""),
                        primary_artist_ids=primary_ids,
                        featured_artist_ids=featured_ids,
                        spotify_id=row.get("spotifyId", ""),
                        spotify_url=row.get("url", ""),
                        duration_seconds=int(row.get("durationSeconds", 0)),
                        disc=int(row.get("disc", 1)),
                        position=int(row.get("position", index + 1)),
                        explicit=bool(row.get("explicit", False)),
                        lyrics=row.get("lyrics", ""),
                        synced_lyrics=row.get("syncedLyrics", ""),
                    )
                )
            self.import_tracks = imported
            self.import_release_artist_ids = ordered_unique(release_artist_ids)
            self.import_spotify_id = data.get("spotifyId", "")
            self.import_spotify_url = data.get("url", "")
            self.import_title.setText(data.get("title", ""))
            release_date = data.get("releaseDate", "")
            if release_date:
                parsed = QDate.fromString(release_date[:10], "yyyy-MM-dd")
                if not parsed.isValid():
                    parsed = QDate.fromString(release_date[:7] + "-01", "yyyy-MM-dd")
                if not parsed.isValid():
                    parsed = QDate.fromString(release_date[:4] + "-01-01", "yyyy-MM-dd")
                if parsed.isValid():
                    self.import_date.setDate(parsed)
            self.import_label.setText(data.get("label", ""))
            self.import_copyright.setText(data.get("copyright", ""))
            self.cover_url.setText(data.get("cover", ""))
            self.hero_url.setText(data.get("cover", ""))
            self.set_dirty()
            self.refresh_all_views()
            if self.import_release_artist_ids:
                combo_index = self.import_artist.findData(self.import_release_artist_ids[0])
                if combo_index >= 0:
                    self.import_artist.setCurrentIndex(combo_index)
            self.refresh_import_table()
            self.publish_status.setText("Spotify metadata hazır; master dosyalarını sıralı seçin")
            self.append_log(f"Spotify'dan {len(imported)} şarkı ve sanatçı rolleri alındı.")
            QMessageBox.information(
                self,
                APP_NAME,
                f"{len(imported)} şarkı içe aktarıldı. Şimdi 'Dosyaları Toplu Seç' veya 'Klasörü Sıralı Ekle' ile masterları sırayla eşleştirin.",
            )

        self.run_task(task, done, "Spotify metadata içe aktarma başladı")

    def assign_audio_paths(self, paths: list[Path]) -> None:
        paths = sorted([path for path in paths if path.is_file()], key=lambda path: natural_sort_key(path.name))
        if not paths:
            return
        missing_rows = [row for row in self.import_tracks if not row.has_master]
        if self.import_tracks and missing_rows:
            if len(paths) != len(missing_rows):
                answer = QMessageBox.question(
                    self,
                    APP_NAME,
                    f"Spotify sırası için {len(missing_rows)} master bekleniyor, {len(paths)} dosya seçildi. İlk {min(len(paths), len(missing_rows))} dosya sırayla eşleştirilsin mi?",
                )
                if answer != QMessageBox.Yes:
                    return
            for row, path in zip(missing_rows, paths):
                row.path = path
            extras = paths[len(missing_rows):]
            default_artists = self.import_release_artist_ids or ([self.import_artist.currentData()] if self.import_artist.currentData() else [])
            for path in extras:
                self.import_tracks.append(ImportTrack(path=path, title=path.stem, primary_artist_ids=default_artists))
        else:
            default_artists = self.import_release_artist_ids or ([self.import_artist.currentData()] if self.import_artist.currentData() else [])
            for path in paths:
                self.import_tracks.append(ImportTrack(path=path, title=path.stem, primary_artist_ids=default_artists))
        self.refresh_import_table()

    def add_audio_files(self) -> None:
        files, _ = QFileDialog.getOpenFileNames(self, "Master Ses Dosyalarını Sıralı Seç", "", "Ses Dosyaları (*.flac *.wav *.m4a *.mp3 *.aiff *.alac)")
        self.assign_audio_paths([Path(name) for name in files])

    def add_audio_folder(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "Master Dosyalarının Bulunduğu Klasörü Seç")
        if not folder:
            return
        extensions = {".flac", ".wav", ".m4a", ".mp3", ".aiff", ".alac"}
        paths = [path for path in Path(folder).iterdir() if path.is_file() and path.suffix.lower() in extensions]
        self.assign_audio_paths(paths)

    def move_import_track(self, direction: int) -> None:
        row = self.import_table.currentRow()
        target = row + direction
        if not (0 <= row < len(self.import_tracks) and 0 <= target < len(self.import_tracks)):
            return
        self.import_tracks[row], self.import_tracks[target] = self.import_tracks[target], self.import_tracks[row]
        self.refresh_import_table()
        self.import_table.selectRow(target)

    def refresh_import_table(self) -> None:
        existing_isrc = {normalize_isrc(row.get("isrc", "")) for row in self.catalog.get("tracks", []) if normalize_isrc(row.get("isrc", ""))}
        self.import_table.blockSignals(True)
        self.import_table.setRowCount(len(self.import_tracks))
        for row, track in enumerate(self.import_tracks):
            normalized = normalize_isrc(track.isrc)
            if normalized and normalized in existing_isrc:
                status = "Katalogda var • yeniden yüklenmez"
            elif track.has_master:
                status = "Master hazır"
            else:
                status = "Master gerekli"
            values = [
                str(row + 1),
                track.path.name if track.has_master else "—",
                track.title,
                ", ".join(self.artist_name_for_id(value) for value in track.primary_artist_ids) or "—",
                ", ".join(self.artist_name_for_id(value) for value in track.featured_artist_ids) or "—",
                track.isrc,
                status,
                "Var" if track.lyrics or track.synced_lyrics else "—",
                track.atmos_path.name if track.atmos_path else "—",
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                if column not in {2, 5}:
                    item.setFlags(item.flags() & ~Qt.ItemIsEditable)
                self.import_table.setItem(row, column, item)
        self.import_table.blockSignals(False)
        suggested = self.settings.classify_release(len(self.import_tracks)) if self.import_tracks else "—"
        self.type_hint.setText(f"Öneri: {self.release_label_name(suggested)}")

    def import_table_changed(self, item: QTableWidgetItem) -> None:
        if item.row() >= len(self.import_tracks):
            return
        track = self.import_tracks[item.row()]
        if item.column() == 2:
            track.title = item.text().strip()
        elif item.column() == 5:
            track.isrc = item.text().strip()
        self.refresh_import_table()

    def remove_import_track(self) -> None:
        row = self.import_table.currentRow()
        if 0 <= row < len(self.import_tracks):
            self.import_tracks.pop(row)
            self.refresh_import_table()

    def edit_import_lyrics(self) -> None:
        row = self.import_table.currentRow()
        if 0 <= row < len(self.import_tracks):
            if LyricsDialog(self.import_tracks[row], self).exec() == QDialog.Accepted:
                self.refresh_import_table()

    def add_atmos_file(self) -> None:
        row = self.import_table.currentRow()
        if not (0 <= row < len(self.import_tracks)):
            QMessageBox.information(self, APP_NAME, "Önce bir şarkı satırı seçin.")
            return
        file, _ = QFileDialog.getOpenFileName(self, "Dolby Atmos Master Seç", "", "Atmos / Çok Kanallı Ses (*.ec3 *.eac3 *.m4a *.mp4 *.wav)")
        if file:
            self.import_tracks[row].atmos_path = Path(file)
            self.refresh_import_table()

    def pick_cover(self) -> None:
        file, _ = QFileDialog.getOpenFileName(self, "Kapak Seç", "", "Görseller (*.jpg *.jpeg *.png *.webp)")
        if file:
            self.cover_path.setText(file)

    def pick_animated_cover(self) -> None:
        file, _ = QFileDialog.getOpenFileName(self, "Hareketli Kapak Seç", "", "Videolar (*.mp4 *.webm *.mov)")
        if file:
            self.animated_path.setText(file)

    def build_import_request(self) -> ImportRequest:
        selected_artist = self.import_artist.currentData()
        release_artist_ids = ordered_unique(self.import_release_artist_ids or ([selected_artist] if selected_artist else []))
        if not release_artist_ids:
            raise ValueError("Önce bir sanatçı oluşturun veya Spotify metadata içe aktarın.")
        if not self.import_title.text().strip():
            raise ValueError("Yayın adı gerekli.")
        if not self.import_tracks:
            raise ValueError("En az bir şarkı ekleyin.")
        existing_isrc = {normalize_isrc(row.get("isrc", "")) for row in self.catalog.get("tracks", []) if normalize_isrc(row.get("isrc", ""))}
        missing = [row.title for row in self.import_tracks if not row.has_master and normalize_isrc(row.isrc) not in existing_isrc]
        if missing:
            raise ValueError("Bu yeni şarkılar için master dosyası gerekli\n" + "\n".join(missing[:20]))
        for row in self.import_tracks:
            if not row.primary_artist_ids:
                row.primary_artist_ids = release_artist_ids.copy()
        selected = self.import_type.currentData()
        release_type = self.settings.classify_release(len(self.import_tracks)) if selected == "auto" else selected
        return ImportRequest(
            artist_id=release_artist_ids[0],
            release_artist_ids=release_artist_ids,
            release_title=self.import_title.text().strip(),
            release_type=release_type,
            release_date=self.import_date.date().toString("yyyy-MM-dd"),
            label=self.import_label.text().strip(),
            copyright_text=self.import_copyright.text().strip(),
            description=self.import_description.toPlainText(),
            cover_url=self.cover_url.text().strip(),
            cover_path=Path(self.cover_path.text()) if self.cover_path.text() else None,
            hero_url=self.hero_url.text().strip(),
            animated_cover_path=Path(self.animated_path.text()) if self.animated_path.text() else None,
            tracks=copy.deepcopy(self.import_tracks),
            featured=self.import_featured.isChecked(),
            spotify_id=self.import_spotify_id,
            spotify_url=self.import_spotify_url,
        )

    def publish_release(self) -> None:
        try:
            request = self.build_import_request()
            if not self.catalog_sha:
                raise ValueError("Önce GitHub'dan katalog yükleyin.")
            self.save_settings_from_quality()
            self.settings.spotify_auto_lyrics = self.spotify_auto_lyrics.isChecked()
            self.settings.save()
        except Exception as exc:
            QMessageBox.warning(self, APP_NAME, str(exc))
            return
        self.publish_btn.setEnabled(False)
        snapshot = copy.deepcopy(self.catalog)
        sha = self.catalog_sha
        settings = copy.deepcopy(self.settings)

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str, int, int]:
            processor = MediaProcessor()
            storage = HuggingFaceStorage(settings)
            github = GitHubCatalogClient(settings)
            release_id = opaque_id("release")
            temp_root = Path(tempfile.mkdtemp(prefix="aurora-studio-"))
            upload_files: list[tuple[Path, str]] = []
            new_tracks: list[dict[str, Any]] = []
            release_rows: list[dict[str, Any]] = []
            reused_count = 0
            snapshot["schemaVersion"] = max(3, int(snapshot.get("schemaVersion", 1)))
            existing_by_isrc = {
                normalize_isrc(row.get("isrc", "")): row
                for row in snapshot.get("tracks", [])
                if normalize_isrc(row.get("isrc", ""))
            }
            try:
                cover_url = request.cover_url
                if request.cover_path:
                    ext = request.cover_path.suffix.lower() or ".jpg"
                    remote = storage.allocate_remote("artwork", ext, lambda status: progress(status, 4))
                    upload_files.append((request.cover_path, remote))
                    cover_url = storage.resolve_url(remote)
                animated_url = ""
                if request.animated_cover_path:
                    ext = request.animated_cover_path.suffix.lower() or ".mp4"
                    remote = storage.allocate_remote("animated", ext, lambda status: progress(status, 4))
                    upload_files.append((request.animated_cover_path, remote))
                    animated_url = storage.resolve_url(remote)
                if not cover_url:
                    raise RuntimeError("Kapak URL'si veya kapak dosyası gerekli.")

                total = len(request.tracks)
                for index, row in enumerate(request.tracks):
                    base = 5 + int((index / max(total, 1)) * 65)
                    normalized = normalize_isrc(row.isrc)
                    existing = existing_by_isrc.get(normalized) if normalized else None
                    if existing:
                        reused_count += 1
                        progress(f"{row.title}: aynı ISRC bulundu, mevcut kayıt kullanılıyor", base)
                        release_rows.append({
                            "trackId": existing["id"],
                            "disc": row.disc or 1,
                            "position": row.position or index + 1,
                        })
                        continue
                    if not row.has_master:
                        raise RuntimeError(f"{row.title} için master dosyası yok.")
                    progress(f"{row.title}: ses analiz ediliyor", base)
                    track_id = opaque_id("track")
                    media_folder = temp_root / track_id
                    info, variants = processor.process(row.path, media_folder, settings)
                    sources: list[dict[str, Any]] = []
                    if settings.upload_master:
                        master_remote = storage.allocate_remote("masters", row.path.suffix.lower(), lambda status: progress(status, base))
                        upload_files.append((row.path, master_remote))
                    for variant in variants:
                        ext = variant["path"].suffix.lower()
                        remote = storage.allocate_remote("audio", ext, lambda status: progress(status, base))
                        upload_files.append((variant["path"], remote))
                        url = storage.resolve_url(remote)
                        source = {
                            "id": opaque_id("audio"),
                            "kind": variant["kind"],
                            "label": variant["label"],
                            "codec": variant["codec"],
                            "url": url,
                            "downloadUrl": url,
                            "downloadable": True,
                            "channels": f"{info['channels']} kanal" if info["channels"] else "Stereo",
                            "spatial": False,
                        }
                        for key in ["bitrateKbps", "sampleRateKhz", "bitDepth"]:
                            if key in variant:
                                source[key] = variant[key]
                        sources.append(source)
                    if row.atmos_path:
                        remote = storage.allocate_remote("atmos", row.atmos_path.suffix.lower(), lambda status: progress(status, base))
                        upload_files.append((row.atmos_path, remote))
                        url = storage.resolve_url(remote)
                        sources.append({
                            "id": opaque_id("audio"),
                            "kind": "dolby_atmos",
                            "label": "Dolby Atmos",
                            "codec": "Dolby Atmos / E-AC-3 JOC",
                            "url": url,
                            "downloadUrl": url,
                            "downloadable": True,
                            "channels": "Çok kanallı",
                            "spatial": True,
                        })
                    primary_ids = ordered_unique(row.primary_artist_ids or request.release_artist_ids)
                    featured_ids = [value for value in ordered_unique(row.featured_artist_ids) if value not in primary_ids]
                    track_data = {
                        "id": track_id,
                        "slug": slugify(row.title),
                        "title": row.title,
                        "artistIds": ordered_unique(primary_ids + featured_ids),
                        "primaryArtistIds": primary_ids,
                        "featuredArtistIds": featured_ids,
                        "durationSeconds": info["duration"] or row.duration_seconds,
                        "isrc": row.isrc,
                        "explicit": row.explicit,
                        "spotifyId": row.spotify_id,
                        "spotifyUrl": row.spotify_url,
                        "lyrics": row.lyrics,
                        "syncedLyrics": row.synced_lyrics,
                        "credits": row.credits,
                        "sources": sources,
                    }
                    new_tracks.append(track_data)
                    if normalized:
                        existing_by_isrc[normalized] = track_data
                    release_rows.append({
                        "trackId": track_id,
                        "disc": row.disc or 1,
                        "position": row.position or index + 1,
                    })

                if upload_files:
                    progress("Yeni dosyalar Hugging Face'e sıralı toplu commit ediliyor…", 72)
                    storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status, percent: progress(status, 72 + int(percent * 0.18)))
                else:
                    progress("Tüm şarkılar mevcut ISRC kayıtlarından kullanıldı; medya yüklenmedi", 72)
                release = {
                    "id": release_id,
                    "slug": slugify(request.release_title),
                    "title": request.release_title,
                    "type": request.release_type,
                    "artistIds": request.release_artist_ids,
                    "primaryArtistIds": request.release_artist_ids,
                    "releaseDate": request.release_date,
                    "cover": cover_url,
                    "heroImage": request.hero_url or cover_url,
                    "animatedCoverUrl": animated_url,
                    "label": request.label,
                    "copyright": request.copyright_text,
                    "description": request.description,
                    "spotifyId": request.spotify_id,
                    "spotifyUrl": request.spotify_url,
                    "tracks": sorted(release_rows, key=lambda item: (item.get("disc", 1), item.get("position", 1))),
                }
                snapshot.setdefault("tracks", []).extend(new_tracks)
                snapshot.setdefault("releases", []).append(release)
                if request.featured:
                    snapshot.setdefault("featuredReleaseIds", []).insert(0, release_id)
                progress("Katalog GitHub'a commit ediliyor…", 92)
                new_sha = github.commit_catalog(snapshot, sha, f"Aurora Music: {request.release_title} yayınını ekle")
                progress("Yayın tamamlandı", 100)
                return snapshot, new_sha, reused_count, len(new_tracks)
            finally:
                shutil.rmtree(temp_root, ignore_errors=True)

        def done(result: tuple[dict[str, Any], str, int, int]) -> None:
            self.catalog, self.catalog_sha, reused_count, new_count = result
            self.set_dirty(False)
            self.publish_btn.setEnabled(True)
            self.publish_progress.setValue(100)
            self.publish_status.setText("Yayın başarıyla eklendi")
            self.import_tracks.clear()
            self.import_release_artist_ids.clear()
            self.import_spotify_id = ""
            self.import_spotify_url = ""
            self.refresh_import_table()
            self.import_title.clear(); self.cover_path.clear(); self.cover_url.clear(); self.hero_url.clear(); self.animated_path.clear(); self.spotify_url_input.clear()
            self.refresh_all_views()
            summary = f"{new_count} yeni şarkı yüklendi, {reused_count} şarkı aynı ISRC kaydından tekrar kullanılmadan yayına eklendi."
            self.append_log(summary)
            QMessageBox.information(self, APP_NAME, "Yayın tamamlandı.\n\n" + summary)

        self.run_task(task, done, "Yeni yayın işleme başladı")

    def upload_asset_to_field(self, field: QLineEdit, category: str) -> None:
        file, _ = QFileDialog.getOpenFileName(self, "Dosya Seç", "", "Medya (*.jpg *.jpeg *.png *.webp *.mp4 *.webm *.mov)")
        if not file:
            return
        path = Path(file)
        settings = copy.deepcopy(self.settings)

        def task(progress: Callable[[str, int], None]) -> str:
            progress("Dosya Hugging Face'e yükleniyor…", 40)
            storage = HuggingFaceStorage(settings)
            remote = storage.allocate_remote(category, path.suffix.lower(), lambda status: progress(status, 25))
            url = storage.upload_one(path, remote, f"Aurora asset: {category}", progress=lambda status, percent: progress(status, 25 + int(percent * 0.70)))
            progress("Yükleme tamamlandı", 100)
            return url

        def done(url: str) -> None:
            field.setText(url)
            self.publish_progress.setValue(100)
            self.append_log(f"Medya yüklendi: {url}")

        self.run_task(task, done, "Medya yükleme başladı")

    def save_settings_from_quality(self) -> None:
        self.settings.upload_master = self.q_master.isChecked()
        self.settings.make_standard = self.q_standard.isChecked()
        self.settings.make_high = self.q_high.isChecked()
        self.settings.make_lossless = self.q_lossless.isChecked()
        self.settings.make_hires = self.q_hires.isChecked()

    def save_settings_from_ui_if_available(self) -> None:
        if not hasattr(self, "s_github_repo"):
            return
        self.settings.github_repo = self.s_github_repo.text().strip() or DEFAULT_REPO
        self.settings.github_branch = self.s_github_branch.text().strip() or DEFAULT_BRANCH
        self.settings.github_catalog_path = self.s_catalog_path.text().strip() or DEFAULT_CATALOG_PATH
        self.settings.github_token = self.s_github_token.text().strip()
        self.settings.hf_repo = self.s_hf_repo.text().strip()
        self.settings.hf_repo_type = self.s_hf_type.currentData()
        self.settings.hf_token = self.s_hf_token.text().strip()
        self.settings.spotify_client_id = self.s_spotify_client_id.text().strip()
        self.settings.spotify_client_secret = self.s_spotify_client_secret.text().strip()
        self.settings.spotify_market = self.s_spotify_market.text().strip().upper() or "TR"
        self.settings.spotify_auto_lyrics = self.s_spotify_lyrics.isChecked()
        self.settings.single_max = self.s_single.value()
        self.settings.maxi_max = max(self.settings.single_max, self.s_maxi.value())
        self.settings.ep_max = max(self.settings.maxi_max, self.s_ep.value())
        self.save_settings_from_quality()

    def save_settings(self) -> None:
        self.save_settings_from_ui_if_available()
        self.settings.save()
        self.refresh_import_table()
        QMessageBox.information(self, APP_NAME, "Ayarlar kaydedildi. Tokenlar Windows kullanıcı hesabına bağlı olarak şifrelendi.")

    def test_github(self) -> None:
        self.save_settings_from_ui_if_available()
        try:
            QMessageBox.information(self, APP_NAME, GitHubCatalogClient(self.settings).test())
        except Exception as exc:
            QMessageBox.critical(self, APP_NAME, str(exc))

    def test_hf(self) -> None:
        self.save_settings_from_ui_if_available()
        try:
            QMessageBox.information(self, APP_NAME, HuggingFaceStorage(self.settings).test())
        except Exception as exc:
            QMessageBox.critical(self, APP_NAME, str(exc))

    def test_spotify(self) -> None:
        self.save_settings_from_ui_if_available()
        try:
            client = SpotifyMetadataClient(self.settings)
            client.token()
            QMessageBox.information(self, APP_NAME, "Spotify metadata bağlantısı başarılı.")
        except Exception as exc:
            QMessageBox.critical(self, APP_NAME, str(exc))

    def simple_text_dialog(self, title: str, label: str, default: str) -> tuple[str, bool]:
        dialog = QDialog(self); dialog.setWindowTitle(title)
        layout = QVBoxLayout(dialog); layout.addWidget(QLabel(label))
        edit = QLineEdit(default); layout.addWidget(edit)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject); layout.addWidget(buttons)
        ok = dialog.exec() == QDialog.Accepted
        return edit.text().strip(), ok

    def closeEvent(self, event) -> None:
        if self.dirty:
            answer = QMessageBox.question(self, APP_NAME, "Commit edilmemiş katalog değişiklikleri var. Yine de çıkılsın mı?")
            if answer != QMessageBox.Yes:
                event.ignore(); return
        event.accept()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudio()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
