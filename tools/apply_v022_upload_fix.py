from __future__ import annotations

import re
from pathlib import Path

SOURCE = Path('pc/AuroraStudio.py')
README = Path('pc/README.md')

source = SOURCE.read_text(encoding='utf-8')

source = source.replace('APP_VERSION = "0.2.1"', 'APP_VERSION = "0.2.2"', 1)

old_xet = '''# Xet bazı Windows kurulumlarında yüklemeyi cevapsız bırakabildiği için Aurora Studio
# varsayılan olarak daha öngörülebilir Git LFS yolunu kullanır. Kullanıcı ortam
# değişkenini önceden ayarladıysa mevcut tercih korunur.
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
'''
new_xet = '''# Hugging Face'in otomatik Xet/LFS seçimine izin verilir. Xet'i zorla kapatmak
# büyük ses dosyalarında ciddi yavaşlamaya yol açabildiği için yalnızca kullanıcı
# kendi ortamında HF_HUB_DISABLE_XET ayarlarsa uyumluluk modu kullanılır.
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
'''
if old_xet not in source:
    raise RuntimeError('Eski Xet ayarı bulunamadı')
source = source.replace(old_xet, new_xet, 1)

old_constants = '''HF_COMMIT_SOFT_LIMIT = 120
HF_UPLOAD_TIMEOUT_SECONDS = 90 * 60
'''
new_constants = '''HF_COMMIT_SOFT_LIMIT = 120
HF_UPLOAD_TIMEOUT_SECONDS = 3 * 60 * 60
HF_FILE_UPLOAD_TIMEOUT_SECONDS = 60 * 60
HF_COMMIT_FINALIZE_TIMEOUT_SECONDS = 15 * 60
'''
if old_constants not in source:
    raise RuntimeError('Yükleme sabitleri bulunamadı')
source = source.replace(old_constants, new_constants, 1)

worker = r'''

def _hf_upload_process_worker(
    settings_data: dict[str, Any],
    files: list[tuple[str, str]],
    index_file: str | None,
    message: str,
    event_queue: Any,
) -> None:
    """Medya ön yüklemelerini ve tek final commit'i öldürülebilir alt süreçte yapar."""
    try:
        api = HfApi(token=settings_data.get("hf_token", ""))
        repo_id = settings_data["hf_repo"]
        repo_type = settings_data.get("hf_repo_type", "dataset")
        token = settings_data.get("hf_token", "")
        prepared: list[CommitOperationAdd] = []
        total_bytes = sum(Path(local).stat().st_size for local, _remote in files)
        completed_bytes = 0

        for file_index, (local, remote) in enumerate(files, start=1):
            path = Path(local)
            size = path.stat().st_size
            operation: CommitOperationAdd | None = None
            for attempt in range(1, 3):
                event_queue.put((
                    "file_start", file_index, len(files), path.name, size,
                    completed_bytes, total_bytes, attempt,
                ))
                operation = CommitOperationAdd(path_in_repo=remote, path_or_fileobj=str(path))
                try:
                    api.preupload_lfs_files(
                        repo_id=repo_id,
                        repo_type=repo_type,
                        additions=[operation],
                        token=token,
                        num_threads=1,
                        free_memory=True,
                    )
                    break
                except Exception as exc:
                    text = str(exc).lower()
                    transient = any(
                        marker in text
                        for marker in ("429", "500", "502", "503", "504", "connection", "temporarily", "timeout")
                    )
                    if not transient or attempt >= 2:
                        raise
                    event_queue.put(("retry", file_index, path.name, str(exc), attempt + 1))
                    time.sleep(8)
            if operation is None:
                raise RuntimeError(f"{path.name} için yükleme işlemi oluşturulamadı")
            prepared.append(operation)
            completed_bytes += size
            event_queue.put((
                "file_done", file_index, len(files), path.name, size,
                completed_bytes, total_bytes,
            ))

        if index_file:
            prepared.append(
                CommitOperationAdd(path_in_repo=HF_STORAGE_INDEX_PATH, path_or_fileobj=index_file)
            )

        event_queue.put(("commit_start", len(files), total_bytes))
        result = api.create_commit(
            repo_id=repo_id,
            repo_type=repo_type,
            operations=prepared,
            commit_message=message,
            token=token,
            num_threads=1,
        )
        event_queue.put(("done", getattr(result, "oid", ""), getattr(result, "commit_url", "")))
    except BaseException as exc:
        event_queue.put(("error", str(exc), traceback.format_exc()))
'''

