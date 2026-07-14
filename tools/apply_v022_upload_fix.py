from __future__ import annotations

import re
from pathlib import Path

SOURCE = Path("pc/AuroraStudio.py")
README = Path("pc/README.md")

source = SOURCE.read_text(encoding="utf-8")

if 'APP_VERSION = "0.2.1"' not in source:
    raise RuntimeError("Beklenen Aurora Studio v0.2.1 sürümü bulunamadı")
source = source.replace('APP_VERSION = "0.2.1"', 'APP_VERSION = "0.2.2"', 1)

old_xet = '''# Xet bazı Windows kurulumlarında yüklemeyi cevapsız bırakabildiği için Aurora Studio
# varsayılan olarak daha öngörülebilir Git LFS yolunu kullanır. Kullanıcı ortam
# değişkenini önceden ayarladıysa mevcut tercih korunur.
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
'''
new_xet = '''# Hugging Face'in Xet/LFS seçimi otomatik bırakılır. Aurora Studio Xet'i kapatmaz.
# Yalnızca indirme zaman aşımı için güvenli bir varsayılan kullanılır.
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
'''
if old_xet not in source:
    raise RuntimeError("Xet'i zorla kapatan eski ayar bulunamadı")
source = source.replace(old_xet, new_xet, 1)

old_constants = '''HF_COMMIT_SOFT_LIMIT = 120
HF_UPLOAD_TIMEOUT_SECONDS = 90 * 60
'''
new_constants = '''HF_COMMIT_SOFT_LIMIT = 120
HF_UPLOAD_TIMEOUT_SECONDS = 90 * 60
HF_FILE_UPLOAD_TIMEOUT_SECONDS = 60 * 60
HF_COMMIT_FINALIZE_TIMEOUT_SECONDS = 15 * 60
'''
if old_constants not in source:
    raise RuntimeError("Hugging Face yükleme sabitleri bulunamadı")
source = source.replace(old_constants, new_constants, 1)

new_create_commit = r'''    @staticmethod
    def _human_size(size: int) -> str:
        value = float(max(0, size))
        units = ["B", "KB", "MB", "GB", "TB"]
        unit = units[0]
        for unit in units:
            if value < 1024 or unit == units[-1]:
                break
            value /= 1024
        return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"

    def create_commit(
        self,
        files: list[tuple[Path, str]],
        message: str,
        progress: Callable[[str, int], None] | None = None,
    ) -> None:
        self.ensure_repo()
        index_operation = self._index_operation()
        if not files and index_operation is None:
            return
        self._check_commit_budget()

        total_bytes = sum(local.stat().st_size for local, _remote in files)
        completed_bytes = 0
        operations: list[CommitOperationAdd] = []
        upload_started = time.monotonic()

        if progress:
            progress(
                f"Hugging Face Xet yüklemesi hazırlanıyor: {len(files)} dosya • "
                f"toplam {self._human_size(total_bytes)} • finalde tek commit",
                0,
            )

        for file_index, (local, remote) in enumerate(files, start=1):
            file_size = local.stat().st_size
            operation: CommitOperationAdd | None = None
            last_error: Exception | None = None

            for attempt in range(1, 3):
                operation = CommitOperationAdd(path_in_repo=remote, path_or_fileobj=str(local))
                if progress:
                    progress(
                        f"Dosya {file_index}/{len(files)}: {local.name} "
                        f"({self._human_size(file_size)}) Xet ile yükleniyor • deneme {attempt}/2 • "
                        f"tamamlanan {self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                        int((completed_bytes / max(total_bytes, 1)) * 92),
                    )

                executor = concurrent.futures.ThreadPoolExecutor(
                    max_workers=1,
                    thread_name_prefix="aurora-hf-xet-upload",
                )
                future = executor.submit(
                    self.api.preupload_lfs_files,
                    repo_id=self.settings.hf_repo,
                    repo_type=self.settings.hf_repo_type,
                    additions=[operation],
                    token=self.settings.hf_token,
                    num_threads=1,
                    free_memory=True,
                )
                file_started = time.monotonic()
                last_heartbeat = -1
                try:
                    while not future.done():
                        elapsed_file = int(time.monotonic() - file_started)
                        elapsed_total = int(time.monotonic() - upload_started)
                        if elapsed_file >= HF_FILE_UPLOAD_TIMEOUT_SECONDS:
                            future.cancel()
                            raise TimeoutError(
                                f"{local.name} dosyası 60 dakika boyunca tamamlanamadı. "
                                "Xet yüklemesi durduruldu; bağlantıyı kontrol edip yeniden deneyin."
                            )
                        if elapsed_total >= HF_UPLOAD_TIMEOUT_SECONDS:
                            future.cancel()
                            raise TimeoutError(
                                "Hugging Face yüklemesi 90 dakikalık toplam güvenlik süresini aştı."
                            )
                        heartbeat = elapsed_file // 5
                        if progress and heartbeat != last_heartbeat:
                            last_heartbeat = heartbeat
                            progress(
                                f"Dosya {file_index}/{len(files)}: {local.name} "
                                f"({self._human_size(file_size)}) Xet ile işleniyor/yükleniyor • "
                                f"{elapsed_file // 60:02d}:{elapsed_file % 60:02d} • "
                                f"tamamlanan {self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                                int((completed_bytes / max(total_bytes, 1)) * 92),
                            )
                        time.sleep(1)
                    future.result()
                    last_error = None
                    break
                except Exception as exc:
                    last_error = exc
                    text = str(exc).lower()
                    transient = any(
                        marker in text
                        for marker in (
                            "429", "500", "502", "503", "504", "connection",
                            "temporarily", "timeout", "timed out",
                        )
                    )
                    if not transient or attempt >= 2:
                        raise
                    if progress:
                        progress(
                            f"{local.name} geçici Hugging Face hatası verdi. "
                            f"8 saniye sonra Xet ile son kez denenecek: {exc}",
                            int((completed_bytes / max(total_bytes, 1)) * 92),
                        )
                    time.sleep(8)
                finally:
                    executor.shutdown(wait=False, cancel_futures=True)

            if last_error is not None or operation is None:
                raise RuntimeError(f"{local.name} yüklenemedi: {last_error}")

            operations.append(operation)
            completed_bytes += file_size
            if progress:
                progress(
                    f"Dosya {file_index}/{len(files)} tamamlandı: {local.name} • "
                    f"{self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                    int((completed_bytes / max(total_bytes, 1)) * 92),
                )

        if index_operation:
            operations.append(index_operation)

        last_error: Exception | None = None
        for attempt in range(1, 3):
            future = self._start_commit(operations, message)
            commit_started = time.monotonic()
            last_heartbeat = -1
            try:
                while not future.done():
                    elapsed = int(time.monotonic() - commit_started)
                    if elapsed >= HF_COMMIT_FINALIZE_TIMEOUT_SECONDS:
                        future.cancel()
                        raise TimeoutError(
                            "Medya verileri gönderildi ancak Hugging Face final commit işlemi "
                            "15 dakika içinde tamamlanamadı."
                        )
                    heartbeat = elapsed // 5
                    if progress and heartbeat != last_heartbeat:
                        last_heartbeat = heartbeat
                        progress(
                            f"Tüm dosyalar Xet ile gönderildi; tek Hugging Face commit'i oluşturuluyor • "
                            f"{elapsed // 60:02d}:{elapsed % 60:02d} • deneme {attempt}/2",
                            96,
                        )
                    time.sleep(1)
                future.result()
                self._record_commit()
                self._storage_index_dirty = False
                if progress:
                    progress("Hugging Face Xet yüklemesi ve tek commit tamamlandı.", 100)
                return
            except Exception as exc:
                last_error = exc
                text = str(exc).lower()
                transient = any(
                    marker in text
                    for marker in (
                        "429", "500", "502", "503", "504", "connection",
                        "temporarily", "timeout", "timed out",
                    )
                )
                if not transient or attempt >= 2:
                    if "429" in text:
                        raise RuntimeError(
                            "Hugging Face saatlik commit veya hız sınırına ulaştı. "
                            "Bir süre bekleyip yeniden deneyin."
                        ) from exc
                    raise RuntimeError(f"Hugging Face yüklemesi başarısız: {exc}") from exc
                if progress:
                    progress(
                        f"Final commit geçici hata verdi. 8 saniye sonra son kez deneniyor: {exc}",
                        96,
                    )
                time.sleep(8)
            finally:
                executor = getattr(future, "_aurora_executor", None)
                if executor:
                    executor.shutdown(wait=False, cancel_futures=True)

        if last_error:
            raise last_error

'''

