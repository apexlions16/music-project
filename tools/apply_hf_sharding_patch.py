from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "pc" / "AuroraStudio.py"
README = ROOT / "pc" / "README.md"
WORKFLOW = ROOT / ".github" / "workflows" / "aurora-v030-build.yml"

source = SOURCE.read_text(encoding="utf-8")

source = source.replace('import base64\n', 'import base64\nimport concurrent.futures\n')
source = source.replace('import tempfile\n', 'import tempfile\nimport time\n')
source = source.replace(
    'import requests\nfrom huggingface_hub import CommitOperationAdd, HfApi\n',
    '# Xet bazı Windows kurulumlarında yüklemeyi cevapsız bırakabildiği için Aurora Studio\n'
    '# varsayılan olarak daha öngörülebilir Git LFS yolunu kullanır. Kullanıcı ortam\n'
    '# değişkenini önceden ayarladıysa mevcut tercih korunur.\n'
    'os.environ.setdefault("HF_HUB_DISABLE_XET", "1")\n'
    'os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")\n\n'
    'import requests\nfrom huggingface_hub import CommitOperationAdd, HfApi\n',
)
source = source.replace('APP_VERSION = "0.2.0"', 'APP_VERSION = "0.2.1"')
source = source.replace(
    'CONFIG_PATH = CONFIG_DIR / "settings.json"\n',
    'CONFIG_PATH = CONFIG_DIR / "settings.json"\n'
    'HF_COMMIT_LEDGER_PATH = CONFIG_DIR / "hf-commit-history.json"\n'
    'HF_STORAGE_INDEX_PATH = "aurora/.aurora-storage-index.json"\n'
    'HF_SHARD_FILE_LIMIT = 9000\n'
    'HF_COMMIT_HOURLY_LIMIT = 128\n'
    'HF_COMMIT_SOFT_LIMIT = 120\n'
    'HF_UPLOAD_TIMEOUT_SECONDS = 90 * 60\n',
)

