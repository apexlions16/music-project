from __future__ import annotations

import sys

from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication

import AuroraStudio as base
import AuroraStudioV3 as v3
import AuroraStudioV3Entry as v3_entry

APP_NAME = base.APP_NAME
APP_VERSION = "0.4.0"

# Kalıtım zincirindeki tüm başlıklar ve HTTP User-Agent değerleri aynı sürümü görür.
base.APP_VERSION = APP_VERSION
v3.APP_VERSION = APP_VERSION
v3_entry.APP_VERSION = APP_VERSION

AuroraStudioV4 = v3_entry.AuroraStudioV3Final


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV4()
    window.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
