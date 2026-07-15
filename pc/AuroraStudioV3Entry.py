from __future__ import annotations

import sys

from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication

import AuroraStudio as base
from AuroraStudioV3 import APP_NAME, APP_VERSION, AuroraStudioV3


class AuroraStudioV3Final(AuroraStudioV3):
    """PySide sinyallerinin taşıdığı indeks değerlerini form verisinden ayıran son giriş sınıfı."""

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
