from __future__ import annotations

import copy
import difflib
import json
import re
import shutil
import sys
import tempfile
import unicodedata
from pathlib import Path
from typing import Any, Callable

import requests
from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QSplitter,
    QStackedWidget,
    QTableWidget,
    QTableWidgetItem,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

import AuroraStudio as base
import AuroraStudioV3 as v3
import AuroraStudioV3Entry as v3_entry
import AuroraStudioV4Entry as v4

APP_NAME = base.APP_NAME
APP_VERSION = "0.6.0"
base.APP_VERSION = APP_VERSION
v3.APP_VERSION = APP_VERSION
v3_entry.APP_VERSION = APP_VERSION
v4.APP_VERSION = APP_VERSION


def normalize_media_name(value: str) -> str:
    text = Path(value).stem
    text = unicodedata.normalize("NFD", text).encode("ascii", "ignore").decode("ascii")
    text = text.replace("ı", "i").lower()
    text = re.sub(r"^\s*(?:cd\s*\d+\s*[-_. ]*)?(?:track\s*)?\d{1,3}\s*[-_. )]+", "", text)
    text = re.sub(r"[\[(].*?[\])]", " ", text)
    text = re.sub(r"\b(feat|ft|featuring|remaster(?:ed)?|version|edit|mix|explicit|clean|official|audio|video|lyrics?|instrumental|master)\b.*$", " ", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def media_score(left: str, right: str) -> float:
    a, b = normalize_media_name(left), normalize_media_name(right)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if a in b or b in a:
        return 0.72 + min(len(a), len(b)) / max(len(a), len(b)) * 0.18
    at, bt = set(a.split()), set(b.split())
    union = at | bt
    token = len(at & bt) / len(union) if union else 0.0
    edit = difflib.SequenceMatcher(None, a, b).ratio()
    return token * 0.62 + edit * 0.38


def match_media(targets: list[str], files: list[Path]) -> dict[int, int]:
    unused = set(range(len(files)))
    result: dict[int, int] = {}
    target_keys = [normalize_media_name(value) for value in targets]
    file_keys = [normalize_media_name(path.name) for path in files]
    for target_index, key in enumerate(target_keys):
        exact = next((file_index for file_index in unused if file_keys[file_index] == key and key), None)
        if exact is not None:
            result[target_index] = exact
            unused.remove(exact)
    for target_index, title in enumerate(targets):
        if target_index in result:
            continue
        ranked = sorted(((file_index, media_score(title, files[file_index].name)) for file_index in unused), key=lambda row: row[1], reverse=True)
        if ranked:
            best = ranked[0]
            second = ranked[1][1] if len(ranked) > 1 else 0.0
            if best[1] >= 0.64 and best[1] - second >= 0.08:
                result[target_index] = best[0]
                unused.remove(best[0])
    remaining_targets = [index for index in range(len(targets)) if index not in result]
    for target_index, file_index in zip(remaining_targets, sorted(unused, key=lambda value: base.natural_sort_key(files[value].name))):
        result[target_index] = file_index
    return result


class AuroraStudioV6(v4.AuroraStudioV4):
    def __init__(self):
        self.library_selected_release_id = ""
        self.library_selected_track_id = ""
        self.completion_files: list[Path] = []
        self.completion_assignments: dict[str, Path] = {}
        self.completion_rows: list[tuple[str, str, str, int, int]] = []
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        if hasattr(self, "cover_fetch_btn_v3"):
            self.cover_fetch_btn_v3.hide()

    def build_ui(self) -> None:
        super().build_ui()
        old_pages = [self.pages.widget(index) for index in range(self.pages.count())]
        if len(old_pages) < 10:
            return
        library_page = self.make_library_page_v6()
        completion_page = self.make_completion_page_v6()
        desired_pages = [
            old_pages[0],
            old_pages[1],
            library_page,
            completion_page,
            old_pages[3],
            old_pages[6],
            old_pages[8],
            old_pages[9],
        ]
        while self.pages.count():
            self.pages.removeWidget(self.pages.widget(0))
        for page in desired_pages:
            self.pages.addWidget(page)
        self.nav.blockSignals(True)
        self.nav.clear()
        for title in [
            "Genel Bakış",
            "Yeni Yayın",
            "Yayın Kütüphanesi",
            "Yakında Tamamlama",
            "Sanatçılar",
            "Sunum ve Listeler",
            "Katalog JSON",
            "Ayarlar",
        ]:
            self.nav.addItem(base.QListWidgetItem(title))
        self.nav.blockSignals(False)
        try:
            self.nav.currentRowChanged.disconnect()
        except Exception:
            pass
        self.nav.currentRowChanged.connect(self.pages_set_index_v6)
        self.nav.setCurrentRow(0)

    def pages_set_index_v6(self, index: int) -> None:
        if index < 0:
            return
        self.pages.setCurrentIndex(index)
        if index == 2:
            self.refresh_library_v6()
        elif index == 3:
            self.refresh_completion_v6()
        elif index == 6:
            self.json_editor.setPlainText(json.dumps(self.catalog, ensure_ascii=False, indent=2))

    def refresh_all_views(self) -> None:
        super().refresh_all_views()
        if hasattr(self, "library_tree_v6"):
            self.refresh_library_v6()
        if hasattr(self, "completion_table_v6"):
            self.refresh_completion_v6()

    def publish_release(self) -> None:
        cover_url = self.cover_url.text().strip()
        cover_path = Path(self.cover_path.text()) if self.cover_path.text().strip() else None
        if cover_path and cover_path.is_file() or not cover_url.startswith("https://") or "huggingface.co" in cover_url.lower():
            super().publish_release()
            return
        self.publish_btn.setEnabled(False)

        def task(progress: Callable[[str, int], None]) -> str:
            progress("Spotify kapağı otomatik indiriliyor…", 20)
            response = requests.get(cover_url, timeout=45, stream=True, headers={"User-Agent": f"AuroraStudio/{APP_VERSION}"})
            response.raise_for_status()
            content_type = response.headers.get("Content-Type", "").lower()
            suffix = ".png" if "png" in content_type else ".webp" if "webp" in content_type else ".jpg"
            folder = base.CONFIG_DIR / "cover-cache"
            folder.mkdir(parents=True, exist_ok=True)
            target = folder / f"spotify-cover-{base.uuid.uuid4().hex}{suffix}"
            with target.open("wb") as output:
                for chunk in response.iter_content(1024 * 256):
                    if chunk:
                        output.write(chunk)
            progress("Kapak Hugging Face yüklemesine hazır", 100)
            return str(target)

        def done(path: str) -> None:
            self.cover_path.setText(path)
            self.publish_btn.setEnabled(True)
            super(AuroraStudioV6, self).publish_release()

        self.run_task(task, done, "Spotify kapağı otomatik kalıcılaştırılıyor")

    def make_library_page_v6(self) -> QWidget:
        page, layout = self.page_container(
            "Yayın Kütüphanesi",
            "Yayınlanmış albüm, single ve içlerindeki şarkıları tek ekrandan düzenleyin veya kaldırın.",
        )
        splitter = QSplitter()
        left = QWidget()
        left_layout = QVBoxLayout(left)
        self.library_tree_v6 = QTreeWidget()
        self.library_tree_v6.setHeaderLabels(["Yayın / Şarkı", "Durum"])
        self.library_tree_v6.setAlternatingRowColors(True)
        self.library_tree_v6.itemSelectionChanged.connect(self.library_selection_changed_v6)
        left_layout.addWidget(self.library_tree_v6, 1)
        refresh = QPushButton("Kütüphaneyi Yenile")
        refresh.clicked.connect(self.refresh_library_v6)
        left_layout.addWidget(refresh)

        self.library_forms_v6 = QStackedWidget()
        self.library_forms_v6.addWidget(self.make_library_empty_v6())
        self.library_forms_v6.addWidget(self.make_release_editor_v6())
        self.library_forms_v6.addWidget(self.make_track_editor_v6())
        splitter.addWidget(left)
        splitter.addWidget(self.library_forms_v6)
        splitter.setStretchFactor(0, 2)
        splitter.setStretchFactor(1, 3)
        layout.addWidget(splitter, 1)
        return page

    def make_library_empty_v6(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.addStretch()
        text = QLabel("Soldan bir albüm/single veya şarkı seçin.")
        text.setAlignment(Qt.AlignCenter)
        text.setObjectName("muted")
        layout.addWidget(text)
        layout.addStretch()
        return page

    def make_release_editor_v6(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        group = QGroupBox("Yayın Bilgileri")
        form = QFormLayout(group)
        self.lib_release_title = QLineEdit()
        self.lib_release_type = QComboBox()
        for label, value in [("Single", "single"), ("Maxi Single", "maxi_single"), ("EP", "ep"), ("Albüm", "album")]:
            self.lib_release_type.addItem(label, value)
        self.lib_release_date = QLineEdit()
        self.lib_release_cover = QLineEdit()
        self.lib_release_animated = QLineEdit()
        self.lib_release_label = QLineEdit()
        self.lib_release_copyright = QLineEdit()
        self.lib_release_description = QPlainTextEdit()
        self.lib_release_description.setMaximumHeight(110)
        form.addRow("Yayın adı", self.lib_release_title)
        form.addRow("Tür", self.lib_release_type)
        form.addRow("Yayın tarihi", self.lib_release_date)
        form.addRow("Normal kapak", self.lib_release_cover)
        form.addRow("Hareketli URL • isteğe bağlı", self.lib_release_animated)
        form.addRow("Label", self.lib_release_label)
        form.addRow("Telif", self.lib_release_copyright)
        form.addRow("Açıklama", self.lib_release_description)
        layout.addWidget(group)
        save = QPushButton("Yayın Bilgilerini Güncelle")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_library_release_v6)
        layout.addWidget(save)
        delete_row = QHBoxLayout()
        delete_keep = QPushButton("Yayını Sil • Şarkıları Koru")
        delete_keep.clicked.connect(lambda: self.delete_library_release_v6(False))
        delete_all = QPushButton("Yayın + Yetim Şarkıları Sil")
        delete_all.clicked.connect(lambda: self.delete_library_release_v6(True))
        delete_row.addWidget(delete_keep)
        delete_row.addWidget(delete_all)
        layout.addLayout(delete_row)
        note = QLabel("Hareketli URL boşsa uygulama normal kapağı gösterir. Hugging Face geçmiş medya dosyaları katalog silme işleminde otomatik silinmez.")
        note.setWordWrap(True)
        note.setObjectName("muted")
        layout.addWidget(note)
        layout.addStretch()
        return page

    def make_track_editor_v6(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        group = QGroupBox("Şarkı Bilgileri")
        form = QFormLayout(group)
        self.lib_track_title = QLineEdit()
        self.lib_track_isrc = QLineEdit()
        self.lib_track_explicit = QCheckBox("Explicit • uygulamada E göster")
        self.lib_track_featured_names = QLineEdit()
        self.lib_track_lyrics = QPlainTextEdit()
        self.lib_track_lyrics.setMaximumHeight(150)
        self.lib_track_synced = QPlainTextEdit()
        self.lib_track_synced.setMaximumHeight(150)
        self.lib_track_credits = QPlainTextEdit()
        self.lib_track_credits.setMaximumHeight(110)
        self.lib_track_status = QLabel()
        self.lib_track_status.setObjectName("muted")
        form.addRow("Şarkı adı", self.lib_track_title)
        form.addRow("ISRC", self.lib_track_isrc)
        form.addRow("Serbest feat isimleri", self.lib_track_featured_names)
        form.addRow("", self.lib_track_explicit)
        form.addRow("Düz sözler", self.lib_track_lyrics)
        form.addRow("Senkronize LRC", self.lib_track_synced)
        form.addRow("Künye JSON", self.lib_track_credits)
        form.addRow("Durum", self.lib_track_status)
        layout.addWidget(group)
        save = QPushButton("Şarkıyı Güncelle")
        save.setObjectName("primaryButton")
        save.clicked.connect(self.save_library_track_v6)
        layout.addWidget(save)
        audio = QPushButton("Bu Şarkıya Ses Ekle / Değiştir")
        audio.clicked.connect(self.open_selected_track_in_completion_v6)
        layout.addWidget(audio)
        row = QHBoxLayout()
        remove = QPushButton("Yalnız Bu Yayından Çıkar")
        remove.clicked.connect(self.remove_library_track_from_release_v6)
        delete = QPushButton("Şarkıyı Tamamen Sil")
        delete.clicked.connect(self.delete_library_track_v6)
        row.addWidget(remove)
        row.addWidget(delete)
        layout.addLayout(row)
        layout.addStretch()
        return page

    def refresh_library_v6(self) -> None:
        if not hasattr(self, "library_tree_v6"):
            return
        self.library_tree_v6.blockSignals(True)
        self.library_tree_v6.clear()
        lookup = {row.get("id"): row for row in self.catalog.get("tracks", [])}
        for release in sorted(self.catalog.get("releases", []), key=lambda row: row.get("releaseDate", ""), reverse=True):
            status = self.release_state_v6(release, lookup)
            release_item = QTreeWidgetItem([release.get("title", "Adsız Yayın"), self.status_label_v6(status)])
            release_item.setData(0, Qt.UserRole, ("release", release.get("id"), ""))
            self.library_tree_v6.addTopLevelItem(release_item)
            for ref in sorted(release.get("tracks", []), key=lambda row: (row.get("disc", 1), row.get("position", 1))):
                track = lookup.get(ref.get("trackId"))
                if not track:
                    continue
                playable = bool(track.get("sources")) and bool(track.get("playable", True))
                child = QTreeWidgetItem([track.get("title", "Adsız Şarkı"), "Yayında" if playable else "Yakında"])
                child.setData(0, Qt.UserRole, ("track", track.get("id"), release.get("id")))
                release_item.addChild(child)
            release_item.setExpanded(release.get("id") == self.library_selected_release_id)
        self.library_tree_v6.blockSignals(False)
        self.library_tree_v6.resizeColumnToContents(0)

    def library_selection_changed_v6(self) -> None:
        item = self.library_tree_v6.currentItem()
        if not item:
            self.library_forms_v6.setCurrentIndex(0)
            return
        data = item.data(0, Qt.UserRole)
        if not data:
            return
        kind, item_id, release_id = data
        if kind == "release":
            self.library_selected_release_id = item_id
            self.library_selected_track_id = ""
            release = self.find_by_id("releases", item_id)
            if not release:
                return
            self.lib_release_title.setText(release.get("title", ""))
            self.lib_release_type.setCurrentIndex(max(0, self.lib_release_type.findData(release.get("type", "album"))))
            self.lib_release_date.setText(release.get("releaseDate", ""))
            self.lib_release_cover.setText(release.get("cover", ""))
            self.lib_release_animated.setText(release.get("animatedCoverUrl", ""))
            self.lib_release_label.setText(release.get("label", ""))
            self.lib_release_copyright.setText(release.get("copyright", ""))
            self.lib_release_description.setPlainText(release.get("description", ""))
            self.library_forms_v6.setCurrentIndex(1)
        else:
            self.library_selected_release_id = release_id
            self.library_selected_track_id = item_id
            track = self.find_by_id("tracks", item_id)
            if not track:
                return
            self.lib_track_title.setText(track.get("title", ""))
            self.lib_track_isrc.setText(track.get("isrc", ""))
            self.lib_track_explicit.setChecked(bool(track.get("explicit", False)))
            self.lib_track_featured_names.setText(", ".join(track.get("featuredArtistNames", [])))
            self.lib_track_lyrics.setPlainText(track.get("lyrics", ""))
            self.lib_track_synced.setPlainText(track.get("syncedLyrics", ""))
            self.lib_track_credits.setPlainText(json.dumps(track.get("credits", []), ensure_ascii=False, indent=2))
            self.lib_track_status.setText("Yayında" if track.get("sources") else "Yakında • ses dosyası bekliyor")
            self.library_forms_v6.setCurrentIndex(2)

    def commit_mutation_v6(self, message: str, mutate: Callable[[dict[str, Any]], None]) -> None:
        if not self.catalog_sha:
            QMessageBox.warning(self, APP_NAME, "Önce GitHub'dan kataloğu yükleyin.")
            return
        snapshot = copy.deepcopy(self.catalog)
        sha = self.catalog_sha
        settings = copy.deepcopy(self.settings)

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str]:
            progress("Katalog hazırlanıyor…", 20)
            mutate(snapshot)
            self.refresh_all_release_states_v6(snapshot)
            progress("GitHub'a commit ediliyor…", 75)
            new_sha = base.GitHubCatalogClient(settings).commit_catalog(snapshot, sha, message)
            progress("Tamamlandı", 100)
            return snapshot, new_sha

        def done(result: tuple[dict[str, Any], str]) -> None:
            self.catalog, self.catalog_sha = result
            self.set_dirty(False)
            self.refresh_all_views()
            self.append_log(message)

        self.run_task(task, done, message)

    def save_library_release_v6(self) -> None:
        release_id = self.library_selected_release_id
        title = self.lib_release_title.text().strip()
        if not release_id or not title:
            return

        def mutate(catalog: dict[str, Any]) -> None:
            release = next(row for row in catalog.get("releases", []) if row.get("id") == release_id)
            release.update(
                title=title,
                slug=base.slugify(title),
                type=self.lib_release_type.currentData(),
                releaseDate=self.lib_release_date.text().strip(),
                cover=self.lib_release_cover.text().strip(),
                heroImage=self.lib_release_cover.text().strip(),
                animatedCoverUrl=self.lib_release_animated.text().strip(),
                label=self.lib_release_label.text().strip(),
                copyright=self.lib_release_copyright.text().strip(),
                description=self.lib_release_description.toPlainText(),
            )

        self.commit_mutation_v6(f"Aurora Music: {title} yayınını güncelle", mutate)

    def save_library_track_v6(self) -> None:
        track_id = self.library_selected_track_id
        title = self.lib_track_title.text().strip()
        if not track_id or not title:
            return
        try:
            credits = json.loads(self.lib_track_credits.toPlainText().strip() or "[]")
            if not isinstance(credits, list):
                raise ValueError("Künye JSON dizisi olmalıdır.")
        except Exception as exc:
            QMessageBox.warning(self, APP_NAME, f"Künye JSON hatası: {exc}")
            return

        def mutate(catalog: dict[str, Any]) -> None:
            track = next(row for row in catalog.get("tracks", []) if row.get("id") == track_id)
            track.update(
                title=title,
                slug=base.slugify(title),
                isrc=self.lib_track_isrc.text().strip(),
                explicit=self.lib_track_explicit.isChecked(),
                featuredArtistNames=base.ordered_unique([value.strip() for value in self.lib_track_featured_names.text().split(",") if value.strip()]),
                lyrics=self.lib_track_lyrics.toPlainText(),
                syncedLyrics=self.lib_track_synced.toPlainText(),
                credits=credits,
            )

        self.commit_mutation_v6(f"Aurora Music: {title} şarkısını güncelle", mutate)

    def remove_library_track_from_release_v6(self) -> None:
        release_id, track_id = self.library_selected_release_id, self.library_selected_track_id
        if not release_id or not track_id:
            return
        track = self.find_by_id("tracks", track_id) or {}
        if QMessageBox.question(self, APP_NAME, f"{track.get('title', 'Şarkı')} yalnız bu yayından çıkarılsın mı? Katalog kaydı korunur.") != QMessageBox.Yes:
            return

        def mutate(catalog: dict[str, Any]) -> None:
            release = next(row for row in catalog.get("releases", []) if row.get("id") == release_id)
            release["tracks"] = [row for row in release.get("tracks", []) if row.get("trackId") != track_id]

        self.commit_mutation_v6("Aurora Music: şarkıyı yayından çıkar", mutate)

    def delete_library_track_v6(self) -> None:
        track_id = self.library_selected_track_id
        track = self.find_by_id("tracks", track_id)
        if not track:
            return
        if QMessageBox.question(self, APP_NAME, f"{track.get('title')} bütün yayınlardan ve katalogdan tamamen silinsin mi?\n\nHugging Face geçmiş dosyaları otomatik silinmez.") != QMessageBox.Yes:
            return

        def mutate(catalog: dict[str, Any]) -> None:
            catalog["tracks"] = [row for row in catalog.get("tracks", []) if row.get("id") != track_id]
            for release in catalog.get("releases", []):
                release["tracks"] = [row for row in release.get("tracks", []) if row.get("trackId") != track_id]
            for artist_list in catalog.get("artistLists", []):
                artist_list["trackIds"] = [value for value in artist_list.get("trackIds", []) if value != track_id]
            for section in catalog.get("homeSections", []):
                section["trackIds"] = [value for value in section.get("trackIds", []) if value != track_id]
            catalog["qualityJobs"] = [row for row in catalog.get("qualityJobs", []) if row.get("trackId") != track_id]

        self.commit_mutation_v6(f"Aurora Music: {track.get('title')} şarkısını tamamen sil", mutate)

    def delete_library_release_v6(self, delete_orphans: bool) -> None:
        release_id = self.library_selected_release_id
        release = self.find_by_id("releases", release_id)
        if not release:
            return
        action = "ve başka yayında kullanılmayan şarkılarıyla birlikte" if delete_orphans else "şarkı kayıtları korunarak"
        if QMessageBox.question(self, APP_NAME, f"{release.get('title')} {action} silinsin mi?") != QMessageBox.Yes:
            return

        def mutate(catalog: dict[str, Any]) -> None:
            target = next(row for row in catalog.get("releases", []) if row.get("id") == release_id)
            target_ids = {row.get("trackId") for row in target.get("tracks", [])}
            remaining = [row for row in catalog.get("releases", []) if row.get("id") != release_id]
            catalog["releases"] = remaining
            catalog["featuredReleaseIds"] = [value for value in catalog.get("featuredReleaseIds", []) if value != release_id]
            for section in catalog.get("homeSections", []):
                section["releaseIds"] = [value for value in section.get("releaseIds", []) if value != release_id]
            if delete_orphans:
                still_used = {ref.get("trackId") for row in remaining for ref in row.get("tracks", [])}
                deleted = target_ids - still_used
                catalog["tracks"] = [row for row in catalog.get("tracks", []) if row.get("id") not in deleted]
                catalog["qualityJobs"] = [row for row in catalog.get("qualityJobs", []) if row.get("trackId") not in deleted]
                for artist_list in catalog.get("artistLists", []):
                    artist_list["trackIds"] = [value for value in artist_list.get("trackIds", []) if value not in deleted]

        self.commit_mutation_v6(f"Aurora Music: {release.get('title')} yayınını sil", mutate)

    def open_selected_track_in_completion_v6(self) -> None:
        self.nav.setCurrentRow(3)
        self.refresh_completion_v6()
        if self.library_selected_track_id:
            for row in range(self.completion_table_v6.rowCount()):
                if self.completion_table_v6.item(row, 0).data(Qt.UserRole) == self.library_selected_track_id:
                    self.completion_table_v6.selectRow(row)
                    break

    def make_completion_page_v6(self) -> QWidget:
        page, layout = self.page_container(
            "Yakında Tamamlama",
            "Yeni release oluşturmadan mevcut Yakında şarkılara toplu ses veya TXT/LRC dosyası eşleştirin.",
        )
        controls = QHBoxLayout()
        self.completion_mode_v6 = QComboBox()
        self.completion_mode_v6.addItem("Ses dosyaları", "audio")
        self.completion_mode_v6.addItem("Söz dosyaları TXT/LRC", "lyrics")
        choose = QPushButton("Dosyaları Toplu Seç")
        choose.clicked.connect(self.choose_completion_files_v6)
        folder = QPushButton("Klasör Seç")
        folder.clicked.connect(self.choose_completion_folder_v6)
        auto = QPushButton("İsimle Eşleştir + Sıra")
        auto.clicked.connect(self.auto_match_completion_v6)
        order = QPushButton("Yalnız Albüm Sırası")
        order.clicked.connect(self.order_match_completion_v6)
        controls.addWidget(self.completion_mode_v6)
        controls.addWidget(choose)
        controls.addWidget(folder)
        controls.addWidget(auto)
        controls.addWidget(order)
        controls.addStretch()
        layout.addLayout(controls)
        self.completion_table_v6 = QTableWidget(0, 6)
        self.completion_table_v6.setHorizontalHeaderLabels(["#", "Yayın", "Şarkı", "ISRC", "Dosya Eşleştirme", "Durum"])
        self.completion_table_v6.horizontalHeader().setSectionResizeMode(0, base.QHeaderView.ResizeToContents)
        self.completion_table_v6.horizontalHeader().setSectionResizeMode(1, base.QHeaderView.Stretch)
        self.completion_table_v6.horizontalHeader().setSectionResizeMode(2, base.QHeaderView.Stretch)
        self.completion_table_v6.horizontalHeader().setSectionResizeMode(3, base.QHeaderView.ResizeToContents)
        self.completion_table_v6.horizontalHeader().setSectionResizeMode(4, base.QHeaderView.Stretch)
        self.completion_table_v6.horizontalHeader().setSectionResizeMode(5, base.QHeaderView.ResizeToContents)
        layout.addWidget(self.completion_table_v6, 1)
        submit = QPushButton("Eşleşen Dosyaları Mevcut Şarkılara Uygula")
        submit.setObjectName("primaryButton")
        submit.clicked.connect(self.submit_completion_v6)
        layout.addWidget(submit)
        note = QLabel("Aynı dosya iki şarkıya atanamaz. İsim eşleştirmesi güvenli değilse kalan dosyalar albüm sırasına göre yerleştirilir; her satırdaki açılır listeden elle değiştirebilirsiniz.")
        note.setWordWrap(True)
        note.setObjectName("muted")
        layout.addWidget(note)
        return page

    def pending_tracks_v6(self) -> list[tuple[str, str, str, int, int]]:
        lookup = {row.get("id"): row for row in self.catalog.get("tracks", [])}
        result: list[tuple[str, str, str, int, int]] = []
        for release in self.catalog.get("releases", []):
            for ref in sorted(release.get("tracks", []), key=lambda row: (row.get("disc", 1), row.get("position", 1))):
                track = lookup.get(ref.get("trackId"))
                if not track or track.get("sources"):
                    continue
                result.append((track.get("id"), release.get("title", "Yayın"), track.get("title", "Şarkı"), ref.get("disc", 1), ref.get("position", 1)))
        seen: set[str] = set()
        return [row for row in result if not (row[0] in seen or seen.add(row[0]))]

    def refresh_completion_v6(self) -> None:
        if not hasattr(self, "completion_table_v6"):
            return
        self.completion_rows = self.pending_tracks_v6()
        self.completion_table_v6.setRowCount(len(self.completion_rows))
        for row_index, (track_id, release_title, track_title, disc, position) in enumerate(self.completion_rows):
            values = [str(row_index + 1), release_title, track_title, (self.find_by_id("tracks", track_id) or {}).get("isrc", "")]
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setFlags(item.flags() & ~Qt.ItemIsEditable)
                if column == 0:
                    item.setData(Qt.UserRole, track_id)
                self.completion_table_v6.setItem(row_index, column, item)
            combo = QComboBox()
            combo.addItem("— Eşlenmedi —", "")
            for path in self.completion_files:
                combo.addItem(path.name, str(path))
            assigned = self.completion_assignments.get(track_id)
            if assigned:
                combo.setCurrentIndex(max(0, combo.findData(str(assigned))))
            combo.currentIndexChanged.connect(lambda _value, tid=track_id, widget=combo: self.set_completion_assignment_v6(tid, widget.currentData()))
            self.completion_table_v6.setCellWidget(row_index, 4, combo)
            status = "Eşlendi" if assigned else "Ses bekliyor"
            self.completion_table_v6.setItem(row_index, 5, QTableWidgetItem(status))

    def set_completion_assignment_v6(self, track_id: str, path_value: str) -> None:
        if path_value:
            self.completion_assignments[track_id] = Path(path_value)
        else:
            self.completion_assignments.pop(track_id, None)
        for row in range(self.completion_table_v6.rowCount()):
            if self.completion_table_v6.item(row, 0).data(Qt.UserRole) == track_id:
                self.completion_table_v6.setItem(row, 5, QTableWidgetItem("Eşlendi" if path_value else "Bekliyor"))
                break

    def choose_completion_files_v6(self) -> None:
        mode = self.completion_mode_v6.currentData()
        filter_text = "Ses (*.flac *.wav *.m4a *.mp3 *.aiff *.alac *.ogg *.opus)" if mode == "audio" else "Söz (*.txt *.lrc)"
        files, _ = QFileDialog.getOpenFileNames(self, "Toplu Dosya Seç", "", filter_text)
        if files:
            self.completion_files = sorted([Path(value) for value in files], key=lambda path: base.natural_sort_key(path.name))
            self.auto_match_completion_v6()

    def choose_completion_folder_v6(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "Dosyaların Bulunduğu Klasörü Seç")
        if not folder:
            return
        mode = self.completion_mode_v6.currentData()
        extensions = {".flac", ".wav", ".m4a", ".mp3", ".aiff", ".alac", ".ogg", ".opus"} if mode == "audio" else {".txt", ".lrc"}
        self.completion_files = sorted([path for path in Path(folder).iterdir() if path.is_file() and path.suffix.lower() in extensions], key=lambda path: base.natural_sort_key(path.name))
        self.auto_match_completion_v6()

    def auto_match_completion_v6(self) -> None:
        matches = match_media([row[2] for row in self.completion_rows], self.completion_files)
        self.completion_assignments = {self.completion_rows[target][0]: self.completion_files[file_index] for target, file_index in matches.items()}
        self.refresh_completion_v6()

    def order_match_completion_v6(self) -> None:
        self.completion_assignments = {row[0]: path for row, path in zip(self.completion_rows, self.completion_files)}
        self.refresh_completion_v6()

    def submit_completion_v6(self) -> None:
        assignments = {track_id: path for track_id, path in self.completion_assignments.items() if path.is_file()}
        if not assignments:
            QMessageBox.warning(self, APP_NAME, "En az bir dosyayı bir şarkıyla eşleyin.")
            return
        if len(set(assignments.values())) != len(assignments):
            QMessageBox.warning(self, APP_NAME, "Aynı dosya birden fazla şarkıya atanamaz.")
            return
        if not self.catalog_sha:
            QMessageBox.warning(self, APP_NAME, "Önce kataloğu yükleyin.")
            return
        mode = self.completion_mode_v6.currentData()
        snapshot = copy.deepcopy(self.catalog)
        sha = self.catalog_sha
        settings = copy.deepcopy(self.settings)

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str]:
            lookup = {row.get("id"): row for row in snapshot.get("tracks", [])}
            if mode == "lyrics":
                for index, (track_id, path) in enumerate(assignments.items()):
                    text = path.read_text(encoding="utf-8-sig", errors="replace")
                    lookup[track_id]["syncedLyrics" if path.suffix.lower() == ".lrc" else "lyrics"] = text
                    progress(f"Söz {index + 1}/{len(assignments)} eşlendi", 20 + int((index + 1) / len(assignments) * 50))
            else:
                processor = base.MediaProcessor()
                storage = base.HuggingFaceStorage(settings)
                temp_root = Path(tempfile.mkdtemp(prefix="aurora-completion-"))
                upload_files: list[tuple[Path, str]] = []
                try:
                    for index, (track_id, path) in enumerate(assignments.items()):
                        track = lookup[track_id]
                        progress(f"{track.get('title')}: ses işleniyor", 5 + int(index / len(assignments) * 55))
                        info, variants = processor.process(path, temp_root / track_id, settings)
                        sources: list[dict[str, Any]] = []
                        if settings.upload_master:
                            remote = storage.allocate_remote("masters", path.suffix.lower(), lambda status: progress(status, 15))
                            upload_files.append((path, remote))
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
                                "channels": f"{info['channels']} kanal" if info.get("channels") else "Stereo",
                                "spatial": False,
                                "generated": True,
                            }
                            for key in ["bitrateKbps", "sampleRateKhz", "bitDepth"]:
                                if key in variant:
                                    source[key] = variant[key]
                            sources.append(source)
                        track.update(
                            sources=sources,
                            playable=bool(sources),
                            availability="available" if sources else "upcoming",
                            qualityState="ready" if sources else "waiting_for_audio",
                            durationSeconds=info.get("duration") or track.get("durationSeconds", 0),
                        )
                    if upload_files:
                        progress("Dosyalar Hugging Face'e yükleniyor…", 68)
                        storage.create_commit(upload_files, "Aurora Music: Yakında şarkıları tamamla", progress=lambda text, percent: progress(text, 68 + int(percent * .22)))
                finally:
                    shutil.rmtree(temp_root, ignore_errors=True)
            self.refresh_all_release_states_v6(snapshot)
            progress("GitHub kataloğu commit ediliyor…", 94)
            new_sha = base.GitHubCatalogClient(settings).commit_catalog(snapshot, sha, "Aurora Music: Yakında şarkıları toplu tamamla")
            progress("Tamamlandı", 100)
            return snapshot, new_sha

        def done(result: tuple[dict[str, Any], str]) -> None:
            self.catalog, self.catalog_sha = result
            self.completion_files.clear()
            self.completion_assignments.clear()
            self.set_dirty(False)
            self.refresh_all_views()
            QMessageBox.information(self, APP_NAME, "Dosyalar mevcut şarkılara bağlandı. Yeni release oluşturulmadı.")

        self.run_task(task, done, "Yakında şarkılar tamamlanıyor")

    @staticmethod
    def status_label_v6(value: str) -> str:
        return {"published": "Yayında", "partial": "Kısmen Yayında", "upcoming": "Yakında"}.get(value, value)

    @staticmethod
    def release_state_v6(release: dict[str, Any], lookup: dict[str, dict[str, Any]]) -> str:
        refs = release.get("tracks", [])
        available = sum(1 for ref in refs if lookup.get(ref.get("trackId"), {}).get("sources"))
        return "published" if refs and available == len(refs) else "partial" if available else "upcoming"

    def refresh_all_release_states_v6(self, catalog: dict[str, Any]) -> None:
        lookup = {row.get("id"): row for row in catalog.get("tracks", [])}
        for release in catalog.get("releases", []):
            refs = release.get("tracks", [])
            available = sum(1 for ref in refs if lookup.get(ref.get("trackId"), {}).get("sources"))
            release["status"] = "published" if refs and available == len(refs) else "partial" if available else "upcoming"
            release["availableTrackCount"] = available
            release["totalTrackCount"] = len(refs)


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV6()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
