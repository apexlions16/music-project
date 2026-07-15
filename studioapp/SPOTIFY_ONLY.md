# Aurora Studio Mobile — Spotify-only Metadata

Aurora Studio Mobile v0.3.0 otomatik metadata için yalnızca Spotify Web API kullanır.

## Desteklenen girişler

- Spotify albüm bağlantısı
- Spotify şarkı bağlantısı
- Spotify URI (`spotify:album:...` veya `spotify:track:...`)
- Albüm ya da şarkı adıyla düz metin araması

Şarkı bağlantısı girildiğinde Spotify üzerindeki bağlı albüm çözülür. Albüm parçaları tam track kayıtlarıyla alınarak başlık, ana sanatçı, feat sanatçılar, sıralama, süre, explicit bilgisi ve mevcutsa ISRC doldurulur.

## Görseller

Spotify albüm kapağı ve erişilebilen sanatçı görselleri katalogda dış CDN adresi olarak bırakılmaz:

1. Görsel Spotify adresinden indirilir.
2. Hugging Face dataset deposuna yüklenir.
3. GitHub kataloğuna yalnızca Hugging Face adresi yazılır.

Kullanıcının elle girdiği hareketli kapak veya arka plan videosu URL olarak tutulur.

## Spotify'ın sağlamadığı alanlar

Spotify Web API şarkı sözlerini ve eksiksiz söz yazarı/yapımcı künyesini sağlamaz. Bu alanlar uydurulmaz; Studio katalog düzenleme ekranından elle eklenebilir.

## Tokenlar

Spotify Client ID/Secret, GitHub tokenı ve Hugging Face tokenı Android şifreli tercih kasasında saklanır. Aynı package adı ve aynı kalıcı Android imzasıyla yapılan güncellemelerde korunur.
