# Aurora Studio v0.2.0

Aurora Music kataloğunu yöneten Windows masaüstü uygulamasıdır.

## Yeni yayın akışı

1. Ayarlar bölümünde GitHub, Hugging Face ve Spotify API bilgilerini kaydedin.
2. Yeni Yayın bölümüne Spotify albüm veya şarkı bağlantısını yapıştırın.
3. Metadata, kapak, tarih, ISRC, ana sanatçılar ve feat sanatçılar otomatik alınır.
4. Master dosyalarını topluca seçin veya klasörü sıralı ekleyin. Dosyalar Spotify parça sırasına eşleştirilir.
5. Aynı normalize edilmiş ISRC katalogda varsa ses yeniden dönüştürülmez ve Hugging Face'e tekrar yüklenmez; mevcut track_id yayına eklenir.
6. Yeni kayıtlar sırayla işlenir, tek Hugging Face commit'iyle yüklenir ve katalog GitHub'a commit edilir.

Spotify Web API söz sağlamaz. “Sözleri otomatik ara” açıksa Aurora Studio uygun eşleşmelerde LRCLIB üzerinden normal ve senkronize sözleri getirir; sonuç yayınlamadan önce düzenlenebilir.

## Feat sanatçılar

Spotify albüm sanatçıları ana sanatçı, parçadaki ek sanatçılar feat olarak işaretlenir. Her şarkının “Detay / Feat / Söz” ekranında roller elle değiştirilebilir.
