# Aurora Studio v0.2.1

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


## Hugging Face klasör shard ve commit koruması

- Yeni dosyalar medya türüne göre `audio1`, `audio2`, `masters1`, `artwork1`, `animated1`, `atmos1`, `artist-profile1` gibi klasörlere ayrılır.
- Her shard için güvenli sınır 9.000 öğedir. Bir toplu yükleme kalan kapasiteyi aşarsa aynı işlem içinde otomatik olarak sonraki shard'a geçilir.
- İlk kullanımda mevcut numaralı shard'lar bir kez taranır; daha sonra `aurora/.aurora-storage-index.json` aynı medya commit'i içinde güncellenir.
- Bir yayın içindeki tüm yeni medya dosyaları tek Hugging Face commit'i olarak gönderilir.
- Aurora yerel olarak son bir saatteki commitleri takip eder ve 128 commit/saat platform sınırından önce, 120 committe yeni yüklemeyi güvenli biçimde durdurur.
- Xet varsayılan olarak kapalıdır; Windows'ta daha kararlı Git LFS yükleme yolu kullanılır.
- Yükleme sırasında her 15 saniyede bir geçen süre gösterilir. Geçici ağ hatası bir kez yeniden denenir; belirsiz sonsuz bekleme kaldırılmıştır.
