from __future__ import annotations

import json
import sys
from typing import Any

from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication, QMessageBox, QWidget

import AuroraStudio as base
import AuroraStudioV6Entry as v6

APP_NAME = v6.APP_NAME
APP_VERSION = v6.APP_VERSION


class AuroraStudioV6Final(v6.AuroraStudioV6):
    def __init__(self):
        self._legacy_pages_v6: list[QWidget] = []
        self._legacy_container_v6: QWidget | None = None
        super().__init__()
        self.ensure_final_navigation_v6()
        if hasattr(self, "cover_fetch_btn_v3"):
            self.cover_fetch_btn_v3.hide()
        self.refresh_all_views()

    def ensure_final_navigation_v6(self) -> None:
        desired_titles = [
            "Genel Bakış",
            "Yeni Yayın",
            "Yayın Kütüphanesi",
            "Yakında Tamamlama",
            "Sanatçılar",
            "Sunum ve Listeler",
            "Katalog JSON",
            "Ayarlar",
        ]
        current: dict[str, QWidget] = {}
        all_pages: list[QWidget] = []
        count = min(self.nav.count(), self.pages.count())
        for index in range(count):
            page = self.pages.widget(index)
            all_pages.append(page)
            title = self.nav.item(index).text()
            current.setdefault(title, page)
        if "Yayın Kütüphanesi" not in current:
            current["Yayın Kütüphanesi"] = self.make_library_page_v6()
        if "Yakında Tamamlama" not in current:
            current["Yakında Tamamlama"] = self.make_completion_page_v6()

        desired_pages = [current[title] for title in desired_titles if title in current]
        self._legacy_container_v6 = QWidget(self)
        self._legacy_container_v6.hide()
        self._legacy_pages_v6 = [page for page in all_pages if page not in desired_pages]

        while self.pages.count():
            self.pages.removeWidget(self.pages.widget(0))
        for page in self._legacy_pages_v6:
            page.setParent(self._legacy_container_v6)
            page.hide()

        self.nav.blockSignals(True)
        self.nav.clear()
        for title in desired_titles:
            page = current.get(title)
            if page is None:
                continue
            self.nav.addItem(base.QListWidgetItem(title))
            self.pages.addWidget(page)
        self.nav.blockSignals(False)
        try:
            self.nav.currentRowChanged.disconnect()
        except Exception:
            pass
        self.nav.currentRowChanged.connect(self.pages_set_index_v6)
        self.nav.setCurrentRow(0)

    def save_library_release_v6(self) -> None:
        release_id = self.library_selected_release_id
        values = {
            "title": self.lib_release_title.text().strip(),
            "type": self.lib_release_type.currentData(),
            "releaseDate": self.lib_release_date.text().strip(),
            "cover": self.lib_release_cover.text().strip(),
            "animatedCoverUrl": self.lib_release_animated.text().strip(),
            "label": self.lib_release_label.text().strip(),
            "copyright": self.lib_release_copyright.text().strip(),
            "description": self.lib_release_description.toPlainText(),
        }
        if not release_id or not values["title"]:
            return

        def mutate(catalog: dict[str, Any]) -> None:
            release = next(row for row in catalog.get("releases", []) if row.get("id") == release_id)
            release.update(
                title=values["title"],
                slug=base.slugify(values["title"]),
                type=values["type"],
                releaseDate=values["releaseDate"],
                cover=values["cover"],
                heroImage=values["cover"],
                animatedCoverUrl=values["animatedCoverUrl"],
                label=values["label"],
                copyright=values["copyright"],
                description=values["description"],
            )

        self.commit_mutation_v6(f"Aurora Music: {values['title']} yayınını güncelle", mutate)

    def save_library_track_v6(self) -> None:
        track_id = self.library_selected_track_id
        values = {
            "title": self.lib_track_title.text().strip(),
            "isrc": self.lib_track_isrc.text().strip(),
            "explicit": self.lib_track_explicit.isChecked(),
            "featuredArtistNames": base.ordered_unique(
                [value.strip() for value in self.lib_track_featured_names.text().split(",") if value.strip()]
            ),
            "lyrics": self.lib_track_lyrics.toPlainText(),
            "syncedLyrics": self.lib_track_synced.toPlainText(),
        }
        if not track_id or not values["title"]:
            return
        try:
            credits = json.loads(self.lib_track_credits.toPlainText().strip() or "[]")
            if not isinstance(credits, list):
                raise ValueError("Künye JSON dizisi olmalıdır.")
        except Exception as exc:
            QMessageBox.warning(self, APP_NAME, f"Künye JSON hatası: {exc}")
            return
        values["credits"] = credits

        def mutate(catalog: dict[str, Any]) -> None:
            track = next(row for row in catalog.get("tracks", []) if row.get("id") == track_id)
            track.update(
                title=values["title"],
                slug=base.slugify(values["title"]),
                isrc=values["isrc"],
                explicit=values["explicit"],
                featuredArtistNames=values["featuredArtistNames"],
                lyrics=values["lyrics"],
                syncedLyrics=values["syncedLyrics"],
                credits=values["credits"],
            )

        self.commit_mutation_v6(f"Aurora Music: {values['title']} şarkısını güncelle", mutate)


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV6Final()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