new_storage = r'''class HuggingFaceStorage:
    """Hugging Face medya yükleme, klasör shard ve commit bütçesi yöneticisi."""

    def __init__(self, settings: StudioSettings):
        self.settings = settings
        self.api = HfApi(token=settings.hf_token)
        self._storage_index: dict[str, Any] | None = None
        self._storage_index_dirty = False
        self._index_temp_path: Path | None = None

    def ensure_repo(self) -> None:
        if not self.settings.hf_repo:
            raise RuntimeError("Hugging Face depo adı ayarlanmamış.")
        self.api.create_repo(
            repo_id=self.settings.hf_repo,
            repo_type=self.settings.hf_repo_type,
            private=False,
            exist_ok=True,
            token=self.settings.hf_token,
        )

    def _auth_headers(self) -> dict[str, str]:
        headers = {"User-Agent": f"AuroraStudio/{APP_VERSION}", "Cache-Control": "no-cache"}
        if self.settings.hf_token:
            headers["Authorization"] = f"Bearer {self.settings.hf_token}"
        return headers

    def _empty_index(self) -> dict[str, Any]:
        return {
            "version": 1,
            "shardLimit": HF_SHARD_FILE_LIMIT,
            "counts": {},
            "updatedAt": now_iso(),
        }

    def _scan_existing_shards(self, progress: Callable[[str], None] | None = None) -> dict[str, Any]:
        if progress:
            progress("Hugging Face klasörleri ilk kez taranıyor; sonraki yüklemelerde bu işlem tekrarlanmayacak…")
        index = self._empty_index()
        pattern = re.compile(r"^aurora/(?P<category>[a-z0-9-]+?)(?P<shard>[1-9][0-9]*)/[^/]+$")
        try:
            files = self.api.list_repo_files(
                repo_id=self.settings.hf_repo,
                repo_type=self.settings.hf_repo_type,
                token=self.settings.hf_token,
            )
        except Exception as exc:
            raise RuntimeError(f"Hugging Face klasör listesi alınamadı: {exc}") from exc
        for path in files:
            match = pattern.match(path)
            if not match:
                continue
            category = match.group("category")
            shard = match.group("shard")
            category_counts = index["counts"].setdefault(category, {})
            category_counts[shard] = int(category_counts.get(shard, 0)) + 1
        self._storage_index_dirty = True
        return index

    def _load_storage_index(self, progress: Callable[[str], None] | None = None) -> dict[str, Any]:
        if self._storage_index is not None:
            return self._storage_index
        self.ensure_repo()
        try:
            response = requests.get(
                self.resolve_url(HF_STORAGE_INDEX_PATH),
                headers=self._auth_headers(),
                params={"aurora_cache_bust": int(time.time())},
                timeout=(15, 60),
            )
            if response.status_code == 200:
                payload = response.json()
                if isinstance(payload, dict) and isinstance(payload.get("counts"), dict):
                    payload["shardLimit"] = HF_SHARD_FILE_LIMIT
                    self._storage_index = payload
                    return payload
            elif response.status_code not in {401, 403, 404}:
                response.raise_for_status()
        except (ValueError, requests.RequestException):
            pass
        self._storage_index = self._scan_existing_shards(progress)
        return self._storage_index

    @staticmethod
    def _safe_category(category: str) -> str:
        value = re.sub(r"[^a-z0-9-]+", "-", (category or "media").strip().lower()).strip("-")
        return value or "media"

    def allocate_remote(
        self,
        category: str,
        suffix: str,
        progress: Callable[[str], None] | None = None,
    ) -> str:
        index = self._load_storage_index(progress)
        category = self._safe_category(category)
        suffix = suffix.lower().strip()
        if suffix and not suffix.startswith("."):
            suffix = "." + suffix
        counts = index.setdefault("counts", {}).setdefault(category, {})
        shard = 1
        while int(counts.get(str(shard), 0)) >= HF_SHARD_FILE_LIMIT:
            shard += 1
        counts[str(shard)] = int(counts.get(str(shard), 0)) + 1
        index["updatedAt"] = now_iso()
        index["shardLimit"] = HF_SHARD_FILE_LIMIT
        self._storage_index_dirty = True
        return f"aurora/{category}{shard}/{uuid.uuid4().hex}{suffix}"

    def _recent_local_commits(self) -> list[float]:
        cutoff = time.time() - 3600
        try:
            payload = json.loads(HF_COMMIT_LEDGER_PATH.read_text(encoding="utf-8"))
            values = [float(value) for value in payload if float(value) >= cutoff]
        except Exception:
            values = []
        return sorted(values)

    def _save_local_commits(self, values: list[float]) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        HF_COMMIT_LEDGER_PATH.write_text(json.dumps(values[-HF_COMMIT_HOURLY_LIMIT:], indent=2), encoding="utf-8")

    def _check_commit_budget(self) -> None:
        values = self._recent_local_commits()
        if len(values) >= HF_COMMIT_SOFT_LIMIT:
            wait_seconds = max(1, int(values[0] + 3600 - time.time()))
            wait_minutes = max(1, (wait_seconds + 59) // 60)
            raise RuntimeError(
                f"Hugging Face commit güvenlik sınırına ulaşıldı: son saatte {len(values)} commit. "
                f"Platformun 128 commit/saat sınırını aşmamak için yaklaşık {wait_minutes} dakika bekleyin."
            )

    def _record_commit(self) -> None:
        values = self._recent_local_commits()
        values.append(time.time())
        self._save_local_commits(values)

    def _index_operation(self) -> CommitOperationAdd | None:
        if not self._storage_index_dirty or self._storage_index is None:
            return None
        if self._index_temp_path:
            self._index_temp_path.unlink(missing_ok=True)
        handle = tempfile.NamedTemporaryFile(prefix="aurora-hf-index-", suffix=".json", delete=False)
        handle.close()
        self._index_temp_path = Path(handle.name)
        self._index_temp_path.write_text(
            json.dumps(self._storage_index, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return CommitOperationAdd(path_in_repo=HF_STORAGE_INDEX_PATH, path_or_fileobj=str(self._index_temp_path))

    def _start_commit(self, operations: list[CommitOperationAdd], message: str):
        kwargs = {
            "repo_id": self.settings.hf_repo,
            "repo_type": self.settings.hf_repo_type,
            "operations": operations,
            "commit_message": message,
            "token": self.settings.hf_token,
        }
        try:
            return self.api.create_commit(**kwargs, run_as_future=True)
        except TypeError:
            executor = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="aurora-hf-upload")
            future = executor.submit(self.api.create_commit, **kwargs)
            future._aurora_executor = executor  # type: ignore[attr-defined]
            return future

    def create_commit(
        self,
        files: list[tuple[Path, str]],
        message: str,
        progress: Callable[[str], None] | None = None,
    ) -> None:
        self.ensure_repo()
        index_operation = self._index_operation()
        operations = [CommitOperationAdd(path_in_repo=remote, path_or_fileobj=str(local)) for local, remote in files]
        if index_operation:
            operations.append(index_operation)
        if not operations:
            return
        self._check_commit_budget()
        if progress:
            progress(f"{len(files)} medya dosyası tek Hugging Face commit'inde yükleniyor…")

        last_error: Exception | None = None
        for attempt in range(1, 3):
            future = self._start_commit(operations, message)
            started = time.monotonic()
            last_heartbeat = -1
            try:
                while not future.done():
                    elapsed = int(time.monotonic() - started)
                    if elapsed >= HF_UPLOAD_TIMEOUT_SECONDS:
                        future.cancel()
                        raise TimeoutError(
                            "Hugging Face yüklemesi 90 dakikalık güvenlik süresini aştı. "
                            "Depoda commit oluşup oluşmadığını kontrol ettikten sonra yeniden deneyin."
                        )
                    heartbeat = elapsed // 15
                    if progress and heartbeat != last_heartbeat:
                        last_heartbeat = heartbeat
                        progress(
                            f"Hugging Face yüklemesi sürüyor… {elapsed // 60:02d}:{elapsed % 60:02d} geçti "
                            f"(deneme {attempt}/2)"
                        )
                    time.sleep(2)
                future.result()
                self._record_commit()
                self._storage_index_dirty = False
                if progress:
                    progress("Hugging Face commit'i tamamlandı.")
                return
            except TimeoutError:
                raise
            except Exception as exc:
                last_error = exc
                text = str(exc).lower()
                transient = any(code in text for code in ("429", "502", "503", "504", "connection", "temporarily", "timeout"))
                if not transient or attempt >= 2:
                    if "429" in text:
                        raise RuntimeError(
                            "Hugging Face saatlik commit veya hız sınırına ulaştı. Bir süre bekleyip yeniden deneyin."
                        ) from exc
                    raise RuntimeError(f"Hugging Face yüklemesi başarısız: {exc}") from exc
                if progress:
                    progress(f"Geçici Hugging Face hatası: {exc}. 8 saniye sonra tek kez yeniden deneniyor…")
                time.sleep(8)
            finally:
                executor = getattr(future, "_aurora_executor", None)
                if executor:
                    executor.shutdown(wait=False, cancel_futures=True)
        if last_error:
            raise last_error

    def upload_one(
        self,
        local_path: Path,
        remote_path: str,
        message: str,
        progress: Callable[[str], None] | None = None,
    ) -> str:
        self.create_commit([(local_path, remote_path)], message, progress=progress)
        return self.resolve_url(remote_path)

    def resolve_url(self, path: str) -> str:
        kind = "datasets" if self.settings.hf_repo_type == "dataset" else "models"
        return f"https://huggingface.co/{kind}/{self.settings.hf_repo}/resolve/main/{quote(path, safe='/')}"

    def test(self) -> str:
        who = self.api.whoami(token=self.settings.hf_token)
        values = self._recent_local_commits()
        return (
            f"Hugging Face bağlantısı başarılı: {who.get('name', 'hesap')} • "
            f"Aurora son saatte {len(values)}/{HF_COMMIT_SOFT_LIMIT} güvenli commit kullandı"
        )


class MediaProcessor:'''

