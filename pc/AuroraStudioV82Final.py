from __future__ import annotations

import difflib
import re
import sys
import unicodedata
from dataclasses import dataclass
from typing import Any, Callable

import requests
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QGroupBox,
    QHBoxLayout,
    QLabel,
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
import AuroraStudioV8Final as v8
import AuroraStudioV81Final as v81
from AuroraStudioV81Final import AuroraStudioV81Final

APP_NAME = base.APP_NAME
APP_VERSION = "0.8.2"
base.APP_VERSION = APP_VERSION
v3.APP_VERSION = APP_VERSION
v3_entry.APP_VERSION = APP_VERSION
v4.APP_VERSION = APP_VERSION
v6.APP_VERSION = APP_VERSION
v7.APP_VERSION = APP_VERSION
v8.APP_VERSION = APP_VERSION
v81.APP_VERSION = APP_VERSION

_LRC_TIMESTAMP = re.compile(r"\[(?:\d{1,3}:\d{2}(?:[.:]\d{1,3})?|[a-zA-Z]+:[^]]*)]")
_TURKISH_ASCII = str.maketrans(
    {
        "ı": "i", "İ": "I", "ş": "s", "Ş": "S", "ğ": "g", "Ğ": "G",
        "ü": "u", "Ü": "U", "ö": "o", "Ö": "O", "ç": "c", "Ç": "C",
    }
)


def normalize_lookup_text_v82(value: str) -> str:
    text = (value or "").translate(_TURKISH_ASCII)
    text = unicodedata.normalize("NFD", text)
    text = "".join(character for character in text if unicodedata.category(character) != "Mn")
    text = text.casefold()
    text = re.sub(r"\b(feat|ft|featuring)\b.*$", " ", text)
    text = re.sub(r"\b(remaster(?:ed)?|version|edit|mix|explicit|clean|official|audio|video|lyrics?)\b.*$", " ", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def similarity_v82(left: str, right: str) -> float:
    a = normalize_lookup_text_v82(left)
    b = normalize_lookup_text_v82(right)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if a in b or b in a:
        return 0.82
    at, bt = set(a.split()), set(b.split())
    union = at | bt
    token = len(at & bt) / len(union) if union else 0.0
    edit = difflib.SequenceMatcher(None, a, b).ratio()
    return token * 0.62 + edit * 0.38


def plain_from_payload_v82(payload: dict[str, Any]) -> tuple[str, bool]:
    plain = str(payload.get("plainLyrics") or "").replace("\r\n", "\n").replace("\r", "\n").strip()
    if plain:
        return plain, False

    synced = str(payload.get("syncedLyrics") or "").replace("\r\n", "\n").replace("\r", "\n").strip()
    if not synced:
        return "", False

    # Tek satıra birleşmiş LRC'leri de zaman damgalarının önünden ayır.
    expanded = re.sub(r"(?=\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])", "\n", synced)
    rows: list[str] = []
    for row in expanded.splitlines():
        lyric = _LRC_TIMESTAMP.sub("", row).strip()
        if lyric and (not rows or rows[-1] != lyric):
            rows.append(lyric)
    return "\n".join(rows).strip(), True


@dataclass(frozen=True)
class LyricsLookupSpec:
    index: int
    title: str
    artists: tuple[str, ...]
    album: str
    duration: int


@dataclass(frozen=True)
class LyricsLookupMatch:
    lyrics: str
    matched_title: str
    matched_artist: str
    confidence: float
    derived_from_synced: bool


class PlainLyricsLookupClient:
    BASE_URL = "https://lrclib.net/api"

    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Accept": "application/json",
                "User-Agent": f"AuroraStudio/{APP_VERSION} (personal music catalog; automatic plain lyrics lookup)",
            }
        )

    @staticmethod
    def _request_params(spec: LyricsLookupSpec) -> dict[str, Any]:
        params: dict[str, Any] = {
            "track_name": spec.title,
            "artist_name": spec.artists[0] if spec.artists else "",
        }
        if spec.album:
            params["album_name"] = spec.album
        if spec.duration > 0:
            params["duration"] = spec.duration
        return params

    @staticmethod
    def _score(spec: LyricsLookupSpec, payload: dict[str, Any]) -> float:
        title_score = similarity_v82(spec.title, str(payload.get("trackName") or ""))
        artist_value = str(payload.get("artistName") or "")
        artist_score = max((similarity_v82(value, artist_value) for value in spec.artists), default=0.0)
        album_score = similarity_v82(spec.album, str(payload.get("albumName") or "")) if spec.album else 0.5
        duration_value = int(round(float(payload.get("duration") or 0)))
        duration_score = 0.5
        if spec.duration > 0 and duration_value > 0:
            difference = abs(spec.duration - duration_value)
            duration_score = 1.0 if difference <= 2 else 0.8 if difference <= 5 else 0.35 if difference <= 12 else 0.0
        return title_score * 0.57 + artist_score * 0.28 + album_score * 0.10 + duration_score * 0.05

    def _get_json(self, path: str, params: dict[str, Any]) -> Any:
        response = self.session.get(f"{self.BASE_URL}/{path}", params=params, timeout=(10, 25))
        if response.status_code == 404:
            return None
        if response.status_code == 429:
            raise RuntimeError("Söz sağlayıcısının hız sınırına ulaşıldı. Birkaç dakika sonra yeniden deneyin.")
        response.raise_for_status()
        return response.json()

    def lookup(self, spec: LyricsLookupSpec) -> LyricsLookupMatch | None:
        if not spec.title.strip() or not spec.artists:
            return None

        candidates: list[dict[str, Any]] = []
        params = self._request_params(spec)

        # Önce süre ve albüm dahil kesin eşleşmeyi dene.
        exact = self._get_json("get", params)
        if isinstance(exact, dict):
            candidates.append(exact)

        # Kesin eşleşme söz döndürmediyse daha toleranslı aramaya geç.
        if not any(plain_from_payload_v82(row)[0] for row in candidates):
            search_params = {
                "track_name": spec.title,
                "artist_name": spec.artists[0],
            }
            if spec.album:
                search_params["album_name"] = spec.album
            result = self._get_json("search", search_params)
            if isinstance(result, list):
                candidates.extend(row for row in result if isinstance(row, dict))

        # Bazı kayıtlarda albüm/artist yazımı farklıdır; son çare genel q araması.
        if not any(plain_from_payload_v82(row)[0] for row in candidates):
            result = self._get_json("search", {"q": f"{spec.title} {spec.artists[0]}"})
            if isinstance(result, list):
                candidates.extend(row for row in result if isinstance(row, dict))

        ranked: list[tuple[float, dict[str, Any], str, bool]] = []
        seen: set[tuple[str, str, str]] = set()
        for payload in candidates:
            key = (
                str(payload.get("trackName") or ""),
                str(payload.get("artistName") or ""),
                str(payload.get("albumName") or ""),
            )
            if key in seen or bool(payload.get("instrumental", False)):
                continue
            seen.add(key)
            lyrics, derived = plain_from_payload_v82(payload)
            if not lyrics:
                continue
            title_score = similarity_v82(spec.title, key[0])
            score = self._score(spec, payload)
            # Yanlış şarkının sözünü otomatik doldurmamak için başlık ve toplam güven eşiği uygula.
            if title_score >= 0.68 and score >= 0.64:
                ranked.append((score, payload, lyrics, derived))

        if not ranked:
            return None
        score, payload, lyrics, derived = max(ranked, key=lambda row: row[0])
        return LyricsLookupMatch(
            lyrics=lyrics,
            matched_title=str(payload.get("trackName") or spec.title),
            matched_artist=str(payload.get("artistName") or spec.artists[0]),
            confidence=score,
            derived_from_synced=derived,
        )