marker = '\n\nclass HuggingFaceStorage:'
if marker not in source:
    raise RuntimeError('HuggingFaceStorage sınıfı bulunamadı')
source = source.replace(marker, worker + marker, 1)

new_methods = r'''    @staticmethod
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
        index_file = str(index_operation.path_or_fileobj) if index_operation else None
        if not files and not index_file:
            return
        self._check_commit_budget()

        total_bytes = sum(path.stat().st_size for path, _remote in files)
        if progress:
            progress(
                f"Hazırlanıyor: {len(files)} medya dosyası • toplam {self._human_size(total_bytes)} • finalde tek commit",
                0,
            )

        import multiprocessing
        import queue

        context = multiprocessing.get_context("spawn")
        event_queue = context.Queue()
        settings_data = {
            "hf_repo": self.settings.hf_repo,
            "hf_repo_type": self.settings.hf_repo_type,
            "hf_token": self.settings.hf_token,
        }
        process = context.Process(
            target=_hf_upload_process_worker,
            args=(
                settings_data,
                [(str(local), remote) for local, remote in files],
                index_file,
                message,
                event_queue,
            ),
            name="AuroraHuggingFaceUpload",
        )
        process.start()

        started = time.monotonic()
        current_started = started
        current_index = 0
        current_total = len(files)
        current_name = ""
        current_size = 0
        completed_bytes = 0
        finished = False
        last_status_second = -1
        error_message = ""
        error_traceback = ""

        try:
            while process.is_alive() or not event_queue.empty():
                try:
                    event = event_queue.get(timeout=1)
                except queue.Empty:
                    event = None

                if event:
                    kind = event[0]
                    if kind == "file_start":
                        _, current_index, current_total, current_name, current_size, completed_bytes, total_bytes, attempt = event
                        current_started = time.monotonic()
                        if progress:
                            base_percent = int((completed_bytes / max(total_bytes, 1)) * 92)
                            progress(
                                f"Dosya {current_index}/{current_total}: {current_name} ({self._human_size(current_size)}) "
                                f"yükleniyor • deneme {attempt}/2 • tamamlanan "
                                f"{self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                                base_percent,
                            )
                    elif kind == "retry":
                        _, file_index, file_name, reason, next_attempt = event
                        if progress:
                            progress(
                                f"{file_name} geçici hata verdi; {next_attempt}/2 deneniyor: {reason}",
                                int((completed_bytes / max(total_bytes, 1)) * 92),
                            )
                    elif kind == "file_done":
                        _, current_index, current_total, current_name, current_size, completed_bytes, total_bytes = event
                        if progress:
                            progress(
                                f"Dosya {current_index}/{current_total} tamamlandı: {current_name} • "
                                f"{self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                                int((completed_bytes / max(total_bytes, 1)) * 92),
                            )
                        current_name = ""
                    elif kind == "commit_start":
                        if progress:
                            progress(
                                "Tüm medya verileri gönderildi; Hugging Face üzerinde tek commit oluşturuluyor…",
                                96,
                            )
                        current_name = "__commit__"
                        current_started = time.monotonic()
                    elif kind == "done":
                        finished = True
                        if progress:
                            progress("Hugging Face yüklemesi ve tek commit tamamlandı.", 100)
                    elif kind == "error":
                        _, error_message, error_traceback = event

                elapsed_total = int(time.monotonic() - started)
                if elapsed_total >= HF_UPLOAD_TIMEOUT_SECONDS:
                    raise TimeoutError(
                        "Hugging Face işlemi 3 saatlik toplam güvenlik süresini aştı ve durduruldu."
                    )

                if current_name:
                    elapsed_current = int(time.monotonic() - current_started)
                    timeout = (
                        HF_COMMIT_FINALIZE_TIMEOUT_SECONDS
                        if current_name == "__commit__"
                        else HF_FILE_UPLOAD_TIMEOUT_SECONDS
                    )
                    if elapsed_current >= timeout:
                        stage = "final commit" if current_name == "__commit__" else current_name
                        raise TimeoutError(
                            f"Hugging Face aşaması yanıt vermedi ve durduruldu: {stage}. "
                            "Yeniden çalıştırıldığında daha önce gönderilen içerikler tekrar aktarılmadan kullanılabilir."
                        )
                    if progress and elapsed_current // 5 != last_status_second:
                        last_status_second = elapsed_current // 5
                        if current_name == "__commit__":
                            progress(
                                f"Final commit sunucuda hazırlanıyor • {elapsed_current // 60:02d}:{elapsed_current % 60:02d}",
                                96,
                            )
                        else:
                            progress(
                                f"Dosya {current_index}/{current_total}: {current_name} ({self._human_size(current_size)}) "
                                f"işleniyor/yükleniyor • {elapsed_current // 60:02d}:{elapsed_current % 60:02d} • "
                                f"tamamlanan {self._human_size(completed_bytes)}/{self._human_size(total_bytes)}",
                                int((completed_bytes / max(total_bytes, 1)) * 92),
                            )

            process.join(timeout=5)
            if error_message:
                raise RuntimeError(
                    f"Hugging Face yüklemesi başarısız: {error_message}\n\n{error_traceback[-3000:]}"
                )
            if process.exitcode not in {0, None}:
                raise RuntimeError(f"Hugging Face yükleme işlemi beklenmedik biçimde kapandı (kod {process.exitcode}).")
            if not finished:
                raise RuntimeError("Hugging Face işlemi tamamlanmadan kapandı; commit oluşturulmadı.")

            self._record_commit()
            self._storage_index_dirty = False
        except BaseException:
            if process.is_alive():
                process.terminate()
                process.join(timeout=10)
            raise
        finally:
            try:
                event_queue.close()
                event_queue.join_thread()
            except Exception:
                pass

'''

