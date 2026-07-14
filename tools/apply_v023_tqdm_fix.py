from pathlib import Path

path = Path("pc/AuroraStudio.py")
text = path.read_text(encoding="utf-8")

old_version = 'APP_VERSION = "0.2.2"'
new_version = 'APP_VERSION = "0.2.3"'
if old_version not in text:
    raise SystemExit("Beklenen v0.2.2 sürüm satırı bulunamadı")
text = text.replace(old_version, new_version, 1)

old_env = '''# Hugging Face'in Xet/LFS seçimi otomatik bırakılır. Aurora Studio Xet'i kapatmaz.
# Yalnızca indirme zaman aşımı için güvenli bir varsayılan kullanılır.
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
'''
new_env = '''# Hugging Face'in Xet/LFS seçimi otomatik bırakılır. Aurora Studio Xet'i kapatmaz.
# PyInstaller --windowed uygulamasında stdout/stderr bulunmadığı için Hugging Face'in
# terminal tabanlı tqdm çubuğu kapatılır; ilerleme Aurora arayüzünde gösterilir.
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
'''
if old_env not in text:
    raise SystemExit("Beklenen Hugging Face ortam ayarları bulunamadı")
text = text.replace(old_env, new_env, 1)

path.write_text(text, encoding="utf-8")
print("Aurora Studio v0.2.3 tqdm düzeltmesi uygulandı")
