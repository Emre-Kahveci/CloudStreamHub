# CloudStream TR Mimari Dokümanı (Architecture)

## 1. Temel Mimarî Ayrım

CloudStream eklenti ekosisteminde üç bağımsız katman bulunur. Bu katmanların birbirine karıştırılmaması mimarinin sürdürülebilirliği ve güvenliği için esastır:

```
+-------------------------------------------------------------+
| 1. REPOSITORY DAĞITIMI (Distribution Layer)                  |
|    repo.json -> plugins.json -> *.cs3 Artifacts             |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
| 2. İÇERİK META VERİSİ (Content Metadata Layer)              |
|    MainAPI: getMainPage() / search() / load()               |
|    Kararlı Tanımlayıcılar (Stable Episode Data / Payload)   |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
| 3. OYNATMA ÇÖZÜMLEMESİ (Playback Resolution Layer)           |
|    loadLinks(PlaybackPayload) -> Direct Stream / Extractor  |
|    Geçici / İmzalı Medya Akışları (Ephemeral Media URLs)    |
+-------------------------------------------------------------+
```

### Katman Detayları

1. **Repository Dağıtımı (Distribution)**:
   - Kaynak kod `main` branch'inde saklanır.
   - GitHub Actions CI/CD süreci Gradle CloudStream eklentisini çalıştırarak derlenmiş `.cs3` dosyalarını ve imzalı `plugins.json` manifestini üretir.
   - Dağıtım atomik olarak `builds` branch'inde barındırılır.
   - CloudStream istemcisi yalnızca `repo.json` ve `plugins.json` dosyalarıyla konuşarak eklentiyi yükler.

2. **İçerik Meta Verisi (Content Metadata)**:
   - Eklenti, hedef platformun kamuya açık sayfalarını/API'lerini ayrıştırır (`SearchResponse`, `LoadResponse`).
   - `Episode.data` veya `LoadResponse.data` içine **asla son geçici oynatma linki yazılmaz**. Yalnızca kararlı içerik/bölüm kimliği (`PlaybackPayload`) yerleştirilir.

3. **Oynatma Çözümlemesi (Runtime Resolution)**:
   - Kullanıcı oynat tuşuna bastığında `loadLinks()` tetiklenir.
   - `PlaybackPayload` çözümlenir, embed iframe veya doğrudan medya kaynağı tespit edilir.
   - Built-in veya custom `ExtractorApi` ile son `.m3u8` veya `.mp4` akışı `callback(newExtractorLink(...))` ile CloudStream video oynatıcısına iletilir.

---

## 2. Uçtan Uca Veri Akış Diyagramı (End-to-End Pipeline)

```mermaid
sequenceDiagram
    autonumber
    actor User as Kullanıcı
    participant CS as CloudStream App
    participant Repo as GitHub (builds)
    participant Plugin as Provider Plugin
    participant Target as Hedef Kaynak / API
    participant Stream as Medya Sunucusu (CDN)

    User->>CS: Repo URL Ekle (cloudstreamrepo://...)
    CS->>Repo: repo.json & plugins.json İsteği
    Repo-->>CS: Eklenti Listesi ve SHA-256 İmzaları
    User->>CS: Eklentiyi Kur
    CS->>Repo: Provider.cs3 İndir & Doğrula
    CS->>Plugin: Plugin.load() -> registerMainAPI()
    
    User->>CS: Ana Sayfa Aç / Arama Yap
    CS->>Plugin: getMainPage() / search("film")
    Plugin->>Target: HTML / JSON İsteği (NiceHttp)
    Target-->>Plugin: Sayfa Yanıtı
    Plugin-->>CS: List<SearchResponse>

    User->>CS: İçeriğe Tıkla
    CS->>Plugin: load(contentUrl)
    Plugin->>Target: Detay Sayfası İsteği
    Target-->>Plugin: Metadata (Başlık, Poster, Bölümler)
    Plugin-->>CS: LoadResponse (Episode.data = PlaybackPayload)

    User->>CS: Oynat
    CS->>Plugin: loadLinks(PlaybackPayload)
    Plugin->>Target: Video Embed / Player Endpoint
    Target-->>Plugin: Iframe / Player HTML / JSON
    Plugin->>Plugin: Extractor Çözümlemesi
    Plugin-->>CS: ExtractorLink(M3U8 / MP4, Quality, Headers)
    CS->>Stream: Yetkili / Kamuya Açık Akışı Oynat
```

---

## 3. Güvenlik ve Uyumluluk İlkeleri

- **Doğrudan Veritabanı Yasağı**: Git repository'si içerisinde hiçbir zaman geçici `.m3u8` veya imzalı CDN token'ı barındırılmaz.
- **Yasal ve Teknik Sınır**: DRM/Widevine bypass, CAPTCHA kırma, private credential kullanımı veya kullanıcı hesabından gizli çerez toplama kesinlikle yasaktır.
- **Domain İzolasyonu**: Domain değişiklikleri yalnızca `config/domains.json` allowlist'ine uygun olduğunda ve testleri başarıyla geçtiğinde uygulanır.
