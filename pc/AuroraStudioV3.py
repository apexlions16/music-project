from __future__ import annotations

import copy
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

import requests
from PySide6.QtCore import QDate, Qt
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication

import AuroraStudio as base

base.APP_VERSION = "0.3.0"
APP_NAME = base.APP_NAME
APP_VERSION = "0.3.0"


class OpenMetadataClient:
    """MusicBrainz + Cover Art Archive + LRCLIB tabanlı ücretsiz metadata istemcisi."""

    def __init__(self, contact: str = "https://github.com/apexlions16/music-project"):
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Accept": "application/json",
                "User-Agent": f"AuroraStudio/{APP_VERSION} ({contact})",
            }
        )

    @staticmethod
    def _artist_names(credit: list[Any] | None) -> list[dict[str, str]]:
        rows: list[dict[str, str]] = []
        for item in credit or []:
            if not isinstance(item, dict):
                continue
            artist = item.get("artist") or {}
            name = str(item.get("name") or artist.get("name") or "").strip()
            if not name:
                continue
            rows.append({"name": name, "mbid": str(artist.get("id") or "")})
        unique: list[dict[str, str]] = []
        seen: set[str] = set()
        for row in rows:
            key = (row["mbid"] or row["name"]).casefold()
            if key not in seen:
                seen.add(key)
                unique.append(row)
        return unique

    def search_release(self, query: str) -> dict[str, Any]:
        query = query.strip()
        if not query:
            raise ValueError("Albüm veya single adı gerekli.")
        response = self.session.get(
            "https://musicbrainz.org/ws/2/release/",
            params={"query": f'release:"{query.replace(chr(34), "")}"', "limit": 10, "fmt": "json"},
            timeout=30,
        )
        response.raise_for_status()
        releases = response.json().get("releases") or []
        if not releases:
            raise RuntimeError("MusicBrainz üzerinde uygun yayın bulunamadı.")
        return max(releases, key=lambda row: int(row.get("score") or 0))

    def fetch_release(self, query: str, include_lyrics: bool = True) -> dict[str, Any]:
        match = self.search_release(query)
        release_id = str(match.get("id") or "")
        response = self.session.get(
            f"https://musicbrainz.org/ws/2/release/{release_id}",
            params={"inc": "recordings+artist-credits+labels+isrcs+release-groups", "fmt": "json"},
            timeout=45,
        )
        response.raise_for_status()
        data = response.json()
        release_artists = self._artist_names(data.get("artist-credit"))
        title = str(data.get("title") or query).strip()
        release_group = data.get("release-group") or {}
        primary_type = str(release_group.get("primary-type") or "").lower()
        release_type = {"single": "single", "ep": "ep", "album": "album"}.get(primary_type, "album")
        labels = data.get("label-info") or []
        label = ""
        if labels and isinstance(labels[0], dict):
            label = str((labels[0].get("label") or {}).get("name") or "")
        tracks: list[dict[str, Any]] = []
        for medium in data.get("media") or []:
            disc = int(medium.get("position") or 1)
            for index, row in enumerate(medium.get("tracks") or []):
                recording = row.get("recording") or {}
                artists = self._artist_names(recording.get("artist-credit") or row.get("artist-credit"))
                track_title = str(row.get("title") or recording.get("title") or f"Şarkı {index + 1}").strip()
                isrcs = recording.get("isrcs") or []
                plain, synced = ("", "")
                if include_lyrics:
                    plain, synced = self.lookup_lyrics(
                        track_title,
                        (artists or release_artists or [{"name": ""}])[0]["name"],
                        title,
                    )
                tracks.append(
                    {
                        "title": track_title,
                        "isrc": str(isrcs[0] if isrcs else ""),
                        "artists": artists or release_artists,
                        "durationSeconds": round(float(row.get("length") or recording.get("length") or 0) / 1000),
                        "disc": disc,
                        "position": int(row.get("position") or index + 1),
                        "lyrics": plain,
                        "syncedLyrics": synced,
                    }
                )
        if not tracks:
            raise RuntimeError("Yayında parça listesi bulunamadı.")
        return {
            "source": "musicbrainz+coverartarchive+lrclib",
            "sourceId": release_id,
            "title": title,
            "releaseType": release_type,
            "releaseDate": str(data.get("date") or ""),
            "label": label,
            "copyright": "",
            "cover": f"https://coverartarchive.org/release/{release_id}/front-1200",
            "artists": release_artists,
            "tracks": tracks,
        }

    def lookup_lyrics(self, track: str, artist: str, album: str) -> tuple[str, str]:
        try:
            response = self.session.get(
                "https://lrclib.net/api/get",
                params={"track_name": track, "artist_name": artist, "album_name": album},
                timeout=15,
            )
            if response.status_code == 404:
                return "", ""
            response.raise_for_status()
            value = response.json()
            return str(value.get("plainLyrics") or ""), str(value.get("syncedLyrics") or "")
        except Exception:
            return "", ""


