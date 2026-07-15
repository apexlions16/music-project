from __future__ import annotations

import copy
import shutil
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

import requests
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
)

import AuroraStudio as base
import AuroraStudioV3 as v3
import AuroraStudioV3Entry as v3_entry
import AuroraStudioV4Entry as v4
import AuroraStudioV6Entry as v6
import AuroraStudioV7Final as v7
from AuroraStudioV7Final import AuroraStudioV7Final, normalize_lrc_v7

APP_NAME = base.APP_NAME
APP_VERSION = "0.8.0"
base.APP_VERSION = APP_VERSION
v3.APP_VERSION = APP_VERSION
v3_entry.APP_VERSION = APP_VERSION
v4.APP_VERSION = APP_VERSION
v6.APP_VERSION = APP_VERSION
v7.APP_VERSION = APP_VERSION


class AuroraStudioV8Final(AuroraStudioV7Final):
    def __init__(self):
        self.batch_releases_v8: list[base.ImportRequest] = []
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self._install_batch_release_ui_v8()

    def _install_batch_release_ui_v8(self) -> None:
        if hasattr(self, "batch_queue_v8"):
            return
        publish_box = self.publish_btn.parentWidget()
        body = publish_box.parentWidget() if publish_box else None
        body_layout = body.layout() if body else None
        if body_layout is None:
            return

        group = QGroupBox("Çoklu Yeni Yayın • Batch Kuyruğu")
        layout = QVBoxLayout(group)
        note = QLabel(
            "Her yayını mevcut formda hazırlayıp kuyruğa ekleyin. Batch başlatıldığında bütün ses/kapak "
            "dosyaları tek Hugging Face commit'inde, bütün yayın kayıtları tek GitHub commit'inde gönderilir. "
            "Mevcut tekli yayın düğmesi ve yükleme mantığı değişmez."
        )
        note.setWordWrap(True)
        note.setObjectName("muted")
        layout.addWidget(note)

        self.batch_queue_v8 = QListWidget()
        self.batch_queue_v8.setMinimumHeight(140)
        layout.addWidget(self.batch_queue_v8)

        buttons = QHBoxLayout()
        self.batch_add_btn_v8 = QPushButton("Mevcut Yayını Batch Kuyruğuna Ekle")
        self.batch_add_btn_v8.clicked.connect(self.add_release_to_batch_v8)
        remove = QPushButton("Seçiliyi Kuyruktan Kaldır")
        remove.clicked.connect(self.remove_release_from_batch_v8)
        clear = QPushButton("Kuyruğu Temizle")
        clear.clicked.connect(self.clear_batch_v8)
        buttons.addWidget(self.batch_add_btn_v8)
        buttons.addWidget(remove)
        buttons.addWidget(clear)
        layout.addLayout(buttons)

        self.batch_publish_btn_v8 = QPushButton("Kuyruktaki Yayınları Tek Batch Olarak Yayınla")
        self.batch_publish_btn_v8.setObjectName("primaryButton")
        self.batch_publish_btn_v8.clicked.connect(self.publish_batch_v8)
        layout.addWidget(self.batch_publish_btn_v8)
        self.batch_commit_hint_v8 = QLabel("Kuyruk boş • medya varsa batch başına 1 HF commit + katalog için 1 GitHub commit")
        self.batch_commit_hint_v8.setObjectName("accentText")
        layout.addWidget(self.batch_commit_hint_v8)

        index = body_layout.indexOf(publish_box)
        body_layout.insertWidget(index + 1 if index >= 0 else body_layout.count() - 1, group)
        self.refresh_batch_queue_v8()

    def _normalized_request_v8(self) -> base.ImportRequest:
        request = self.build_import_request()
        for track in request.tracks:
            if track.synced_lyrics.strip():
                track.synced_lyrics = normalize_lrc_v7(track.synced_lyrics)
        return copy.deepcopy(request)

    def add_release_to_batch_v8(self) -> None:
        try:
            request = self._normalized_request_v8()
        except Exception as exc:
            QMessageBox.warning(self, APP_NAME, str(exc))
            return
        self.batch_releases_v8.append(request)
        self.refresh_batch_queue_v8()
        self._reset_import_form_v8()
        self.publish_status.setText(f"{request.release_title} batch kuyruğuna eklendi")
        self.append_log(f"Batch kuyruğuna eklendi: {request.release_title} • {len(request.tracks)} parça")

    def remove_release_from_batch_v8(self) -> None:
        row = self.batch_queue_v8.currentRow()
        if 0 <= row < len(self.batch_releases_v8):
            removed = self.batch_releases_v8.pop(row)
            self.refresh_batch_queue_v8()
            self.append_log(f"Batch kuyruğundan kaldırıldı: {removed.release_title}")

    def clear_batch_v8(self) -> None:
        if not self.batch_releases_v8:
            return
        if QMessageBox.question(self, APP_NAME, "Batch kuyruğundaki bütün yayınlar kaldırılsın mı?") == QMessageBox.Yes:
            self.batch_releases_v8.clear()
            self.refresh_batch_queue_v8()

    def refresh_batch_queue_v8(self) -> None:
        if not hasattr(self, "batch_queue_v8"):
            return
        self.batch_queue_v8.clear()
        total_tracks = 0
        total_audio = 0
        for index, request in enumerate(self.batch_releases_v8):
            audio = sum(1 for track in request.tracks if track.has_master)
            total_tracks += len(request.tracks)
            total_audio += audio
            item = QListWidgetItem(
                f"{index + 1}. {request.release_title} • {len(request.tracks)} parça • "
                f"{audio} ses hazır • {request.release_date}"
            )
            item.setToolTip(
                f"Sanatçı ID: {', '.join(request.release_artist_ids)}\n"
                f"Kapak: {'yerel' if request.cover_path else request.cover_url}\n"
                f"Tür: {request.release_type}"
            )
            self.batch_queue_v8.addItem(item)
        count = len(self.batch_releases_v8)
        self.batch_publish_btn_v8.setEnabled(count > 0)
        self.batch_commit_hint_v8.setText(
            f"{count} yayın • {total_tracks} parça • {total_audio} ses • "
            "medya varsa toplam 1 HF commit + katalog için 1 GitHub commit"
            if count
            else "Kuyruk boş • medya varsa batch başına 1 HF commit + katalog için 1 GitHub commit"
        )

    def _reset_import_form_v8(self) -> None:
        self.import_tracks.clear()
        self.import_release_artist_ids.clear()
        self.import_spotify_id = ""
        self.import_spotify_url = ""
        self.refresh_import_table()
        self.import_title.clear()
        self.cover_path.clear()
        self.cover_url.clear()
        self.hero_url.clear()
        self.animated_path.clear()
        self.spotify_url_input.clear()
        self.import_description.clear()

    @staticmethod
    def _is_hf_url_v8(url: str) -> bool:
        lowered = (url or "").lower()
        return "huggingface.co/" in lowered or "hf.co/" in lowered

    def _download_batch_cover_v8(
        self,
        request: base.ImportRequest,
        folder: Path,
        progress: Callable[[str, int], None],
        release_index: int,
        release_count: int,
    ) -> Path | None:
        if request.cover_path and request.cover_path.is_file():
            return request.cover_path
        url = request.cover_url.strip()
        if not url or self._is_hf_url_v8(url):
            return None
        if not url.startswith("https://"):
            raise RuntimeError(f"{request.release_title}: kapak HTTPS adresi veya yerel kapak gerekli.")
        progress(f"{release_index}/{release_count} • {request.release_title}: Spotify kapağı indiriliyor…", 3)
        response = requests.get(url, timeout=60, stream=True, headers={"User-Agent": f"AuroraStudio/{APP_VERSION}"})
        response.raise_for_status()
        content_type = response.headers.get("Content-Type", "").lower()
        suffix = ".png" if "png" in content_type else ".webp" if "webp" in content_type else ".jpg"
        target = folder / f"cover-{release_index}{suffix}"
        with target.open("wb") as output:
            for chunk in response.iter_content(1024 * 256):
                if chunk:
                    output.write(chunk)
        return target

    def publish_batch_v8(self) -> None:
        if not self.batch_releases_v8:
            QMessageBox.information(self, APP_NAME, "Önce en az bir yayını batch kuyruğuna ekleyin.")
            return
        if not self.catalog_sha:
            QMessageBox.warning(self, APP_NAME, "Önce GitHub'dan kataloğu yükleyin.")
            return
        self.save_settings_from_quality()
        self.settings.spotify_auto_lyrics = self.spotify_auto_lyrics.isChecked()
        self.settings.save()
        requests_batch = copy.deepcopy(self.batch_releases_v8)
        snapshot = copy.deepcopy(self.catalog)
        sha = self.catalog_sha
        settings = copy.deepcopy(self.settings)
        self.publish_btn.setEnabled(False)
        self.batch_add_btn_v8.setEnabled(False)
        self.batch_publish_btn_v8.setEnabled(False)

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str, int, int, int, int]:
            processor = base.MediaProcessor()
            storage = base.HuggingFaceStorage(settings)
            github = base.GitHubCatalogClient(settings)
            temp_root = Path(tempfile.mkdtemp(prefix="aurora-studio-batch-"))
            upload_files: list[tuple[Path, str]] = []
            total_new = 0
            total_reused = 0
            snapshot["schemaVersion"] = max(5, int(snapshot.get("schemaVersion", 1)))
            existing_by_isrc = {
                base.normalize_isrc(row.get("isrc", "")): row
                for row in snapshot.get("tracks", [])
                if base.normalize_isrc(row.get("isrc", ""))
            }
            try:
                for release_index, request in enumerate(requests_batch, start=1):
                    release_id = base.opaque_id("release")
                    release_folder = temp_root / f"release-{release_index}"
                    release_folder.mkdir(parents=True, exist_ok=True)
                    release_rows: list[dict[str, Any]] = []
                    new_tracks: list[dict[str, Any]] = []
                    cover_url = request.cover_url
                    cover_path = self._download_batch_cover_v8(request, release_folder, progress, release_index, len(requests_batch))
                    if cover_path:
                        remote = storage.allocate_remote("artwork", cover_path.suffix.lower() or ".jpg", lambda status: progress(status, 4))
                        upload_files.append((cover_path, remote))
                        cover_url = storage.resolve_url(remote)
                    if not cover_url:
                        raise RuntimeError(f"{request.release_title}: kapak URL'si veya kapak dosyası gerekli.")

                    total_tracks = len(request.tracks)
                    for track_index, row in enumerate(request.tracks):
                        batch_fraction = ((release_index - 1) + (track_index / max(total_tracks, 1))) / max(len(requests_batch), 1)
                        base_progress = 5 + int(batch_fraction * 66)
                        normalized = base.normalize_isrc(row.isrc)
                        existing = existing_by_isrc.get(normalized) if normalized else None
                        if existing and (existing.get("sources") or not row.has_master):
                            total_reused += 1
                            progress(
                                f"{release_index}/{len(requests_batch)} • {request.release_title} • {row.title}: mevcut ISRC kullanılıyor",
                                base_progress,
                            )
                            release_rows.append({
                                "trackId": existing["id"],
                                "disc": row.disc or 1,
                                "position": row.position or track_index + 1,
                            })
                            continue

                        filling_existing = existing if existing and row.has_master else None
                        track_id = filling_existing.get("id") if filling_existing else base.opaque_id("track")
                        sources: list[dict[str, Any]] = []
                        info = {"duration": row.duration_seconds, "channels": 0}
                        variants: list[dict[str, Any]] = []
                        if row.has_master:
                            progress(
                                f"{release_index}/{len(requests_batch)} • {request.release_title} • {row.title}: ses analiz ediliyor",
                                base_progress,
                            )
                            media_folder = release_folder / track_id
                            info, variants = processor.process(row.path, media_folder, settings)
                            if settings.upload_master:
                                master_remote = storage.allocate_remote("masters", row.path.suffix.lower(), lambda status: progress(status, base_progress))
                                upload_files.append((row.path, master_remote))
                        else:
                            progress(
                                f"{release_index}/{len(requests_batch)} • {request.release_title} • {row.title}: Yakında kaydı hazırlanıyor",
                                base_progress,
                            )

                        for variant in variants:
                            remote = storage.allocate_remote("audio", variant["path"].suffix.lower(), lambda status: progress(status, base_progress))
                            upload_files.append((variant["path"], remote))
                            url = storage.resolve_url(remote)
                            source = {
                                "id": base.opaque_id("audio"),
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
                            remote = storage.allocate_remote("atmos", row.atmos_path.suffix.lower(), lambda status: progress(status, base_progress))
                            upload_files.append((row.atmos_path, remote))
                            url = storage.resolve_url(remote)
                            sources.append({
                                "id": base.opaque_id("audio"),
                                "kind": "dolby_atmos",
                                "label": "Dolby Atmos",
                                "codec": "Dolby Atmos / E-AC-3 JOC",
                                "url": url,
                                "downloadUrl": url,
                                "downloadable": True,
                                "channels": "Çok kanallı",
                                "spatial": True,
                            })

                        primary_ids = base.ordered_unique(row.primary_artist_ids or request.release_artist_ids)
                        featured_ids = [value for value in base.ordered_unique(row.featured_artist_ids) if value not in primary_ids]
                        track_data = {
                            "id": track_id,
                            "slug": base.slugify(row.title),
                            "title": row.title,
                            "artistIds": base.ordered_unique(primary_ids + featured_ids),
                            "primaryArtistIds": primary_ids,
                            "featuredArtistIds": featured_ids,
                            "featuredArtistNames": base.ordered_unique(row.featured_artist_names),
                            "durationSeconds": info["duration"] or row.duration_seconds,
                            "isrc": row.isrc,
                            "explicit": row.explicit,
                            "spotifyId": row.spotify_id,
                            "spotifyUrl": row.spotify_url,
                            "lyrics": row.lyrics,
                            "syncedLyrics": row.synced_lyrics,
                            "credits": row.credits,
                            "availability": "available" if sources else "pending",
                            "sources": sources,
                        }
                        if filling_existing:
                            filling_existing.update(track_data)
                            filling_existing["id"] = track_id
                            filling_existing["availability"] = "available" if sources else "pending"
                            total_reused += 1
                        else:
                            new_tracks.append(track_data)
                            total_new += 1
                        if normalized:
                            existing_by_isrc[normalized] = filling_existing or track_data
                        release_rows.append({
                            "trackId": track_id,
                            "disc": row.disc or 1,
                            "position": row.position or track_index + 1,
                        })

                    all_tracks = snapshot.get("tracks", []) + new_tracks
                    availability = [
                        any(track.get("id") == ref.get("trackId") and track.get("sources") for track in all_tracks)
                        for ref in release_rows
                    ]
                    release = {
                        "id": release_id,
                        "slug": base.slugify(request.release_title),
                        "title": request.release_title,
                        "type": request.release_type,
                        "artistIds": request.release_artist_ids,
                        "primaryArtistIds": request.release_artist_ids,
                        "releaseDate": request.release_date,
                        "cover": cover_url,
                        "heroImage": request.hero_url or cover_url,
                        "animatedCoverUrl": request.animated_cover_url,
                        "label": request.label,
                        "copyright": request.copyright_text,
                        "description": request.description,
                        "spotifyId": request.spotify_id,
                        "spotifyUrl": request.spotify_url,
                        "spotifyCoverUrl": request.cover_url,
                        "status": (
                            "published"
                            if availability and all(availability) and request.release_date <= datetime.now().strftime("%Y-%m-%d")
                            else "partial" if any(availability) else "upcoming"
                        ),
                        "publishAt": request.release_date,
                        "tracks": sorted(release_rows, key=lambda item: (item.get("disc", 1), item.get("position", 1))),
                    }
                    snapshot.setdefault("tracks", []).extend(new_tracks)
                    snapshot.setdefault("releases", []).append(release)
                    if request.featured:
                        snapshot.setdefault("featuredReleaseIds", []).insert(0, release_id)
                    progress(
                        f"{release_index}/{len(requests_batch)} • {request.release_title}: katalog taslağı hazır",
                        72,
                    )

                if upload_files:
                    progress(
                        f"{len(upload_files)} medya dosyası tek Hugging Face batch commit'inde gönderiliyor…",
                        74,
                    )
                    storage.create_commit(
                        upload_files,
                        f"Aurora Music: {len(requests_batch)} yayınlık batch medya yüklemesi",
                        progress=lambda status, percent: progress(status, 74 + int(percent * .18)),
                    )
                else:
                    progress("Bütün şarkılar mevcut ISRC kayıtlarından kullanıldı; medya commit'i gerekmedi", 92)

                titles = ", ".join(request.release_title for request in requests_batch)[:180]
                progress("Bütün yayınlar tek GitHub katalog commit'ine yazılıyor…", 94)
                new_sha = github.commit_catalog(
                    snapshot,
                    sha,
                    f"Aurora Music: {len(requests_batch)} yayını batch olarak ekle • {titles}",
                )
                progress("Batch yayın tamamlandı", 100)
                return snapshot, new_sha, total_reused, total_new, len(requests_batch), len(upload_files)
            finally:
                shutil.rmtree(temp_root, ignore_errors=True)

        def done(result: tuple[dict[str, Any], str, int, int, int, int]) -> None:
            self.catalog, self.catalog_sha, reused_count, new_count, release_count, media_count = result
            self.batch_releases_v8.clear()
            self.set_dirty(False)
            self.publish_progress.setValue(100)
            self.publish_status.setText("Batch yayın başarıyla tamamlandı")
            self.refresh_batch_queue_v8()
            self.refresh_all_views()
            summary = (
                f"{release_count} yayın tek batch olarak eklendi. {new_count} yeni şarkı, "
                f"{reused_count} mevcut ISRC kullanımı, {media_count} medya operasyonu. "
                "Toplam: 1 Hugging Face commit + 1 GitHub katalog commit."
            )
            self.append_log(summary)
            QMessageBox.information(self, APP_NAME, "Batch yayın tamamlandı.\n\n" + summary)

        self.run_task(task, done, f"{len(requests_batch)} yayınlık batch işleme başladı")
        if self.active_thread:
            self.active_thread.finished.connect(self._batch_finished_v8)

    def _batch_finished_v8(self) -> None:
        self.publish_btn.setEnabled(True)
        self.batch_add_btn_v8.setEnabled(True)
        self.refresh_batch_queue_v8()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV8Final()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
