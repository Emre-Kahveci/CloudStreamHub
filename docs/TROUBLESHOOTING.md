# Sorun Giderme Rehberi (Troubleshooting)

## 1. CloudStream Eklenti Yüklenemiyor ("Failed to download plugin")
- **Neden**: `builds` branch'indeki `.cs3` dosyası indirilemiyor veya SHA-256 hash'i `plugins.json` içindekiyle uyuşmuyor.
- **Çözüm**: `python tools/validate_repo.py --verify-artifacts` çalıştırarak hash eşleşmesini doğrulayın.

## 2. Repo Eklenirken "Invalid Repository" Hatası
- **Neden**: `repo.json` dosyasındaki `pluginLists` URL'si raw github formatında değil veya `manifestVersion` 1 değil.
- **Çözüm**: `repo.json` dosyasındaki URL'nin doğrudan `raw.githubusercontent.com/.../builds/plugins.json` adresine işaret ettiğini kontrol edin.

## 3. Ana Sayfa Boş Geliyor (No items on home page)
- **Neden**: Hedef sitenin HTML yapısı veya CSS class isimleri değişmiş olabilir.
- **Çözüm**: Sitenin güncel DOM yapısını tarayıcıda inceleyin. Parser fonksiyonunu güncelleyip unit test ekleyin.

## 4. Oynatmaya Basıldığında "No links found"
- **Neden**: Embed iframe adresi değişmiş, video sağlayıcı yeni bir koruma eklemiş veya extractor güncelliğini yitirmiş olabilir.
- **Çözüm**: `loadLinks()` içindeki iframe çekme mantığını ve CloudStream'in upstream extractor listesini kontrol edin.
