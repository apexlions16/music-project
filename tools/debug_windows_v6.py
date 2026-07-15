from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path

os.environ["QT_QPA_PLATFORM"] = "offscreen"
sys.path.insert(0, "pc")

output = Path("verification/windows-v6-debug.txt")
output.parent.mkdir(parents=True, exist_ok=True)

try:
    from PySide6.QtWidgets import QApplication
    import AuroraStudioV6Final as entry

    app = QApplication([])
    window = entry.AuroraStudioV6Final()
    titles = [window.nav.item(index).text() for index in range(window.nav.count())]
    text = (
        "SUCCESS\n"
        f"version={entry.APP_VERSION}\n"
        f"nav_count={window.nav.count()}\n"
        f"page_count={window.pages.count()}\n"
        f"nav_titles={titles!r}\n"
        f"cover_fetch_hidden={window.cover_fetch_btn_v3.isHidden()}\n"
    )
    window.close()
except Exception:
    text = "FAILURE\n" + traceback.format_exc()

output.write_text(text, encoding="utf-8")
print(text)
