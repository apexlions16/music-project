# Müzik Projesi

GitHub üzerinde sürümlenen katalog verisini okuyup Hugging Face veya başka bir doğrudan dosya kaynağındaki müzikleri oynatan, tamamen Türkçe Android uygulaması.

## Özellikler

- Kullanıcı hesabı ve giriş ekranı yoktur.
- Apple Music hissine yakın, özgün Jetpack Compose telefon arayüzü.
- Ana Sayfa, Göz At, Sanatçılar ve Arama sekmeleri.
- Altta mini oynatıcı ve tam ekran oynatıcı.
- Normal şarkı sözleri, künye ve kalite bilgileri.
- Yüksek Kalite, Lossless, Hi-Res Lossless ve Dolby Atmos katalog rozetleri.
- Kullanıcının istediği kalite sürümünü seçebilmesi.
- Şarkıyı cihazın Müzik klasörüne indirebilme.
- Aynı şarkının single, EP ve albüm içinde tekrar dosya yüklemeden kullanılabilmesi.
- Katalog çevrimdışı önbelleği.
- APK'yı otomatik derleyen ve GitHub Release oluşturan Actions akışı.

## Katalog

Uygulama şu dosyayı canlı veritabanı olarak okur:

`catalog/catalog.json`

Şarkılar bağımsız kayıtlardır. Yayınların `tracks` dizisi, şarkıları `trackId` ile bağlar. Bu nedenle aynı şarkı hem single hem de albüm içinde gösterilebilir.

### Ses kalitesi türleri

- `standard`: Standart
- `high`: Yüksek Kalite
- `lossless`: Lossless
- `hires`: Hi-Res Lossless
- `dolby_atmos`: Dolby Atmos

Dolby Atmos rozeti yalnızca gerçekten Atmos uyumlu bir dosya/akış varsa eklenmelidir. Uygulama metadata rozetini gösterir; gerçek oynatma desteği cihaz ve dosya codec desteğine bağlıdır.

### Kapak görseli

`cover`, `image` ve `heroImage` alanlarına doğrudan HTTPS görsel bağlantısı yapıştırılabilir. Görselin bu repoda bulunması zorunlu değildir.

### Hugging Face dosyası

Bir Hugging Face dosyasının herkese açık `/resolve/main/...` bağlantısı, şarkının `sources[].url` ve isteğe bağlı `downloadUrl` alanına yazılır.

## Android derleme

Yerel olarak Android Studio ile açabilir veya Gradle 8.9 kullanabilirsin:

```bash
gradle assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

`main` dalındaki Android kaynakları değiştiğinde GitHub Actions otomatik olarak `v0.1.0` Release'ini oluşturur.

## Yönetim

Uygulamanın sağ üst menüsündeki **Yönetim Paneli**, katalog düzenleme, şema ve repo bağlantılarına erişim verir. GitHub ve Hugging Face anahtarları APK içine gömülmez.