class AuroraStudioV3(base.AuroraStudio):
    @staticmethod
    def empty_catalog() -> dict[str, Any]:
        value = base.AuroraStudio.empty_catalog()
        value["schemaVersion"] = 5
        value.setdefault("homeSections", [])
        value.setdefault("artistLists", [])
        value.setdefault("qualityJobs", [])
        return value

    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")

    def ensure_v5(self) -> None:
        self.catalog["schemaVersion"] = max(5, int(self.catalog.get("schemaVersion", 1)))
        self.catalog.setdefault("homeSections", [])
        self.catalog.setdefault("artistLists", [])
        self.catalog.setdefault("qualityJobs", [])
        for artist in self.catalog.get("artists", []):
            artist.setdefault("popularTrackIds", [])
            artist.setdefault("listIds", [])
        for track in self.catalog.get("tracks", []):
            sources = track.setdefault("sources", [])
            track.setdefault("playable", bool(sources))
            track.setdefault("availability", "available" if sources else "upcoming")
            track.setdefault("qualityState", "ready" if sources else "waiting_for_audio")
        for release in self.catalog.get("releases", []):
            self.refresh_release_state(release)

    def make_import_page(self):
        page = super().make_import_page()
        self.animated_url_v3 = base.QLineEdit()
        self.animated_url_v3.setPlaceholderText("https://.../arka-plan.mp4 — yalnızca URL saklanır")
        self.cover_fetch_btn_v3 = base.QPushButton("Görsel Fetch • URL'yi indir")
        self.cover_fetch_btn_v3.clicked.connect(self.fetch_cover_to_local)
        for group in page.findChildren(base.QGroupBox):
            if "Görseller" not in group.title():
                continue
            layout = group.layout()
            if isinstance(layout, base.QFormLayout):
                layout.addRow("Hareketli kapak URL", self.animated_url_v3)
                layout.addRow("", self.cover_fetch_btn_v3)
            break
        return page

    def build_ui(self) -> None:
        super().build_ui()
        self.nav.insertItem(2, base.QListWidgetItem("Ücretsiz Metadata"))
        self.pages.insertWidget(2, self.make_open_metadata_page())
        self.nav.insertItem(6, base.QListWidgetItem("Sunum ve Listeler"))
        self.pages.insertWidget(6, self.make_curation_page())
        self.nav.currentRowChanged.disconnect()
        self.nav.currentRowChanged.connect(self.pages_set_index)

    def pages_set_index(self, index: int) -> None:
        if index < 0:
            return
        self.pages.setCurrentIndex(index)
        if index == 7:
            self.json_editor.setPlainText(json.dumps(self.catalog, ensure_ascii=False, indent=2))

    def make_open_metadata_page(self):
        page, layout = self.page_container(
            "Ücretsiz Metadata",
            "MusicBrainz, Cover Art Archive ve LRCLIB ile albüm/single bilgilerini, ISRC'leri, kapak adresini ve uygun sözleri doldurur.",
        )
        box = base.QGroupBox("Yayın Ara")
        form = base.QFormLayout(box)
        self.open_query = base.QLineEdit()
        self.open_query.setPlaceholderText("Albüm veya single adı")
        self.open_lyrics = base.QCheckBox("LRCLIB üzerinde söz ve senkronize LRC ara")
        self.open_lyrics.setChecked(True)
        search = base.QPushButton("Metadata'yı Bul ve Yeni Yayına Aktar")
        search.setObjectName("primaryButton")
        search.clicked.connect(self.import_from_open_metadata)
        form.addRow("Arama", self.open_query)
        form.addRow("", self.open_lyrics)
        form.addRow("", search)
        layout.addWidget(box)
        note = base.QLabel(
            "Spotify anahtarları zorunlu değildir. Bu akış kalıcı katalog verisini açık kaynaklardan oluşturur. "
            "Kapak URL olarak gelir; Yeni Yayın ekranındaki Görsel Fetch düğmesiyle dosyayı indirip Hugging Face'e kalıcı yükleyebilirsiniz."
        )
        note.setWordWrap(True)
        note.setObjectName("muted")
        layout.addWidget(note)
        self.open_result = base.QPlainTextEdit()
        self.open_result.setReadOnly(True)
        layout.addWidget(self.open_result, 1)
        return page

    def make_curation_page(self):
        page, layout = self.page_container(
            "Sunum ve Listeler",
            "Sanatçı popülerlerini, sanatçı seçkilerini ve Aurora Music ana sayfasındaki rafları yönetir.",
        )
        tabs = base.QTabWidget()
        tabs.addTab(self.make_popular_tab(), "Sanatçı Popülerleri")
        tabs.addTab(self.make_artist_lists_tab(), "Sanatçı Listeleri")
        tabs.addTab(self.make_home_sections_tab(), "Ana Sayfa Bölümleri")
        layout.addWidget(tabs, 1)
        return page

    def make_popular_tab(self):
        page = base.QWidget()
        layout = base.QVBoxLayout(page)
        self.popular_artist = base.QComboBox()
        self.popular_artist.currentIndexChanged.connect(self.load_popular_artist)
        layout.addWidget(base.QLabel("Sanatçı"))
        layout.addWidget(self.popular_artist)
        columns = base.QHBoxLayout()
        left = base.QVBoxLayout()
        right = base.QVBoxLayout()
        self.popular_available = base.QListWidget()
        self.popular_selected = base.QListWidget()
        self.popular_selected.setDragDropMode(base.QAbstractItemView.InternalMove)
        left.addWidget(base.QLabel("Sanatçının Çalınabilir Şarkıları"))
        left.addWidget(self.popular_available)
        right.addWidget(base.QLabel("Uygulamada Gösterilecek Popüler Sırası"))
        right.addWidget(self.popular_selected)
        controls = base.QVBoxLayout()
        add = base.QPushButton("Ekle →")
        add.clicked.connect(self.add_popular_track)
        remove = base.QPushButton("← Çıkar")
        remove.clicked.connect(self.remove_popular_track)
        up = base.QPushButton("Yukarı")
        up.clicked.connect(lambda: self.move_list_item(self.popular_selected, -1))
        down = base.QPushButton("Aşağı")
        down.clicked.connect(lambda: self.move_list_item(self.popular_selected, 1))
        for button in [add, remove, up, down]:
            controls.addWidget(button)
        controls.addStretch()
        columns.addLayout(left, 2)
        columns.addLayout(controls)
        columns.addLayout(right, 2)
        layout.addLayout(columns, 1)
        save = base.QPushButton("Popüler Sırasını Kaydet")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_popular_tracks)
        layout.addWidget(save)
        return page

    def make_artist_lists_tab(self):
        page = base.QWidget()
        root = base.QHBoxLayout(page)
        left = base.QVBoxLayout()
        self.curation_lists = base.QListWidget()
        self.curation_lists.currentRowChanged.connect(self.load_artist_list_form)
        new = base.QPushButton("Yeni Liste")
        new.setObjectName("primaryButton")
        new.clicked.connect(self.new_artist_list)
        delete = base.QPushButton("Listeyi Sil")
        delete.clicked.connect(self.delete_artist_list)
        left.addWidget(self.curation_lists, 1)
        left.addWidget(new)
        left.addWidget(delete)
        form_widget = base.QWidget()
        form = base.QFormLayout(form_widget)
        self.artist_list_artist = base.QComboBox()
        self.artist_list_artist.currentIndexChanged.connect(self.refresh_artist_list_tracks)
        self.artist_list_title = base.QLineEdit()
        self.artist_list_description = base.QPlainTextEdit()
        self.artist_list_description.setMaximumHeight(90)
        self.artist_list_cover = base.QLineEdit()
        self.artist_list_tracks = base.QListWidget()
        self.artist_list_tracks.setSelectionMode(base.QAbstractItemView.NoSelection)
        save = base.QPushButton("Sanatçı Listesini Kaydet")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_artist_list)
        form.addRow("Sanatçı", self.artist_list_artist)
        form.addRow("Başlık", self.artist_list_title)
        form.addRow("Açıklama", self.artist_list_description)
        form.addRow("Kapak URL", self.artist_list_cover)
        form.addRow("Şarkılar", self.artist_list_tracks)
        form.addRow("", save)
        root.addLayout(left, 1)
        root.addWidget(form_widget, 2)
        return page

    def make_home_sections_tab(self):
        page = base.QWidget()
        root = base.QHBoxLayout(page)
        left = base.QVBoxLayout()
        self.home_sections = base.QListWidget()
        self.home_sections.currentRowChanged.connect(self.load_home_section_form)
        new = base.QPushButton("Yeni Bölüm")
        new.setObjectName("primaryButton")
        new.clicked.connect(self.new_home_section)
        delete = base.QPushButton("Bölümü Sil")
        delete.clicked.connect(self.delete_home_section)
        move_up = base.QPushButton("Yukarı")
        move_up.clicked.connect(lambda: self.move_home_section(-1))
        move_down = base.QPushButton("Aşağı")
        move_down.clicked.connect(lambda: self.move_home_section(1))
        left.addWidget(self.home_sections, 1)
        for button in [new, delete, move_up, move_down]:
            left.addWidget(button)
        form_widget = base.QWidget()
        form = base.QFormLayout(form_widget)
        self.home_id = base.QLineEdit()
        self.home_title = base.QLineEdit()
        self.home_subtitle = base.QLineEdit()
        self.home_type = base.QComboBox()
        for label, value in [("Yayınlar", "releases"), ("Sanatçılar", "artists"), ("Şarkılar", "tracks"), ("Sanatçı Listeleri", "lists")]:
            self.home_type.addItem(label, value)
        self.home_type.currentIndexChanged.connect(self.refresh_home_content)
        self.home_layout = base.QComboBox()
        for label, value in [("Yatay Raf", "horizontal"), ("Büyük Öne Çıkan", "hero")]:
            self.home_layout.addItem(label, value)
        self.home_content = base.QListWidget()
        self.home_content.setSelectionMode(base.QAbstractItemView.NoSelection)
        save = base.QPushButton("Ana Sayfa Bölümünü Kaydet")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_home_section)
        form.addRow("Kimlik", self.home_id)
        form.addRow("Başlık", self.home_title)
        form.addRow("Alt başlık", self.home_subtitle)
        form.addRow("İçerik türü", self.home_type)
        form.addRow("Görünüm", self.home_layout)
        form.addRow("Gösterilecek öğeler", self.home_content)
        form.addRow("", save)
        root.addLayout(left, 1)
        root.addWidget(form_widget, 2)
        return page

    @staticmethod
    def move_list_item(widget, direction: int) -> None:
        row = widget.currentRow()
        target = row + direction
        if not (0 <= row < widget.count() and 0 <= target < widget.count()):
            return
        item = widget.takeItem(row)
        widget.insertItem(target, item)
        widget.setCurrentRow(target)

    def refresh_all_views(self) -> None:
        self.ensure_v5()
        super().refresh_all_views()
        if hasattr(self, "popular_artist"):
            self.refresh_curation_views()

    def refresh_curation_views(self) -> None:
        artists = self.catalog.get("artists", [])
        for combo in [self.popular_artist, self.artist_list_artist]:
            current = combo.currentData()
            combo.blockSignals(True)
            combo.clear()
            for artist in artists:
                combo.addItem(artist.get("name", "Adsız"), artist.get("id"))
            if current:
                combo.setCurrentIndex(max(0, combo.findData(current)))
            combo.blockSignals(False)
        self.curation_lists.blockSignals(True)
        current_list_id = self.current_id(self.curation_lists)
        self.curation_lists.clear()
        for row in self.catalog.get("artistLists", []):
            artist = self.find_by_id("artists", row.get("artistId"))
            item = base.QListWidgetItem(f"{row.get('title', 'Adsız')} • {(artist or {}).get('name', 'Sanatçı yok')}")
            item.setData(Qt.UserRole, row.get("id"))
            self.curation_lists.addItem(item)
        self.curation_lists.blockSignals(False)
        if current_list_id:
            self.select_item_by_id(self.curation_lists, current_list_id)
        self.home_sections.blockSignals(True)
        current_section_id = self.current_id(self.home_sections)
        self.home_sections.clear()
        for row in self.catalog.get("homeSections", []):
            item = base.QListWidgetItem(row.get("title", "Adsız bölüm"))
            item.setData(Qt.UserRole, row.get("id"))
            self.home_sections.addItem(item)
        self.home_sections.blockSignals(False)
        if current_section_id:
            self.select_item_by_id(self.home_sections, current_section_id)
        self.load_popular_artist()
        if self.curation_lists.currentRow() < 0 and self.curation_lists.count():
            self.curation_lists.setCurrentRow(0)
        if self.home_sections.currentRow() < 0 and self.home_sections.count():
            self.home_sections.setCurrentRow(0)

    @staticmethod
    def select_item_by_id(widget, item_id: str) -> None:
        for index in range(widget.count()):
            if widget.item(index).data(Qt.UserRole) == item_id:
                widget.setCurrentRow(index)
                return

    def artist_owned_tracks(self, artist_id: str) -> list[dict[str, Any]]:
        return [
            row
            for row in self.catalog.get("tracks", [])
            if artist_id in (row.get("primaryArtistIds") or row.get("artistIds", []))
        ]

    def load_popular_artist(self) -> None:
        if not hasattr(self, "popular_artist"):
            return
        artist_id = self.popular_artist.currentData()
        artist = self.find_by_id("artists", artist_id)
        selected_ids = list((artist or {}).get("popularTrackIds", []))
        owned = self.artist_owned_tracks(artist_id)
        lookup = {row.get("id"): row for row in owned}
        self.popular_available.clear()
        self.popular_selected.clear()
        for track_id in selected_ids:
            track = lookup.get(track_id) or self.find_by_id("tracks", track_id)
            if track:
                item = base.QListWidgetItem(track.get("title", track_id))
                item.setData(Qt.UserRole, track_id)
                self.popular_selected.addItem(item)
        for track in owned:
            if track.get("id") in selected_ids:
                continue
            state = "hazır" if track.get("sources") else "yakında"
            item = base.QListWidgetItem(f"{track.get('title', 'Adsız')} • {state}")
            item.setData(Qt.UserRole, track.get("id"))
            self.popular_available.addItem(item)

    def add_popular_track(self) -> None:
        item = self.popular_available.currentItem()
        if not item:
            return
        row = self.popular_available.row(item)
        item = self.popular_available.takeItem(row)
        item.setText(item.text().split(" • ")[0])
        self.popular_selected.addItem(item)

    def remove_popular_track(self) -> None:
        item = self.popular_selected.currentItem()
        if not item:
            return
        row = self.popular_selected.row(item)
        item = self.popular_selected.takeItem(row)
        self.popular_available.addItem(item)

    def save_popular_tracks(self) -> None:
        artist = self.find_by_id("artists", self.popular_artist.currentData())
        if not artist:
            return
        artist["popularTrackIds"] = [self.popular_selected.item(i).data(Qt.UserRole) for i in range(self.popular_selected.count())][:5]
        self.set_dirty()
        self.append_log(f"{artist.get('name')} popüler sırası güncellendi.")

    def new_artist_list(self) -> None:
        artist_id = self.artist_list_artist.currentData() or self.popular_artist.currentData()
        row = {
            "id": base.opaque_id("artist_list"),
            "artistId": artist_id or "",
            "title": "Yeni Sanatçı Seçkisi",
            "description": "",
            "cover": "",
            "trackIds": [],
        }
        self.catalog.setdefault("artistLists", []).append(row)
        artist = self.find_by_id("artists", artist_id)
        if artist:
            artist.setdefault("listIds", []).append(row["id"])
        self.set_dirty()
        self.refresh_curation_views()
        self.select_item_by_id(self.curation_lists, row["id"])

    def load_artist_list_form(self) -> None:
        row = self.find_by_id("artistLists", self.current_id(self.curation_lists))
        if not row:
            return
        self.artist_list_artist.setCurrentIndex(max(0, self.artist_list_artist.findData(row.get("artistId"))))
        self.artist_list_title.setText(row.get("title", ""))
        self.artist_list_description.setPlainText(row.get("description", ""))
        self.artist_list_cover.setText(row.get("cover", ""))
        self.refresh_artist_list_tracks(row.get("trackIds", []))

    def refresh_artist_list_tracks(self, selected_ids: list[str] | None = None) -> None:
        if not hasattr(self, "artist_list_tracks"):
            return
        if selected_ids is None:
            row = self.find_by_id("artistLists", self.current_id(self.curation_lists))
            selected_ids = list((row or {}).get("trackIds", []))
        artist_id = self.artist_list_artist.currentData()
        self.artist_list_tracks.clear()
        for track in self.artist_owned_tracks(artist_id):
            item = base.QListWidgetItem(track.get("title", "Adsız"))
            item.setData(Qt.UserRole, track.get("id"))
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked if track.get("id") in selected_ids else Qt.Unchecked)
            self.artist_list_tracks.addItem(item)

    def save_artist_list(self) -> None:
        row = self.find_by_id("artistLists", self.current_id(self.curation_lists))
        if not row:
            return
        old_artist_id = row.get("artistId")
        artist_id = self.artist_list_artist.currentData()
        track_ids = [
            self.artist_list_tracks.item(i).data(Qt.UserRole)
            for i in range(self.artist_list_tracks.count())
            if self.artist_list_tracks.item(i).checkState() == Qt.Checked
        ]
        row.update(
            {
                "artistId": artist_id or "",
                "title": self.artist_list_title.text().strip() or "Sanatçı Seçkisi",
                "description": self.artist_list_description.toPlainText(),
                "cover": self.artist_list_cover.text().strip(),
                "trackIds": track_ids,
            }
        )
        for artist in self.catalog.get("artists", []):
            artist["listIds"] = [x for x in artist.get("listIds", []) if x != row["id"]]
        artist = self.find_by_id("artists", artist_id)
        if artist:
            artist.setdefault("listIds", []).append(row["id"])
            artist["listIds"] = base.ordered_unique(artist["listIds"])
        self.set_dirty()
        self.refresh_curation_views()
        self.select_item_by_id(self.curation_lists, row["id"])
        self.append_log(f"Sanatçı listesi güncellendi: {row['title']}")

    def delete_artist_list(self) -> None:
        list_id = self.current_id(self.curation_lists)
        row = self.find_by_id("artistLists", list_id)
        if not row:
            return
        if base.QMessageBox.question(self, APP_NAME, f"{row.get('title')} silinsin mi?") != base.QMessageBox.Yes:
            return
        self.catalog["artistLists"] = [x for x in self.catalog.get("artistLists", []) if x.get("id") != list_id]
        for artist in self.catalog.get("artists", []):
            artist["listIds"] = [x for x in artist.get("listIds", []) if x != list_id]
        for section in self.catalog.get("homeSections", []):
            section["listIds"] = [x for x in section.get("listIds", []) if x != list_id]
        self.set_dirty()
        self.refresh_curation_views()

    def new_home_section(self) -> None:
        row = {
            "id": base.opaque_id("home"),
            "title": "Yeni Bölüm",
            "subtitle": "",
            "type": "releases",
            "layout": "horizontal",
            "releaseIds": [],
            "artistIds": [],
            "trackIds": [],
            "listIds": [],
        }
        self.catalog.setdefault("homeSections", []).append(row)
        self.set_dirty()
        self.refresh_curation_views()
        self.select_item_by_id(self.home_sections, row["id"])

    def load_home_section_form(self) -> None:
        row = self.find_by_id("homeSections", self.current_id(self.home_sections))
        if not row:
            return
        self.home_id.setText(row.get("id", ""))
        self.home_title.setText(row.get("title", ""))
        self.home_subtitle.setText(row.get("subtitle", ""))
        self.home_type.setCurrentIndex(max(0, self.home_type.findData(row.get("type", "releases"))))
        self.home_layout.setCurrentIndex(max(0, self.home_layout.findData(row.get("layout", "horizontal"))))
        selected = row.get(self.section_key(row.get("type", "releases")), [])
        self.refresh_home_content(selected)

    @staticmethod
    def section_key(section_type: str) -> str:
        return {"artists": "artistIds", "tracks": "trackIds", "lists": "listIds"}.get(section_type, "releaseIds")

    def refresh_home_content(self, selected_ids: list[str] | None = None) -> None:
        if not hasattr(self, "home_content"):
            return
        section_type = self.home_type.currentData() or "releases"
        if selected_ids is None:
            row = self.find_by_id("homeSections", self.current_id(self.home_sections))
            selected_ids = list((row or {}).get(self.section_key(section_type), []))
        collections = {
            "releases": (self.catalog.get("releases", []), "title"),
            "artists": (self.catalog.get("artists", []), "name"),
            "tracks": (self.catalog.get("tracks", []), "title"),
            "lists": (self.catalog.get("artistLists", []), "title"),
        }
        rows, label_key = collections[section_type]
        self.home_content.clear()
        for row in rows:
            item = base.QListWidgetItem(row.get(label_key, "Adsız"))
            item.setData(Qt.UserRole, row.get("id"))
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked if row.get("id") in selected_ids else Qt.Unchecked)
            self.home_content.addItem(item)

    def save_home_section(self) -> None:
        row = self.find_by_id("homeSections", self.current_id(self.home_sections))
        if not row:
            return
        section_type = self.home_type.currentData() or "releases"
        selected = [
            self.home_content.item(i).data(Qt.UserRole)
            for i in range(self.home_content.count())
            if self.home_content.item(i).checkState() == Qt.Checked
        ]
        row.update(
            {
                "id": self.home_id.text().strip() or row.get("id") or base.opaque_id("home"),
                "title": self.home_title.text().strip() or "Bölüm",
                "subtitle": self.home_subtitle.text().strip(),
                "type": section_type,
                "layout": self.home_layout.currentData() or "horizontal",
                "releaseIds": [],
                "artistIds": [],
                "trackIds": [],
                "listIds": [],
            }
        )
        row[self.section_key(section_type)] = selected
        self.set_dirty()
        self.refresh_curation_views()
        self.select_item_by_id(self.home_sections, row["id"])
        self.append_log(f"Ana sayfa bölümü güncellendi: {row['title']}")

    def delete_home_section(self) -> None:
        section_id = self.current_id(self.home_sections)
        row = self.find_by_id("homeSections", section_id)
        if not row:
            return
        if base.QMessageBox.question(self, APP_NAME, f"{row.get('title')} bölümü silinsin mi?") != base.QMessageBox.Yes:
            return
        self.catalog["homeSections"] = [x for x in self.catalog.get("homeSections", []) if x.get("id") != section_id]
        self.set_dirty()
        self.refresh_curation_views()

    def move_home_section(self, direction: int) -> None:
        row = self.home_sections.currentRow()
        target = row + direction
        sections = self.catalog.get("homeSections", [])
        if not (0 <= row < len(sections) and 0 <= target < len(sections)):
            return
        sections[row], sections[target] = sections[target], sections[row]
        self.set_dirty()
        self.refresh_curation_views()
        self.home_sections.setCurrentRow(target)

    def ensure_open_artist(self, value: dict[str, str]) -> str:
        name = value.get("name", "").strip() or "Bilinmeyen Sanatçı"
        mbid = value.get("mbid", "")
        existing = next(
            (
                row
                for row in self.catalog.get("artists", [])
                if (mbid and row.get("musicBrainzId") == mbid) or row.get("name", "").casefold() == name.casefold()
            ),
            None,
        )
        if existing:
            if mbid:
                existing.setdefault("musicBrainzId", mbid)
            return existing["id"]
        artist = {
            "id": base.opaque_id("artist"),
            "slug": base.slugify(name),
            "name": name,
            "image": "",
            "heroImage": "",
            "backgroundImage": "",
            "backgroundVideoUrl": "",
            "bio": "",
            "musicBrainzId": mbid,
            "popularTrackIds": [],
            "listIds": [],
        }
        self.catalog.setdefault("artists", []).append(artist)
        return artist["id"]

    def import_from_open_metadata(self) -> None:
        query = self.open_query.text().strip()
        if not query:
            base.QMessageBox.warning(self, APP_NAME, "Albüm veya single adı gerekli.")
            return
        include_lyrics = self.open_lyrics.isChecked()

        def task(progress: Callable[[str, int], None]) -> dict[str, Any]:
            progress("MusicBrainz üzerinde yayın aranıyor…", 15)
            result = OpenMetadataClient().fetch_release(query, include_lyrics=include_lyrics)
            progress("Metadata hazır", 100)
            return result

        def done(data: dict[str, Any]) -> None:
            release_artist_ids = [self.ensure_open_artist(row) for row in data.get("artists", [])]
            imported: list[base.ImportTrack] = []
            for index, row in enumerate(data.get("tracks", [])):
                artist_ids = [self.ensure_open_artist(artist) for artist in row.get("artists", [])]
                primary = base.ordered_unique((artist_ids[:1] or release_artist_ids[:1]))
                featured = [x for x in base.ordered_unique(artist_ids[1:]) if x not in primary]
                credits = []
                names = [self.artist_name_for_id(x) for x in primary + featured]
                if names:
                    credits = [{"role": "Sanatçılar", "names": names}]
                imported.append(
                    base.ImportTrack(
                        path=Path("__AURORA_MASTER_SECILMEDI__"),
                        title=row.get("title", f"Şarkı {index + 1}"),
                        isrc=row.get("isrc", ""),
                        primary_artist_ids=primary,
                        featured_artist_ids=featured,
                        duration_seconds=int(row.get("durationSeconds", 0)),
                        disc=int(row.get("disc", 1)),
                        position=int(row.get("position", index + 1)),
                        lyrics=row.get("lyrics", ""),
                        synced_lyrics=row.get("syncedLyrics", ""),
                        credits=credits,
                    )
                )
            self.import_tracks = imported
            self.import_release_artist_ids = base.ordered_unique(release_artist_ids)
            self.import_spotify_id = f"musicbrainz:{data.get('sourceId', '')}"
            self.import_spotify_url = ""
            self.import_title.setText(data.get("title", ""))
            self.import_type.setCurrentIndex(max(0, self.import_type.findData(data.get("releaseType", "album"))))
            date = data.get("releaseDate", "")
            if date:
                parsed = QDate.fromString(date[:10], "yyyy-MM-dd")
                if not parsed.isValid() and len(date) >= 7:
                    parsed = QDate.fromString(date[:7] + "-01", "yyyy-MM-dd")
                if not parsed.isValid() and len(date) >= 4:
                    parsed = QDate.fromString(date[:4] + "-01-01", "yyyy-MM-dd")
                if parsed.isValid():
                    self.import_date.setDate(parsed)
            self.import_label.setText(data.get("label", ""))
            self.import_copyright.setText(data.get("copyright", ""))
            self.cover_url.setText(data.get("cover", ""))
            self.hero_url.setText(data.get("cover", ""))
            self.set_dirty()
            self.refresh_all_views()
            if self.import_release_artist_ids:
                index = self.import_artist.findData(self.import_release_artist_ids[0])
                if index >= 0:
                    self.import_artist.setCurrentIndex(index)
            self.refresh_import_table()
            self.open_result.setPlainText(json.dumps(data, ensure_ascii=False, indent=2))
            self.nav.setCurrentRow(1)
            self.publish_status.setText("Ücretsiz metadata hazır • ses eklemeden Yakında olarak yayınlanabilir")
            self.append_log(f"Açık kaynaklardan {len(imported)} şarkı metadata satırı alındı.")

        self.run_task(task, done, "Ücretsiz metadata içe aktarma başladı")

    def fetch_cover_to_local(self) -> None:
        url = self.cover_url.text().strip()
        if not url.startswith("https://"):
            base.QMessageBox.warning(self, APP_NAME, "Önce HTTPS kapak URL'si girin veya metadata içe aktarın.")
            return

        def task(progress: Callable[[str, int], None]) -> str:
            progress("Kapak indiriliyor…", 25)
            response = requests.get(url, timeout=45, stream=True, headers={"User-Agent": f"AuroraStudio/{APP_VERSION}"})
            response.raise_for_status()
            content_type = response.headers.get("Content-Type", "").lower()
            extension = ".png" if "png" in content_type else ".webp" if "webp" in content_type else ".jpg"
            folder = base.CONFIG_DIR / "cover-cache"
            folder.mkdir(parents=True, exist_ok=True)
            path = folder / f"cover-{base.uuid.uuid4().hex}{extension}"
            with path.open("wb") as handle:
                for chunk in response.iter_content(1024 * 256):
                    if chunk:
                        handle.write(chunk)
            progress("Kapak Hugging Face yüklemesine hazır", 100)
            return str(path)

        def done(path: str) -> None:
            self.cover_path.setText(path)
            self.publish_status.setText("Kapak indirildi • yayın sırasında Hugging Face'e kalıcı yüklenecek")
            self.append_log(f"Kapak indirildi: {path}")

        self.run_task(task, done, "Görsel Fetch başladı")

    def refresh_import_table(self) -> None:
        super().refresh_import_table()
        existing_isrc = {
            base.normalize_isrc(row.get("isrc", ""))
            for row in self.catalog.get("tracks", [])
            if base.normalize_isrc(row.get("isrc", ""))
        }
        self.import_table.blockSignals(True)
        for index, track in enumerate(self.import_tracks):
            normalized = base.normalize_isrc(track.isrc)
            if normalized and normalized in existing_isrc:
                status = "Katalogda var • yeniden yüklenmez"
            elif track.has_master:
                status = "Master hazır • kaliteler üretilecek"
            else:
                status = "Metadata hazır • Yakında"
            self.import_table.item(index, 6).setText(status)
        self.import_table.blockSignals(False)

    def build_import_request(self):
        selected_artist = self.import_artist.currentData()
        release_artist_ids = base.ordered_unique(self.import_release_artist_ids or ([selected_artist] if selected_artist else []))
        if not release_artist_ids:
            raise ValueError("Önce bir sanatçı oluşturun veya metadata içe aktarın.")
        if not self.import_title.text().strip():
            raise ValueError("Yayın adı gerekli.")
        if not self.import_tracks:
            raise ValueError("En az bir metadata şarkısı ekleyin.")
        for row in self.import_tracks:
            if not row.primary_artist_ids:
                row.primary_artist_ids = release_artist_ids.copy()
        selected = self.import_type.currentData()
        release_type = self.settings.classify_release(len(self.import_tracks)) if selected == "auto" else selected
        return base.ImportRequest(
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
            animated_cover_url=self.animated_url_v3.text().strip() if hasattr(self, "animated_url_v3") else "",
            animated_cover_path=Path(self.animated_path.text()) if self.animated_path.text() else None,
            tracks=copy.deepcopy(self.import_tracks),
            featured=self.import_featured.isChecked(),
            spotify_id=self.import_spotify_id,
            spotify_url=self.import_spotify_url,
        )

    @staticmethod
    def refresh_release_state_for(catalog: dict[str, Any], release: dict[str, Any]) -> None:
        lookup = {row.get("id"): row for row in catalog.get("tracks", [])}
        refs = release.get("tracks", [])
        available = sum(1 for ref in refs if (lookup.get(ref.get("trackId"), {}).get("sources") or []))
        total = len(refs)
        status = "published" if total and available == total else "partial" if available else "upcoming"
        release.update(status=status, availableTrackCount=available, totalTrackCount=total)

    def refresh_release_state(self, release: dict[str, Any]) -> None:
        self.refresh_release_state_for(self.catalog, release)

    def publish_release(self) -> None:
        try:
            request = self.build_import_request()
            if not self.catalog_sha:
                raise ValueError("Önce GitHub'dan katalog yükleyin.")
            self.save_settings_from_quality()
            self.settings.spotify_auto_lyrics = self.spotify_auto_lyrics.isChecked()
            self.settings.save()
        except Exception as exc:
            base.QMessageBox.warning(self, APP_NAME, str(exc))
            return
        self.publish_btn.setEnabled(False)
        snapshot = copy.deepcopy(self.catalog)
        sha = self.catalog_sha
        settings = copy.deepcopy(self.settings)

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str, int, int, int]:
            processor = base.MediaProcessor()
            storage = base.HuggingFaceStorage(settings)
            github = base.GitHubCatalogClient(settings)
            release_id = base.opaque_id("release")
            temp_root = Path(tempfile.mkdtemp(prefix="aurora-studio-v3-"))
            upload_files: list[tuple[Path, str]] = []
            new_tracks: list[dict[str, Any]] = []
            release_rows: list[dict[str, Any]] = []
            reused_count = 0
            upcoming_count = 0
            snapshot["schemaVersion"] = max(5, int(snapshot.get("schemaVersion", 1)))
            snapshot.setdefault("homeSections", [])
            snapshot.setdefault("artistLists", [])
            existing_by_isrc = {
                base.normalize_isrc(row.get("isrc", "")): row
                for row in snapshot.get("tracks", [])
                if base.normalize_isrc(row.get("isrc", ""))
            }
            try:
                cover_url = request.cover_url
                if request.cover_path and request.cover_path.is_file():
                    remote = storage.allocate_remote("artwork", request.cover_path.suffix.lower() or ".jpg", lambda status: progress(status, 4))
                    upload_files.append((request.cover_path, remote))
                    cover_url = storage.resolve_url(remote)
                local_animated_url = request.animated_cover_url
                if not local_animated_url and request.animated_cover_path and request.animated_cover_path.is_file():
                    remote = storage.allocate_remote("animated", request.animated_cover_path.suffix.lower() or ".mp4", lambda status: progress(status, 4))
                    upload_files.append((request.animated_cover_path, remote))
                    local_animated_url = storage.resolve_url(remote)
                if not cover_url:
                    raise RuntimeError("Kapak URL'si veya kapak dosyası gerekli.")

                total = len(request.tracks)
                for index, row in enumerate(request.tracks):
                    progress(f"{row.title}: yayın satırı hazırlanıyor", 5 + int((index / max(total, 1)) * 65))
                    normalized = base.normalize_isrc(row.isrc)
                    existing = existing_by_isrc.get(normalized) if normalized else None
                    if existing:
                        reused_count += 1
                        release_rows.append({"trackId": existing["id"], "disc": row.disc or 1, "position": row.position or index + 1})
                        continue
                    track_id = base.opaque_id("track")
                    sources: list[dict[str, Any]] = []
                    duration = row.duration_seconds
                    quality_state = "waiting_for_audio"
                    if row.has_master:
                        media_folder = temp_root / track_id
                        info, variants = processor.process(row.path, media_folder, settings)
                        duration = info["duration"] or duration
                        if settings.upload_master:
                            master_remote = storage.allocate_remote("masters", row.path.suffix.lower(), lambda status: progress(status, 20))
                            upload_files.append((row.path, master_remote))
                        for variant in variants:
                            remote = storage.allocate_remote("audio", variant["path"].suffix.lower(), lambda status: progress(status, 30))
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
                                "generated": True,
                            }
                            for key in ["bitrateKbps", "sampleRateKhz", "bitDepth"]:
                                if key in variant:
                                    source[key] = variant[key]
                            sources.append(source)
                        if row.atmos_path and row.atmos_path.is_file():
                            remote = storage.allocate_remote("atmos", row.atmos_path.suffix.lower(), lambda status: progress(status, 40))
                            upload_files.append((row.atmos_path, remote))
                            url = storage.resolve_url(remote)
                            sources.append(
                                {
                                    "id": base.opaque_id("audio"),
                                    "kind": "dolby_atmos",
                                    "label": "Dolby Atmos",
                                    "codec": "Dolby Atmos / E-AC-3 JOC",
                                    "url": url,
                                    "downloadUrl": url,
                                    "downloadable": True,
                                    "channels": "Çok kanallı",
                                    "spatial": True,
                                    "generated": False,
                                }
                            )
                        quality_state = "ready"
                    else:
                        upcoming_count += 1
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
                        "durationSeconds": duration,
                        "isrc": row.isrc,
                        "explicit": row.explicit,
                        "spotifyId": row.spotify_id,
                        "spotifyUrl": row.spotify_url,
                        "lyrics": row.lyrics,
                        "syncedLyrics": row.synced_lyrics,
                        "credits": row.credits,
                        "sources": sources,
                        "playable": bool(sources),
                        "availability": "available" if sources else "upcoming",
                        "qualityState": quality_state,
                    }
                    new_tracks.append(track_data)
                    if normalized:
                        existing_by_isrc[normalized] = track_data
                    release_rows.append({"trackId": track_id, "disc": row.disc or 1, "position": row.position or index + 1})

                if upload_files:
                    progress("Medya Hugging Face'e yükleniyor…", 72)
                    storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status, percent: progress(status, 72 + int(percent * 0.18)))
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
                    "animatedCoverUrl": local_animated_url,
                    "label": request.label,
                    "copyright": request.copyright_text,
                    "description": request.description,
                    "spotifyId": request.spotify_id,
                    "spotifyUrl": request.spotify_url,
                    "tracks": sorted(release_rows, key=lambda item: (item.get("disc", 1), item.get("position", 1))),
                }
                snapshot.setdefault("tracks", []).extend(new_tracks)
                snapshot.setdefault("releases", []).append(release)
                self.refresh_release_state_for(snapshot, release)
                if request.featured:
                    snapshot.setdefault("featuredReleaseIds", []).insert(0, release_id)
                progress("Katalog GitHub'a commit ediliyor…", 92)
                new_sha = github.commit_catalog(snapshot, sha, f"Aurora Music: {request.release_title} yayınını Studio v0.5 ile ekle")
                progress("Yayın tamamlandı", 100)
                return snapshot, new_sha, reused_count, len(new_tracks), upcoming_count
            finally:
                shutil.rmtree(temp_root, ignore_errors=True)

        def done(result: tuple[dict[str, Any], str, int, int, int]) -> None:
            self.catalog, self.catalog_sha, reused_count, new_count, upcoming_count = result
            self.set_dirty(False)
            self.publish_btn.setEnabled(True)
            self.publish_progress.setValue(100)
            self.publish_status.setText("Yayın başarıyla eklendi")
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
            self.animated_url_v3.clear()
            self.spotify_url_input.clear()
            self.refresh_all_views()
            summary = f"{new_count} yeni metadata satırı, {reused_count} ISRC tekrar kullanımı, {upcoming_count} Yakında parça."
            self.append_log(summary)
            base.QMessageBox.information(self, APP_NAME, "Yayın tamamlandı.\n\n" + summary)

        self.run_task(task, done, "Studio v0.3 yayın işlemi başladı")

    def validate_catalog(self) -> list[str]:
        self.ensure_v5()
        errors = super().validate_catalog()
        upcoming_titles = {
            row.get("title", "")
            for row in self.catalog.get("tracks", [])
            if not row.get("sources") and row.get("availability", "upcoming") == "upcoming"
        }
        errors = [error for error in errors if not any(error == f"{title} için ses kaynağı yok." for title in upcoming_titles)]
        artist_ids = {row.get("id") for row in self.catalog.get("artists", [])}
        track_ids = {row.get("id") for row in self.catalog.get("tracks", [])}
        release_ids = {row.get("id") for row in self.catalog.get("releases", [])}
        list_ids = {row.get("id") for row in self.catalog.get("artistLists", [])}
        for row in self.catalog.get("artistLists", []):
            if row.get("artistId") not in artist_ids:
                errors.append(f"Sanatçı listesi bilinmeyen sanatçıya bağlı: {row.get('title')}")
            for track_id in row.get("trackIds", []):
                if track_id not in track_ids:
                    errors.append(f"{row.get('title')} listesinde bilinmeyen şarkı: {track_id}")
        valid_by_key = {"releaseIds": release_ids, "artistIds": artist_ids, "trackIds": track_ids, "listIds": list_ids}
        for section in self.catalog.get("homeSections", []):
            for key, valid in valid_by_key.items():
                for item_id in section.get(key, []):
                    if item_id not in valid:
                        errors.append(f"{section.get('title')} bölümünde bilinmeyen öğe: {item_id}")
        return errors


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV3()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
