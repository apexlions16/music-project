from __future__ import annotations

import copy
import re
import sys
from pathlib import Path
from typing import Any, Callable

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QKeySequence, QPalette, QShortcut
from PySide6.QtWidgets import QApplication, QFileDialog, QFormLayout, QLabel, QLineEdit, QMessageBox, QPushButton

import AuroraStudio as base
import AuroraStudioV3 as v3
import AuroraStudioV3Entry as v3_entry
import AuroraStudioV4Entry as v4
import AuroraStudioV6Entry as v6
from AuroraStudioV6Final import AuroraStudioV6Final

APP_NAME = base.APP_NAME
APP_VERSION = "0.7.0"
base.APP_VERSION = APP_VERSION
v3.APP_VERSION = APP_VERSION
v3_entry.APP_VERSION = APP_VERSION
v4.APP_VERSION = APP_VERSION
v6.APP_VERSION = APP_VERSION

_TIMESTAMP = re.compile(r"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]")


def _timestamp_ms(match: re.Match[str]) -> int:
    minute = int(match.group(1) or 0)
    second = int(match.group(2) or 0)
    fraction = match.group(3) or ""
    if not fraction:
        ms = 0
    elif len(fraction) == 1:
        ms = int(fraction) * 100
    elif len(fraction) == 2:
        ms = int(fraction) * 10
    else:
        ms = int((fraction[:3]).ljust(3, "0"))
    return (minute * 60 + second) * 1000 + ms


def normalize_lrc_v7(raw: str) -> str:
    text = raw.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    matches = list(_TIMESTAMP.finditer(text))
    if not matches:
        raise ValueError("LRC dosyasında [dakika:saniye.salise] zaman kodu bulunamadı.")
    pending: list[int] = []
    rows: list[tuple[int, str]] = []
    for index, match in enumerate(matches):
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        lyric = text[start:end].replace("\n", " ").strip()
        time_ms = _timestamp_ms(match)
        if not lyric:
            pending.append(time_ms)
            continue
        for value in pending + [time_ms]:
            rows.append((value, lyric))
        pending.clear()
    rows = sorted(set(rows), key=lambda row: row[0])
    if not rows:
        raise ValueError("LRC zaman kodları bulundu fakat söz satırı bulunamadı.")
    return "\n".join(
        f"[{time_ms // 60000:02d}:{(time_ms // 1000) % 60:02d}.{(time_ms % 1000) // 10:02d}]{lyric}"
        for time_ms, lyric in rows
    )