class AuroraStudioV82Final(AuroraStudioV81Final):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self._install_auto_plain_lyrics_ui_v82()

    def _install_auto_plain_lyrics_ui_v82(self) -> None:
        if hasattr(self, "find_plain_selected_btn_v82"):
            return
        tracks_group = self.import_table.parentWidget()
        tracks_layout = tracks_group.layout() if tracks_group else None
        if tracks_layout is None:
            return

        group = QGroupBox("Normal Şarkı Sözlerini Otomatik Bul")
        layout = QVBoxLayout(group)
        note = QLabel(
            "LRC bulunan şarkılara dokunulmaz. LRC'si olmayan parçalar sanatçı, şarkı, albüm ve süre bilgisiyle "
            "aranır; bulunan normal sözler yayınlamadan önce Yeni Yayın formuna yazılır."
        )
        note.setWordWrap(True)
        note.setObjectName("muted")
        layout.addWidget(note)

        buttons = QHBoxLayout()
        self.find_plain_selected_btn_v82 = QPushButton("Seçili Şarkının Normal Sözünü Bul")
        self.find_plain_selected_btn_v82.clicked.connect(self.find_selected_plain_lyrics_v82)
        self.find_plain_missing_btn_v82 = QPushButton("Albümde Eksik Normal Sözleri Toplu Bul")
        self.find_plain_missing_btn_v82.setObjectName("primaryButton")
        self.find_plain_missing_btn_v82.clicked.connect(self.find_missing_plain_lyrics_v82)
        buttons.addWidget(self.find_plain_selected_btn_v82)
        buttons.addWidget(self.find_plain_missing_btn_v82)
        buttons.addStretch()
        layout.addLayout(buttons)

        self.plain_lyrics_status_v82 = QLabel("Hazır • otomatik arama yalnız normal söz alanını doldurur")
        self.plain_lyrics_status_v82.setObjectName("accentText")
        layout.addWidget(self.plain_lyrics_status_v82)

        table_index = tracks_layout.indexOf(self.import_table)
        tracks_layout.insertWidget(table_index if table_index >= 0 else tracks_layout.count(), group)

    def _artist_name_v82(self, artist_id: str) -> str:
        for row in self.catalog.get("artists", []):
            if str(row.get("id") or "") == artist_id:
                return str(row.get("name") or "").strip()
        try:
            value = self.artist_name_for_id(artist_id)
            return "" if value in {"", "Bilinmeyen", "Sanatçı"} else str(value).strip()
        except Exception:
            return ""

    def _track_artist_names_v82(self, track: base.ImportTrack) -> tuple[str, ...]:
        ids = list(track.primary_artist_ids or self.import_release_artist_ids)
        names = [self._artist_name_v82(value) for value in ids]
        names.extend(str(value).strip() for value in track.featured_artist_names)
        fallback = self.import_artist.currentText().strip() if hasattr(self, "import_artist") else ""
        if fallback:
            names.append(fallback)
        result: list[str] = []
        seen: set[str] = set()
        for value in names:
            key = normalize_lookup_text_v82(value)
            if value and key and key not in seen:
                seen.add(key)
                result.append(value)
        return tuple(result)

    def _spec_for_track_v82(self, index: int) -> LyricsLookupSpec:
        track = self.import_tracks[index]
        return LyricsLookupSpec(
            index=index,
            title=track.title.strip(),
            artists=self._track_artist_names_v82(track),
            album=self.import_title.text().strip(),
            duration=int(track.duration_seconds or 0),
        )

    def _set_plain_lyrics_busy_v82(self, busy: bool) -> None:
        self.find_plain_selected_btn_v82.setEnabled(not busy)
        self.find_plain_missing_btn_v82.setEnabled(not busy)

    def find_selected_plain_lyrics_v82(self) -> None:
        row = self.import_table.currentRow()
        if not (0 <= row < len(self.import_tracks)):
            QMessageBox.information(self, APP_NAME, "Önce Yeni Yayın tablosundan bir şarkı seçin.")
            return
        track = self.import_tracks[row]
        if track.synced_lyrics.strip():
            QMessageBox.information(self, APP_NAME, "Bu şarkıda senkronize LRC zaten var; normal söz araması yapılmadı.")
            return
        if track.lyrics.strip():
            answer = QMessageBox.question(
                self,
                APP_NAME,
                "Bu şarkının normal söz alanı dolu. İnternetten bulunan sonuçla değiştirilsin mi?",
            )
            if answer != QMessageBox.Yes:
                return

        spec = self._spec_for_track_v82(row)
        if not spec.artists:
            QMessageBox.warning(self, APP_NAME, "Şarkının sanatçı adı bulunamadı; önce sanatçı metadata bilgisini tamamlayın.")
            return

        self._set_plain_lyrics_busy_v82(True)
        self.plain_lyrics_status_v82.setText(f"{spec.title} için normal söz aranıyor…")

        def task(progress: Callable[[str, int], None]) -> tuple[LyricsLookupSpec, LyricsLookupMatch | None]:
            progress(f"{spec.title} için normal söz aranıyor…", 25)
            match = PlainLyricsLookupClient().lookup(spec)
            progress("Arama tamamlandı", 100)
            return spec, match

        def done(result: tuple[LyricsLookupSpec, LyricsLookupMatch | None]) -> None:
            found_spec, match = result
            self._set_plain_lyrics_busy_v82(False)
            if match is None:
                self.plain_lyrics_status_v82.setText(f"{found_spec.title}: uygun normal söz bulunamadı")
                QMessageBox.information(self, APP_NAME, "Bu şarkı için güvenli bir normal söz eşleşmesi bulunamadı.")
                return
            self.import_tracks[found_spec.index].lyrics = match.lyrics
            self.refresh_import_table()
            source_note = " • senkron kayıttan düz söze çevrildi" if match.derived_from_synced else ""
            self.plain_lyrics_status_v82.setText(
                f"{found_spec.title}: normal söz bulundu • güven %{round(match.confidence * 100)}{source_note}"
            )
            self.append_log(
                f"Normal söz bulundu: {found_spec.title} ← {match.matched_title} / {match.matched_artist} "
                f"(güven %{round(match.confidence * 100)})"
            )
            QMessageBox.information(
                self,
                APP_NAME,
                "Normal söz bulundu ve Yeni Yayın formuna yazıldı.\n\n"
                f"Eşleşme: {match.matched_title} — {match.matched_artist}\n"
                "Detay / Feat / Söz düğmesinden yayınlamadan önce kontrol edebilirsiniz.",
            )

        self.run_task(task, done, "Normal söz aranıyor")
        if self.active_thread:
            self.active_thread.finished.connect(lambda: self._set_plain_lyrics_busy_v82(False))

    def find_missing_plain_lyrics_v82(self) -> None:
        indexes = [
            index
            for index, track in enumerate(self.import_tracks)
            if not track.synced_lyrics.strip() and not track.lyrics.strip()
        ]
        if not indexes:
            QMessageBox.information(
                self,
                APP_NAME,
                "Albümde normal söz araması gereken parça yok. LRC bulunan veya normal sözü dolu şarkılar atlanır.",
            )
            return

        specs = [self._spec_for_track_v82(index) for index in indexes]
        searchable = [spec for spec in specs if spec.title and spec.artists]
        if not searchable:
            QMessageBox.warning(self, APP_NAME, "Aranacak şarkıların sanatçı bilgisi bulunamadı.")
            return

        self._set_plain_lyrics_busy_v82(True)
        self.plain_lyrics_status_v82.setText(f"{len(searchable)} eksik şarkı için normal söz aranıyor…")

        def task(
            progress: Callable[[str, int], None],
        ) -> tuple[list[tuple[LyricsLookupSpec, LyricsLookupMatch]], list[tuple[LyricsLookupSpec, str]]]:
            client = PlainLyricsLookupClient()
            found: list[tuple[LyricsLookupSpec, LyricsLookupMatch]] = []
            missing: list[tuple[LyricsLookupSpec, str]] = []
            for position, spec in enumerate(searchable, start=1):
                progress(
                    f"{position}/{len(searchable)} • {spec.title} için normal söz aranıyor…",
                    int((position - 1) / max(len(searchable), 1) * 100),
                )
                try:
                    match = client.lookup(spec)
                    if match is None:
                        missing.append((spec, "uygun eşleşme bulunamadı"))
                    else:
                        found.append((spec, match))
                except Exception as exc:
                    missing.append((spec, str(exc)))
            progress("Toplu normal söz araması tamamlandı", 100)
            return found, missing

        def done(
            result: tuple[list[tuple[LyricsLookupSpec, LyricsLookupMatch]], list[tuple[LyricsLookupSpec, str]]],
        ) -> None:
            found, missing = result
            self._set_plain_lyrics_busy_v82(False)
            for spec, match in found:
                # Arama sürerken kullanıcı alanı doldurmuş olamaz; yine de LRC'yi asla ezme.
                track = self.import_tracks[spec.index]
                if not track.synced_lyrics.strip():
                    track.lyrics = match.lyrics
            self.refresh_import_table()
            self.plain_lyrics_status_v82.setText(
                f"Toplu arama: {len(found)} bulundu • {len(missing)} bulunamadı • LRC bulunanlar atlandı"
            )
            self.append_log(
                f"Toplu normal söz araması: {len(found)} başarılı, {len(missing)} bulunamadı/hatalı"
            )

            details = [f"{len(found)} şarkının normal sözü otomatik olarak forma yazıldı."]
            if found:
                details.append(
                    "\nBulunanlar:\n"
                    + "\n".join(
                        f"{spec.title} ← {match.matched_title} / {match.matched_artist} "
                        f"(%{round(match.confidence * 100)})"
                        for spec, match in found[:20]
                    )
                )
                if len(found) > 20:
                    details.append(f"\n… ve {len(found) - 20} şarkı daha")
            if missing:
                details.append(
                    "\nBulunamayanlar:\n"
                    + "\n".join(f"{spec.title}: {reason}" for spec, reason in missing[:12])
                )
            QMessageBox.information(self, APP_NAME, "\n".join(details))

        self.run_task(task, done, f"{len(searchable)} şarkının normal sözü aranıyor")
        if self.active_thread:
            self.active_thread.finished.connect(lambda: self._set_plain_lyrics_busy_v82(False))


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setStyle("Fusion")
    palette = app.palette()
    palette.setColor(QPalette.ColorRole.Window, QColor("#0a0b10"))
    app.setPalette(palette)
    window = AuroraStudioV82Final()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
