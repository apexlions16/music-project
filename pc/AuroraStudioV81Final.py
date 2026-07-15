from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication, QFileDialog, QLabel, QMessageBox, QPushButton

import AuroraStudio as base
import AuroraStudioV3 as v3
import AuroraStudioV3Entry as v3_entry
import AuroraStudioV4Entry as v4
import AuroraStudioV6Entry as v6
import AuroraStudioV7Final as v7
import AuroraStudioV8Final as v8
from AuroraStudioV7Final import normalize_lrc_v7
from AuroraStudioV8Final import AuroraStudioV8Final

APP_NAME = base.APP_NAME
APP_VERSION = "0.8.1"
base.APP_VERSION = APP_VERSION
v3.APP_VERSION = APP_VERSION
v3_entry.APP_VERSION = APP_VERSION
v4.APP_VERSION = APP_VERSION
v6.APP_VERSION = APP_VERSION
v7.APP_VERSION = APP_VERSION
v8.APP_VERSION = APP_VERSION


class AuroraStudioV81Final(AuroraStudioV8Final):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self._install_bulk_lrc_ui_v81()

    def _install_bulk_lrc_ui_v81(self) -> None:
        if hasattr(self, "bulk_lrc_btn_v81"):
            return
        tracks_group = self.import_table.parentWidget()
        layout = tracks_group.layout() if tracks_group else None
        if layout is None:
            return

        self.bulk_lrc_btn_v81 = QPushButton("LRC Dosyalarını Toplu Seç ve İsimlerle Eşleştir")
        self.bulk_lrc_btn_v81.clicked.connect(self.choose_bulk_lrc_files_v81)
        self.bulk_lrc_btn_v81.setObjectName("primaryButton")

        self.bulk_lrc_note_v81 = QLabel(
            "Dosya adları önce şarkı adlarıyla eşleştirilir. Kesin veya güvenli isim eşleşmesi bulunamayan "
            "dosyalar doğal dosya sırasına göre boş kalan şarkılara yerleştirilir. Geçerli LRC zaman kodları "
            "otomatik doğrulanıp standart biçime çevrilir."
        )
        self.bulk_lrc_note_v81.setWordWrap(True)
        self.bulk_lrc_note_v81.setObjectName("muted")

        table_index = layout.indexOf(self.import_table)
        insert_at = table_index if table_index >= 0 else layout.count()
        layout.insertWidget(insert_at, self.bulk_lrc_note_v81)
        layout.insertWidget(insert_at, self.bulk_lrc_btn_v81)

    def choose_bulk_lrc_files_v81(self) -> None:
        if not self.import_tracks:
            QMessageBox.information(
                self,
                APP_NAME,
                "Önce Spotify metadata çekin veya Yeni Yayın ekranına şarkıları ekleyin.",
            )
            return

        names, _ = QFileDialog.getOpenFileNames(
            self,
            "Albümün LRC Dosyalarını Toplu Seç",
            "",
            "LRC Dosyaları (*.lrc);;Tüm Dosyalar (*.*)",
        )
        if not names:
            return

        files = [Path(name) for name in names]
        assignments = v6.match_media([track.title for track in self.import_tracks], files)
        assigned_file_indexes: set[int] = set()
        completed: list[str] = []
        failed: list[str] = []

        for track_index, file_index in sorted(assignments.items()):
            if not (0 <= track_index < len(self.import_tracks) and 0 <= file_index < len(files)):
                continue
            track = self.import_tracks[track_index]
            path = files[file_index]
            try:
                raw = path.read_text(encoding="utf-8-sig", errors="replace")
                normalized = normalize_lrc_v7(raw)
                track.synced_lyrics = normalized
                assigned_file_indexes.add(file_index)
                score = v6.media_score(track.title, path.name)
                method = "isim eşleşmesi" if score >= 0.64 else "albüm sırası"
                completed.append(f"{track.title} ← {path.name} ({method})")
            except Exception as exc:
                failed.append(f"{path.name}: {exc}")

        unused = [files[index].name for index in range(len(files)) if index not in assigned_file_indexes]
        self.refresh_import_table()

        self.publish_status.setText(
            f"Toplu LRC: {len(completed)} şarkıya söz bağlandı"
            + (f" • {len(failed)} hatalı" if failed else "")
        )
        self.append_log(
            f"Toplu LRC eşleştirmesi: {len(completed)} başarılı, {len(failed)} hatalı, {len(unused)} kullanılmayan dosya"
        )

        details = [f"{len(completed)} LRC dosyası şarkılara bağlandı."]
        if completed:
            details.append("\nEşleşmeler:\n" + "\n".join(completed[:24]))
            if len(completed) > 24:
                details.append(f"\n… ve {len(completed) - 24} eşleşme daha")
        if failed:
            details.append("\nOkunamayan/geçersiz dosyalar:\n" + "\n".join(failed[:10]))
        if unused:
            details.append("\nKullanılmayan dosyalar:\n" + "\n".join(unused[:10]))

        if failed:
            QMessageBox.warning(self, APP_NAME, "\n".join(details))
        else:
            QMessageBox.information(self, APP_NAME, "\n".join(details))


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV81Final()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
