# CloudStream TR — Eklenti Deposu (Extensions Repository)

[![Build & Publish](https://github.com/Emre-Kahveci/CloudStreamHub/actions/workflows/build.yml/badge.svg)](https://github.com/Emre-Kahveci/CloudStreamHub/actions/workflows/build.yml)
[![Validate](https://github.com/Emre-Kahveci/CloudStreamHub/actions/workflows/validate.yml/badge.svg)](https://github.com/Emre-Kahveci/CloudStreamHub/actions/workflows/validate.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

Eski `Kraptor123/cs-kraptor` Türkçe eklenti ekosisteminin adli analiz (forensics) ve modernizasyon süreci sonucunda, güncel resmî **CloudStream 3** mimarisine tam uyumlu, güvenli, test edilebilir ve otomatik derlenen yeni nesil Türkçe eklenti deposudur.

---

## ⚡ Hızlı Kurulum (Tek Tıkla Kurulum)

CloudStream uygulaması cihazınızda yüklüyse:

👉 **[CloudStream'e Doğrudan Ekle (Tek Tıkla Kurulum)](cloudstreamrepo://raw.githubusercontent.com/Emre-Kahveci/CloudStreamHub/builds/repo.json)**

---

## 📱 Manuel Kurulum Adımları

1. Cihazınızda **CloudStream 3** uygulamasını açın.
2. **Ayarlar (Settings)** &rarr; **Eklentiler (Extensions)** &rarr; **Depo Ekle (Add Repository)** sekmesine gidin.
3. Aşağıdaki Depo URL'sini kopyalayıp ilgili alana yapıştırın:

```text
https://raw.githubusercontent.com/Emre-Kahveci/CloudStreamHub/builds/repo.json
```

4. Depo eklendikten sonra listeden dilediğiniz eklentiyi seçip **Yükle (Install)** butonuna basarak anında kullanmaya başlayabilirsiniz!

---

## 📦 Mevcut Eklentiler (Active Providers)

| Eklenti Adı | Modül Adı | Güncel Sürüm | Eski Sürüm | Türler | Canonical Domain | Durum |
|---|---|---|---|---|---|---|
| **Kült Filmler** | `KultFilmler` | **32** | 31 | Film, Dizi | [kultfilmler.net](https://kultfilmler.net) | `1 (Aktif)` |
| **Yeşilçam TV** | `YesilCamTv` | **10** | 9 | Film | [yesilcamtv.com.tr](https://yesilcamtv.com.tr) | `1 (Aktif)` |

> Tam 67 legacy eklentinin durum dökümü ve canlandırma matrisi için [docs/LEGACY_INVENTORY.md](docs/LEGACY_INVENTORY.md) ve [legacy/migration-matrix.json](legacy/migration-matrix.json) dosyalarını inceleyebilirsiniz.

---

## 🛡️ Temel Mimari ve Güvenlik İlkeleri

1. **Çalışma Zamanı (Runtime) Link Çözümleme**: Bu depoda hiçbir kalıcı `.m3u8` veya geçici CDN yayın URL'si commit edilmez. Yayın bağlantıları kullanıcı oynat tuşuna bastığında `loadLinks()` üzerinden taze ve yetkili olarak çözümlenir. Detaylar için [docs/LINK_RESOLUTION.md](docs/LINK_RESOLUTION.md) belgesini inceleyin.
2. **Domain Hijack Koruması**: Eski domainlerin el değiştirmesi riskine karşı kör yönlendirme takip edilmez. Domainler `config/domains.json` allowlist'i ve içerik işaretçileri (fingerprint) ile doğrulanır. Detaylar için [docs/DOMAIN_MANAGEMENT.md](docs/DOMAIN_MANAGEMENT.md) belgesini inceleyin.
3. **DRM / Widevine / Kimlik Bilgisi Yasağı**: Hiçbir eklenti DRM koruması aşmaz, ücretli abonelik atlatmaz veya kullanıcı hesabı çerezi kullanmaz. Yalnızca kamuya açık, serbest kaynaklar desteklenir.
4. **Atomik Yayınlama**: Derleme ve test süreçleri başarıyla tamamlanmadan `builds` branch'ine hiçbir dosya yüklenmez; mevcut kullanıcıların çalışan sürümleri korunur.

---

## 🛠️ Geliştiriciler İçin Yerel Derleme (Local Development)

### Gereksinimler
- **JDK 17 veya JDK 21**
- **Android SDK (API 35)**
- **Git**

### Komutlar

```powershell
# Eklentileri derlemek ve .cs3 oluşturmak için:
./gradlew make

# plugins.json dosyasını üretmek için:
./gradlew makePluginsJson

# Statik doğrulama ve bütünlük testi:
powershell -ExecutionPolicy Bypass -File tools/validate_repo.ps1
# veya Python ile:
python tools/validate_repo.py --verify-artifacts

# Domain sağlık testi:
powershell -ExecutionPolicy Bypass -File tools/domain_health.ps1
```

---

## 🤝 Katkıda Bulunma ve Hata Bildirimi

- Yeni bir eklenti geliştirmek istiyorsanız [docs/PROVIDER_DEVELOPMENT.md](docs/PROVIDER_DEVELOPMENT.md) ve [CONTRIBUTING.md](CONTRIBUTING.md) belgelerini okuyunuz.
- Çalışmayan bir eklenti bildirmek için [Bozuk Eklenti Formu](.github/ISSUE_TEMPLATE/provider-broken.yml) kullanabilirsiniz.
- Güvenlik bildirimleri için [SECURITY.md](SECURITY.md) belgesini inceleyiniz.

## 📄 Lisans ve Yasal Uyarı

Bu proje [GNU General Public License v3.0](LICENSE) altında lisanslanmıştır. Eklentiler yalnızca kamuya açık web sayfalarının kullanıcı adına standart HTTP protokolüyle ayrıştırılması prensibiyle çalışır; repository sunucularında hiçbir video, ses veya medya içeriği barındırılmaz.