source, count = re.subn(
    r'class HuggingFaceStorage:.*?\n\nclass MediaProcessor:',
    new_storage,
    source,
    count=1,
    flags=re.DOTALL,
)
if count != 1:
    raise RuntimeError("HuggingFaceStorage sınıfı bulunamadı veya birden fazla eşleşti")

replacements = {
    'remote = f"aurora/artwork/{uuid.uuid4().hex}{ext}"': 'remote = storage.allocate_remote("artwork", ext, lambda status: progress(status, 4))',
    'remote = f"aurora/animated/{uuid.uuid4().hex}{ext}"': 'remote = storage.allocate_remote("animated", ext, lambda status: progress(status, 4))',
    'master_remote = f"aurora/masters/{uuid.uuid4().hex}{row.path.suffix.lower()}"': 'master_remote = storage.allocate_remote("masters", row.path.suffix.lower(), lambda status: progress(status, base))',
    'remote = f"aurora/audio/{uuid.uuid4().hex}{ext}"': 'remote = storage.allocate_remote("audio", ext, lambda status: progress(status, base))',
    'remote = f"aurora/atmos/{uuid.uuid4().hex}{row.atmos_path.suffix.lower()}"': 'remote = storage.allocate_remote("atmos", row.atmos_path.suffix.lower(), lambda status: progress(status, base))',
    'storage.create_commit(upload_files, f"Aurora Music: {request.release_title}")': 'storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status: progress(status, 76))',
    'remote = f"aurora/{category}/{uuid.uuid4().hex}{path.suffix.lower()}"\n            url = HuggingFaceStorage(settings).upload_one(path, remote, f"Aurora asset: {category}")': 'storage = HuggingFaceStorage(settings)\n            remote = storage.allocate_remote(category, path.suffix.lower(), lambda status: progress(status, 25))\n            url = storage.upload_one(path, remote, f"Aurora asset: {category}", progress=lambda status: progress(status, 60))',
}
for old, new in replacements.items():
    if old not in source:
        raise RuntimeError(f"Beklenen kaynak parçası bulunamadı: {old[:80]}")
    source = source.replace(old, new, 1)

