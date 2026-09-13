# Scrapling Entegrasyon ve Sağlık İzleme Raporu

**Tarih:** 2026-09-14  
**Branch:** `infra/scrapling-monitoring`  
**Durum:** Tamamlandı (Verified & Stabilized)

---

## 1. Kapsam ve Amaç

CloudStreamHub projesindeki Python tabanlı provider izleme, anti-bot analiz, seçici kayması (adaptive selector drift) ve sağlık denetim altyapısının `D4Vinci/Scrapling` (`0.4.15`) kütüphanesi kullanılarak modernize edilmesidir.

### Katı Kurallar ve Uyum:
1. **Android Runtime İzolasyonu**: Scrapling, Chromium ve Playwright motorları Android projesine bağımlılık olarak eklenmemiştir. Sadece `tools/` katmanında ve `provider-health.yml` CI iş akışında çalışır.
2. **Kotlin Provider Kod Dondurması (Code Freeze)**: 8 aktif sağlayıcının Kotlin kaynak kodları değiştirilmemiştir.
3. **Sürüm Dondurması (Version Freeze)**: Sağlayıcı sürüm numaraları (`version`) artırılmamıştır.
4. **Semantik Dürüstlük**: Otomasyonla doğrulanabilen iframe/embed varlığı `PLAYER_DISCOVERED` olarak raporlanırken, gerçek video akışı `UNVERIFIED_BY_AUTOMATION` olarak işaretlenmiştir. `PLAYBACK_PASS` yalnızca gerçek Android cihaz testine aittir.
5. **Seçici Kayması**: `AdaptiveManager` katı sorgu başarısız olduğunda Scrapling adaptive motorunu çalıştırır ve `DRIFT_DETECTED` raporlar. Kotlin kodunu otomatik değiştirmez.
6. **Güvenlik Redaksiyonu**: `XhrRedactor` yakalanan tüm log, başlık, çerez, token ve `.m3u8` dinamik parametrelerini güvenli bir şekilde maskelemiştir.

---

## 2. Gerçekleştirilen Değişiklikler

### 2.1 Yeni Bileşenler
- `tools/requirements.txt`: `scrapling[fetchers]==0.4.15` ve `pytest>=7.0.0` eklendi.
- `tools/install_scrapling.py`: Scrapling ve Playwright Chromium tarayıcı ortamını kuran çapraz platform kurulum betiği oluşturuldu.
- `tools/scraping/models.py`: `FetchMode`, `FetchStatus`, `CapturedXhr`, `FetchResult` veri yapıları tanımlandı.
- `tools/scraping/detection.py`: Cloudflare Turnstile/Challenge tespiti (`is_cloudflare_challenge`), WAF engeli (`is_bot_blocked`), domain yönlendirme güvenlik denetimi (`verify_redirect_safety`) ve içerik işaretçileri denetimi (`verify_content_markers`) uygulandı.
- `tools/scraping/redaction.py`: `XhrRedactor` ile hassas başlıklar, query string parametreleri, JWT'ler ve medya stream URL'leri maskelendi.
- `tools/scraping/adaptive.py`: `AdaptiveManager` sınıfı ile katı sorgu -> parmak izi kaydetme -> kayma tespiti (`DRIFT_DETECTED`) akışı kuruldu.
- `tools/scraping/fetch.py`: 3-kademeli (HTTP -> DYNAMIC -> STEALTH) `ProviderFetcher` sınıfı uygulandı.
- `tools/scraping/__init__.py`: Temiz paket arayüzü dışa aktarıldı.
- `tools/provider_probe.py`: Mühendislerin hedef siteleri CLI üzerinden inceleyebileceği, XHR yakalayabileceği ve DOM analiz edebileceği tersine mühendislik aracı geliştirildi.
- `tools/tests/`: 11 adet birim test (`test_redaction.py`, `test_detection.py`, `test_adaptive.py`, `test_fetch.py`) yazıldı ve başarıyla çalıştırıldı.

