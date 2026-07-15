from __future__ import annotations

import json
import os
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from huggingface_hub import HfApi, hf_hub_download


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def track_by_id(catalog: dict[str, Any], track_id: str) -> dict[str, Any] | None:
    return next((row for row in catalog.get("tracks", []) if row.get("id") == track_id), None)


def source_is_lossless(path: str) -> bool:
    return Path(path).suffix.lower() in {".flac", ".wav", ".wave", ".aiff", ".aif", ".alac"}


def encode_presets(source: Path, output_dir: Path, allow_lossless: bool) -> list[tuple[str, str, str, Path]]:
    outputs: list[tuple[str, str, str, Path]] = []

    standard = output_dir / "standard.m4a"
    run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-vn",
        "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2", str(standard),
    ])
    outputs.append(("standard", "Standart", "AAC 128 kbps", standard))

    high = output_dir / "high.m4a"
    run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-vn",
        "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-ac", "2", str(high),
    ])
    outputs.append(("high", "Yüksek Kalite", "AAC 256 kbps", high))

    if allow_lossless:
        lossless = output_dir / "lossless.flac"
        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-vn",
            "-c:a", "flac", "-sample_fmt", "s16", "-ar", "44100", "-ac", "2", str(lossless),
        ])
        outputs.append(("lossless", "Lossless", "FLAC 16-bit/44.1 kHz", lossless))

    return outputs


def generated_source(kind: str, label: str, codec: str, url: str) -> dict[str, Any]:
    return {
        "id": f"audio_generated_{os.urandom(12).hex()}",
        "kind": kind,
        "label": label,
        "codec": codec,
        "url": url,
        "downloadUrl": url,
        "downloadable": True,
        "spatial": False,
        "generated": True,
    }


def refresh_release_states(catalog: dict[str, Any]) -> None:
    lookup = {row.get("id"): row for row in catalog.get("tracks", [])}
    for release in catalog.get("releases", []):
        refs = release.get("tracks", [])
        available = 0
        for ref in refs:
            track = lookup.get(ref.get("trackId"), {})
            if track.get("playable") or track.get("sources"):
                available += 1
        total = len(refs)
        if total and available == total:
            status = "published"
        elif available:
            status = "partial"
        else:
            status = "upcoming"
        release["status"] = status
        release["availableTrackCount"] = available
        release["totalTrackCount"] = total


def process_catalog(catalog_path: Path, repo_id: str, token: str) -> bool:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    jobs = catalog.setdefault("qualityJobs", [])
    queued = [job for job in jobs if job.get("status") == "queued"]
    if not queued:
        print("Kalite kuyruğunda iş yok.")
        return False

    api = HfApi(token=token)
    changed = False
    for job in queued:
        track_id = str(job.get("trackId", ""))
        source_path = str(job.get("sourcePath", ""))
        track = track_by_id(catalog, track_id)
        if not track or not source_path:
            job.update(status="failed", error="Şarkı veya kaynak yolu bulunamadı", completedAt=now_iso())
            changed = True
            continue

        job["status"] = "processing"
        job["startedAt"] = now_iso()
        changed = True
        try:
            local_source = Path(hf_hub_download(
                repo_id=repo_id,
                filename=source_path,
                repo_type="dataset",
                token=token,
            ))
            with tempfile.TemporaryDirectory(prefix="aurora-quality-") as temp:
                output_dir = Path(temp)
                rendered = encode_presets(local_source, output_dir, source_is_lossless(source_path))
                generated: list[dict[str, Any]] = []
                for kind, label, codec, file_path in rendered:
                    remote = f"aurora/audio-processed/{track_id}/{job['id']}-{kind}{file_path.suffix}"
                    api.upload_file(
                        path_or_fileobj=str(file_path),
                        path_in_repo=remote,
                        repo_id=repo_id,
                        repo_type="dataset",
                        commit_message=f"Aurora quality: {track.get('title', track_id)} {kind}",
                    )
                    url = f"https://huggingface.co/datasets/{repo_id}/resolve/main/{remote}"
                    generated.append(generated_source(kind, label, codec, url))

            old_sources = track.setdefault("sources", [])
            generated_kinds = {row["kind"] for row in generated}
            track["sources"] = [
                row for row in old_sources
                if not (row.get("generated") is True and row.get("kind") in generated_kinds)
            ] + generated
            track["playable"] = True
            track["availability"] = "available"
            track["qualityState"] = "ready"
            job.update(
                status="done",
                completedAt=now_iso(),
                generatedKinds=sorted(generated_kinds),
                error="",
            )
            print(f"Tamamlandı: {track.get('title', track_id)} -> {', '.join(sorted(generated_kinds))}")
        except Exception as exc:  # noqa: BLE001
            job.update(status="failed", completedAt=now_iso(), error=str(exc)[:2000])
            track["qualityState"] = "failed"
            print(f"Hata: {track.get('title', track_id)}: {exc}")

    refresh_release_states(catalog)
    catalog["schemaVersion"] = max(5, int(catalog.get("schemaVersion", 1)))
    catalog["updatedAt"] = now_iso()
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return changed


def main() -> None:
    token = os.environ.get("HF_TOKEN", "").strip()
    if not token:
        raise SystemExit("HF_TOKEN GitHub Actions secret tanımlı değil.")
    repo_id = os.environ.get("HF_REPO", "hcywashere/m-project").strip()
    catalog_path = Path(os.environ.get("AURORA_CATALOG", "catalog/catalog.json"))
    process_catalog(catalog_path, repo_id, token)


if __name__ == "__main__":
    main()