SOURCE.write_text(source, encoding="utf-8")

readme = README.read_text(encoding="utf-8")
readme = readme.replace("# Aurora Studio v0.2.0", "# Aurora Studio v0.2.1")
section = '''

## Hugging Face klasör shard ve commit koruması

- Yeni dosyalar medya türüne göre `audio1`, `audio2`, `masters1`, `artwork1`, `animated1`, `atmos1`, `artist-profile1` gibi klasörlere ayrılır.
- Her shard için güvenli sınır 9.000 öğedir. Bir toplu yükleme kalan kapasiteyi aşarsa aynı işlem içinde otomatik olarak sonraki shard'a geçilir.
- İlk kullanımda mevcut numaralı shard'lar bir kez taranır; daha sonra `aurora/.aurora-storage-index.json` aynı medya commit'i içinde güncellenir.
- Bir yayın içindeki tüm yeni medya dosyaları tek Hugging Face commit'i olarak gönderilir.
- Aurora yerel olarak son bir saatteki commitleri takip eder ve 128 commit/saat platform sınırından önce, 120 committe yeni yüklemeyi güvenli biçimde durdurur.
- Xet varsayılan olarak kapalıdır; Windows'ta daha kararlı Git LFS yükleme yolu kullanılır.
- Yükleme sırasında her 15 saniyede bir geçen süre gösterilir. Geçici ağ hatası bir kez yeniden denenir; belirsiz sonsuz bekleme kaldırılmıştır.
'''
if "## Hugging Face klasör shard ve commit koruması" not in readme:
    readme += section
README.write_text(readme, encoding="utf-8")

workflow = WORKFLOW.read_text(encoding="utf-8")
workflow = workflow.replace("Aurora Studio Windows v0.2.0", "Aurora Studio Windows v0.2.1")
workflow = workflow.replace("aurora-studio-windows-v0.2.0", "aurora-studio-windows-v0.2.1")
workflow = workflow.replace("Aurora-Studio-Windows-x64-v0.2.0.zip", "Aurora-Studio-Windows-x64-v0.2.1.zip")
workflow = workflow.replace("aurora-studio-v0.2.0", "aurora-studio-v0.2.1")
workflow = workflow.replace(
    "ISRC tekrar engeli, sıralı toplu master eşleştirme, Spotify metadata/ana sanatçı/feat sanatçı aktarımı, isteğe bağlı LRCLIB söz araması ve GitHub-Hugging Face yayın akışı.",
    "9.000 öğelik otomatik Hugging Face klasör shard sistemi, tek-commit yayın akışı, 128 commit/saat koruması, yükleme zaman aşımı/heartbeat, ISRC tekrar engeli ve Spotify metadata aktarımı.",
)
WORKFLOW.write_text(workflow, encoding="utf-8")

print("Aurora Studio v0.2.1 Hugging Face shard yaması uygulandı.")