### 2.2 Güncellenen Bileşenler
- `tools/provider_health.py`: Eski `urllib` mantığı `ProviderFetcher` ve `AdaptiveManager` ile değiştirildi. Truthful L0-L5 katmanları uygulandı.
- `tools/live_provider_smoke.py`: Scrapling ile modernize edildi; `playback` alanı `playerDiscovery` (`PLAYER_DISCOVERED`) olarak güncellendi.
- `tools/report_health_summary.py`: Yeni semantik durumlar (`PLAYER_DISCOVERED`, `DRIFT_DETECTED`, `CLOUDFLARE`, `BLOCKED`, `UNTRUSTED_REDIRECT`) eklendi.
- `tools/manage_health_issues.py`: Seçici kayması (`selector-drift`) ve güvenilmeyen yönlendirmeler (`untrusted-redirect`) için issue ve yorum yönetimi entegre edildi.
- `.github/workflows/provider-health.yml`: `python tools/install_scrapling.py` adımı eklendi; `build.yml` hafif bırakıldı.
- `.gitignore`: `cache/`, `*.db`, `.scrapling/` dizin ve dosyaları eklendi.
- `docs/SCRAPLING_MONITORING.md`: Kapsamlı mimari ve araç kullanım rehberi hazırlandı.
- `docs/PROVIDER_DEVELOPMENT.md` ve `CONTRIBUTING.md`: Scrapling araçları ve test talimatları eklendi.

---

## 3. Doğrulama ve Test Sonuçları

### 3.1 Birim Testler (`pytest tools/tests`)
```text
======================= 11 passed, 4 warnings in 1.23s ========================
- test_adaptive_drift_lifecycle: PASS
- test_is_cloudflare_challenge / test_is_bot_blocked: PASS
- test_verify_redirect_safety / test_verify_content_markers: PASS
- test_provider_fetcher_http_mock / test_provider_fetcher_untrusted_redirect: PASS
- test_sanitize_headers / test_sanitize_media_url / test_sanitize_query_params / test_sanitize_body: PASS
```

### 3.2 Sağlık Matrisi (`tools/provider_health.py` & `report_health_summary.py`)
```text
| Provider | L0 Config | L1 Domain | L2 Homepage | L3 Search | L4 Load | L5 Player Discovery | Overall Status |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| AnimeciX | PASS | PASS | FAIL | FAIL | PASS | PLAYER_NOT_FOUND | DEGRADED |
| BelgeselX | PASS | PASS | PASS | FAIL | FAIL | PLAYER_NOT_FOUND | DEGRADED |
| DiziPal | PASS | PASS | PASS | FAIL | PASS | PLAYER_DISCOVERED | DEGRADED |
| FilmMakinesi | PASS | PASS | PASS | FAIL | PASS | PLAYER_DISCOVERED | DEGRADED |
| HDFilmCehennemi | PASS | PASS | PASS | FAIL | PASS | PLAYER_DISCOVERED | DEGRADED |
| KultFilmler | PASS | PASS | PASS | PASS | PASS | PLAYER_DISCOVERED | HEALTHY |
| TurkAnime | PASS | PASS | PASS | PASS | PASS | PLAYER_NOT_FOUND | HEALTHY |
| YesilCamTv | PASS | PASS | PASS | PASS | PASS | PLAYER_DISCOVERED | HEALTHY |
```

### 3.3 Canlı Smoke Testi (`tools/live_provider_smoke.py`)
- 8 aktif sağlayıcının tümü başarıyla taranmış ve rapor `reports/live_provider_smoke.json` dosyasına yazılmıştır.

### 3.4 Gradle Birim Testleri & Repo Doğrulaması
- `./gradlew test`: 8 aktif eklentinin tüm Android/Kotlin birim testleri başarıyla geçti (`BUILD SUCCESSFUL in 24s`, 240 up-to-date tasks).
- `python tools/validate_repo.py`: 8 eklentinin tüm şema, modül, Gradle ve manifest eşleşmeleri 0 hatayla doğrulandı.

---

## 4. CI / GitHub Actions Durumu Notu
- Mevcut repository'de GitHub Actions genelinde hesap faturalandırma kilidi (`The job was not started because your account is locked due to a billing issue.`) bulunmaktadır.
- Durum kodlanmış kural gereğince `CI_NOT_EXECUTED_ACCOUNT_BILLING_LOCK` olarak kayıt altına alınmıştır ve Scrapling entegrasyonu ile hiçbir ilgisi bulunmamaktadır.
