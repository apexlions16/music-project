from __future__ import annotations

import difflib
import re
import sys
import unicodedata
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

_TURKISH_ASCII = str.maketrans(
    {
        "ı": "i", "İ": "I", "ş": "s", "Ş": "S", "ğ": "g", "Ğ": "G",
        "ü": "u", "Ü": "U", "ö": "o", "Ö": "O", "ç": "c", "Ç": "C",
    }
)


def normalize_lrc_media_name_v81(value: str) -> str:
    text = Path(value).stem.translate(_TURKISH_ASCII)
    text = unicodedata.normalize("NFD", text).encode("ascii", "ignore").decode("ascii").lower()
    text = re.sub(r"^\s*(?:cd\s*\d+\s*[-_. ]*)?(?:track\s*)?\d{1,3}\s*[-_. )]+", "", text)
    text = re.sub(r"[\[(].*?[\])]", " ", text)
    text = re.sub(
        r"\b(feat|ft|featuring|remaster(?:ed)?|version|edit|mix|explicit|clean|official|audio|video|lyrics?|instrumental|master)\b.*$",
        " ",
        text,
    )
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def lrc_media_score_v81(left: str, right: str) -> float:
    a = normalize_lrc_media_name_v81(left)
    b = normalize_lrc_media_name_v81(right)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if a in b or b in a:
        return 0.72 + min(len(a), len(b)) / max(len(a), len(b)) * 0.18
    at, bt = set(a.split()), set(b.split())
    union = at | bt
    token_score = len(at & bt) / len(union) if union else 0.0
    edit_score = difflib.SequenceMatcher(None, a, b).ratio()
    return token_score * 0.62 + edit_score * 0.38


def match_lrc_files_v81(targets: list[str], files: list[Path]) -> tuple[dict[int, int], dict[int, str]]:
    unused = set(range(len(files)))
    result: dict[int, int] = {}
    methods: dict[int, str] = {}
    target_keys = [normalize_lrc_media_name_v81(value) for value in targets]
    file_keys = [normalize_lrc_media_name_v81(path.name) for path in files]

    for target_index, key in enumerate(target_keys):
        exact = next((file_index for file_index in unused if key and file_keys[file_index] == key), None)
        if exact is not None:
            result[target_index] = exact
            methods[target_index] = "kesin isim eşleşmesi"
            unused.remove(exact)

    for target_index, title in enumerate(targets):
        if target_index in result:
            continue
        ranked = sorted(
            ((file_index, lrc_media_score_v81(title, files[file_index].name)) for file_index in unused),
            key=lambda row: row[1],
            reverse=True,
        )
        if ranked:
            best = ranked[0]
            second_score = ranked[1][1] if len(ranked) > 1 else 0.0
            if best[1] >= 0.64 and best[1] - second_score >= 0.08:
                result[target_index] = best[0]
                methods[target_index] = "isim benzerliği"
                unused.remove(best[0])

    remaining_targets = [index for index in range(len(targets)) if index not in result]
    sorted_files = sorted(unused, key=lambda index: base.natural_sort_key(files[index].name))
    for target_index, file_index in zip(remaining_targets, sorted_files):
        result[target_index] = file_index
        methods[target_index] = "albüm sırası"

    return result, methods


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
        assignments, methods = match_lrc_files_v81([track.title for track in self.import_tracks], files)
        consumed_file_indexes: set[int] = set()
        completed: list[str] = []
        failed: list[str] = []

        for track_index, file_index in sorted(assignments.items()):
            if not (0 <= track_index < len(self.import_tracks) and 0 <= file_index < len(files)):
                continue
            track = self.import_tracks[track_index]
            path = files[file_index]
            consumed_file_indexes.add(file_index)
            try:
                raw = path.read_text(encoding="utf-8-sig", errors="replace")
                track.synced_lyrics = normalize_lrc_v7(raw)
                completed.append(f"{track.title} ← {path.name} ({methods.get(track_index, 'eşleştirme')})")
            except Exception as exc:
                failed.append(f"{track.title} ← {path.name}: {exc}")

        unused = [files[index].name for index in range(len(files)) if index not in consumed_file_indexes]
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