pattern = re.compile(
    r"    def create_commit\(\n.*?(?=    def upload_one\()",
    flags=re.DOTALL,
)
source, count = pattern.subn(lambda _match: new_create_commit, source, count=1)
if count != 1:
    raise RuntimeError(f"HuggingFaceStorage.create_commit bloğu eşleşmedi: {count}")

new_upload_one = r'''    def upload_one(
        self,
        local_path: Path,
        remote_path: str,
        message: str,
        progress: Callable[[str, int], None] | None = None,
    ) -> str:
        self.create_commit([(local_path, remote_path)], message, progress=progress)
        return self.resolve_url(remote_path)

'''
source, count = re.subn(
    r"    def upload_one\(\n.*?(?=    def resolve_url\()",
    lambda _match: new_upload_one,
    source,
    count=1,
    flags=re.DOTALL,
)
if count != 1:
    raise RuntimeError(f"HuggingFaceStorage.upload_one bloğu eşleşmedi: {count}")

old_publish = 'storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status: progress(status, 76))'
new_publish = 'storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status, percent: progress(status, 72 + int(percent * 0.18)))'
if old_publish not in source:
    raise RuntimeError("Yayın yükleme progress callback'i bulunamadı")
source = source.replace(old_publish, new_publish, 1)

old_asset = 'url = storage.upload_one(path, remote, f"Aurora asset: {category}", progress=lambda status: progress(status, 60))'
new_asset = 'url = storage.upload_one(path, remote, f"Aurora asset: {category}", progress=lambda status, percent: progress(status, 25 + int(percent * 0.70)))'
if old_asset not in source:
    raise RuntimeError("Tekil asset yükleme progress callback'i bulunamadı")
source = source.replace(old_asset, new_asset, 1)

SOURCE.write_text(source, encoding="utf-8")

readme = README.read_text(encoding="utf-8")
readme = readme.replace("# Aurora Studio v0.2.1", "# Aurora Studio v0.2.2", 1)
readme += '''\n\n## v0.2.2 Hugging Face yükleme düzeltmesi\n\n- Xet teknolojisi açık ve otomatik seçimde kalır.\n- Yalnızca yükleme görünürlüğü ve kararlılığı değiştirilmiştir.\n- Her dosyanın adı, boyutu, sırası, geçen süresi ve tamamlanan toplam boyut gösterilir.\n- Finalde yayın başına tek Hugging Face commit oluşturulur.\n- Shard klasörleri ve 120/128 commit güvenlik koruması aynen korunur.\n'''
README.write_text(readme, encoding="utf-8")

print("Aurora Studio v0.2.2 minimal Xet upload fix applied")