pattern = re.compile(
    r'    def _start_commit\(.*?(?=    def upload_one\()',
    flags=re.DOTALL,
)
source, count = pattern.subn(lambda _m: new_methods, source, count=1)
if count != 1:
    raise RuntimeError(f'Eski create_commit bloğu eşleşmedi: {count}')

source = source.replace(
    'progress: Callable[[str], None] | None = None,\n    ) -> str:\n        self.create_commit([(local_path, remote_path)], message, progress=progress)',
    'progress: Callable[[str, int], None] | None = None,\n    ) -> str:\n        self.create_commit([(local_path, remote_path)], message, progress=progress)',
    1,
)

old_publish = 'storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status: progress(status, 76))'
new_publish = 'storage.create_commit(upload_files, f"Aurora Music: {request.release_title}", progress=lambda status, percent: progress(status, 72 + int(percent * 0.18)))'
if old_publish not in source:
    raise RuntimeError('Yayın progress callback bulunamadı')
source = source.replace(old_publish, new_publish, 1)

old_asset = 'url = storage.upload_one(path, remote, f"Aurora asset: {category}", progress=lambda status: progress(status, 60))'
new_asset = 'url = storage.upload_one(path, remote, f"Aurora asset: {category}", progress=lambda status, percent: progress(status, 25 + int(percent * 0.70)))'
if old_asset not in source:
    raise RuntimeError('Asset progress callback bulunamadı')
source = source.replace(old_asset, new_asset, 1)

old_main = '''if __name__ == "__main__":
    main()
'''
new_main = '''if __name__ == "__main__":
    import multiprocessing

    multiprocessing.freeze_support()
    main()
'''
if old_main not in source:
    raise RuntimeError('main guard bulunamadı')
source = source.replace(old_main, new_main, 1)

SOURCE.write_text(source, encoding='utf-8')

readme = README.read_text(encoding='utf-8')
readme = readme.replace('# Aurora Studio v0.2.1', '# Aurora Studio v0.2.2', 1)
readme += '''\n\n## v0.2.2 yükleme görünürlüğü\n\n- Xet artık zorla kapatılmaz; Hugging Face otomatik en uygun yolu seçer.\n- Dosyalar final commit öncesinde tek tek ön yüklenir ve her dosyanın adı, boyutu, sırası ve tamamlanan toplam boyut gösterilir.\n- Medya dosyaları ön yüklenmiş olsa da albüm başına yalnızca bir Hugging Face commit oluşturulur.\n- Yükleme ayrı bir süreçte çalışır; aşama zaman aşımında gerçekten sonlandırılabilir.\n- Sabit ve yanıltıcı yüzde yerine dosya bazlı gerçek tamamlanma oranı gösterilir.\n'''
README.write_text(readme, encoding='utf-8')

print('Aurora Studio v0.2.2 upload patch applied')