class AuroraStudioV7Final(AuroraStudioV6Final):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self._install_library_search_v7()
        self._install_lrc_picker_v7()
        self.completion_mode_v6.currentIndexChanged.connect(lambda _index: self.refresh_completion_v6())
        self.refresh_all_views()

    def _install_library_search_v7(self) -> None:
        if hasattr(self, "library_search_v7"):
            return
        self.library_search_v7 = QLineEdit()
        self.library_search_v7.setPlaceholderText("Şarkı, ISRC, sanatçı veya yayın ara…")
        self.library_search_v7.setClearButtonEnabled(True)
        self.library_search_v7.textChanged.connect(self.filter_library_v7)
        parent = self.library_tree_v6.parentWidget()
        layout = parent.layout() if parent else None
        if layout is not None:
            layout.insertWidget(0, self.library_search_v7)
        shortcut = QShortcut(QKeySequence.Find, self)
        shortcut.activated.connect(self._focus_library_search_v7)
        self._library_search_shortcut_v7 = shortcut

    def _focus_library_search_v7(self) -> None:
        self.nav.setCurrentRow(2)
        self.library_search_v7.setFocus()
        self.library_search_v7.selectAll()

    def _install_lrc_picker_v7(self) -> None:
        if hasattr(self, "lrc_picker_v7"):
            return
        self.lrc_picker_v7 = QPushButton("LRC Dosyası Seç ve Doğrula")
        self.lrc_picker_v7.clicked.connect(self.choose_lrc_file_v7)
        note = QLabel("Boşluklu veya tek satıra birleşmiş [00:00.00] sözleri otomatik ayrılır. Her zaman kodu yeni satırda kaydedilir.")
        note.setWordWrap(True)
        note.setObjectName("muted")
        parent = self.lib_track_synced.parentWidget()
        form = parent.layout() if parent else None
        if isinstance(form, QFormLayout):
            form.addRow("LRC dosyası", self.lrc_picker_v7)
            form.addRow("", note)

    def choose_lrc_file_v7(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Senkronize LRC seç", "", "LRC Dosyaları (*.lrc);;Metin Dosyaları (*.txt);;Tüm Dosyalar (*.*)")
        if not path:
            return
        try:
            raw = Path(path).read_text(encoding="utf-8-sig")
            normalized = normalize_lrc_v7(raw)
            self.lib_track_synced.setPlainText(normalized)
            QMessageBox.information(self, APP_NAME, f"LRC doğrulandı ve {len(normalized.splitlines())} zamanlı satır yüklendi.")
        except Exception as exc:
            QMessageBox.warning(self, APP_NAME, f"LRC dosyası okunamadı:\n{exc}")

    def refresh_library_v6(self) -> None:
        super().refresh_library_v6()
        if hasattr(self, "library_search_v7"):
            self.filter_library_v7(self.library_search_v7.text())

    def filter_library_v7(self, query: str) -> None:
        if not hasattr(self, "library_tree_v6"):
            return
        needle = query.strip().casefold()
        for release_index in range(self.library_tree_v6.topLevelItemCount()):
            release_item = self.library_tree_v6.topLevelItem(release_index)
            release_data = release_item.data(0, Qt.UserRole) or ("", "", "")
            release = self.find_by_id("releases", release_data[1]) or {}
            release_match = not needle or needle in " ".join(
                [release.get("title", ""), release.get("label", ""), release.get("releaseDate", "")]
            ).casefold()
            child_match_found = False
            for child_index in range(release_item.childCount()):
                child = release_item.child(child_index)
                data = child.data(0, Qt.UserRole) or ("", "", "")
                track = self.find_by_id("tracks", data[1]) or {}
                artist_ids = track.get("primaryArtistIds") or track.get("artistIds", [])
                artist_names = [self.artist_name_for_id(value) for value in artist_ids]
                haystack = " ".join(
                    [
                        track.get("title", ""),
                        track.get("isrc", ""),
                        *artist_names,
                        *track.get("featuredArtistNames", []),
                        release.get("title", ""),
                    ]
                ).casefold()
                matched = not needle or needle in haystack
                child.setHidden(not matched and not release_match)
                child_match_found = child_match_found or matched
            visible = release_match or child_match_found
            release_item.setHidden(not visible)
            if needle and visible:
                release_item.setExpanded(True)

    def save_library_track_v6(self) -> None:
        synced = self.lib_track_synced.toPlainText().strip()
        if synced:
            try:
                self.lib_track_synced.setPlainText(normalize_lrc_v7(synced))
            except Exception as exc:
                QMessageBox.warning(self, APP_NAME, f"Senkronize LRC geçersiz:\n{exc}")
                return
        super().save_library_track_v6()

    def pending_tracks_v6(self) -> list[tuple[str, str, str, int, int]]:
        if not hasattr(self, "completion_mode_v6") or self.completion_mode_v6.currentData() != "lyrics":
            return super().pending_tracks_v6()
        lookup = {row.get("id"): row for row in self.catalog.get("tracks", [])}
        result: list[tuple[str, str, str, int, int]] = []
        for release in self.catalog.get("releases", []):
            for ref in sorted(release.get("tracks", []), key=lambda row: (row.get("disc", 1), row.get("position", 1))):
                track = lookup.get(ref.get("trackId"))
                if not track:
                    continue
                result.append((track.get("id"), release.get("title", "Yayın"), track.get("title", "Şarkı"), ref.get("disc", 1), ref.get("position", 1)))
        seen: set[str] = set()
        return [row for row in result if not (row[0] in seen or seen.add(row[0]))]

    def submit_completion_v6(self) -> None:
        if self.completion_mode_v6.currentData() != "lyrics":
            super().submit_completion_v6()
            return
        assignments = {track_id: path for track_id, path in self.completion_assignments.items() if path.is_file()}
        if not assignments:
            QMessageBox.warning(self, APP_NAME, "En az bir LRC/TXT dosyasını bir şarkıyla eşleyin.")
            return
        if len(set(assignments.values())) != len(assignments):
            QMessageBox.warning(self, APP_NAME, "Aynı dosya birden fazla şarkıya atanamaz.")
            return
        if not self.catalog_sha:
            QMessageBox.warning(self, APP_NAME, "Önce kataloğu yükleyin.")
            return
        snapshot = copy.deepcopy(self.catalog)
        sha = self.catalog_sha
        settings = copy.deepcopy(self.settings)

        def task(progress: Callable[[str, int], None]) -> tuple[dict[str, Any], str]:
            lookup = {row.get("id"): row for row in snapshot.get("tracks", [])}
            for index, (track_id, path) in enumerate(assignments.items()):
                text = path.read_text(encoding="utf-8-sig", errors="replace")
                if path.suffix.lower() == ".lrc":
                    lookup[track_id]["syncedLyrics"] = normalize_lrc_v7(text)
                else:
                    lookup[track_id]["lyrics"] = text.strip()
                progress(f"Söz {index + 1}/{len(assignments)} doğrulandı", 20 + int((index + 1) / len(assignments) * 55))
            progress("GitHub kataloğu commit ediliyor…", 92)
            new_sha = base.GitHubCatalogClient(settings).commit_catalog(snapshot, sha, "Aurora Music: toplu LRC/TXT sözlerini güncelle")
            progress("Tamamlandı", 100)
            return snapshot, new_sha

        def done(result: tuple[dict[str, Any], str]) -> None:
            self.catalog, self.catalog_sha = result
            self.completion_files.clear()
            self.completion_assignments.clear()
            self.set_dirty(False)
            self.refresh_all_views()
            QMessageBox.information(self, APP_NAME, "LRC/TXT dosyaları mevcut şarkılara bağlandı.")

        self.run_task(task, done, "LRC/TXT dosyaları doğrulanıyor")


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV7Final()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
