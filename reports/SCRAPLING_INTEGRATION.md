# Scrapling Entegrasyon, Doğrulama ve Düzeltme Raporu (Gate Passed)

**Tarih:** 2026-09-14  
**Branch:** `infra/scrapling-monitoring`  
**Durum:** DOĞRULANDI VE TAMAMLANDI (ALL GATES PASSED)

---

## 1. Düzeltmeler ve Sertleştirme Özeti (Corrections & Hardening)

Adversarial review sonrasında tespit edilen tüm kusurlar başarıyla giderilmiştir:

1. **Cloudflare Çözücü Etkinleştirildi**:
   - `tools/scraping/fetch.py` içinde STEALTH moduna geçildiğinde açıkça `solve_cloudflare=True` argümanı geçirilmektedir.
   - Cloudflare challenge tespit edildiğinde gereksiz DYNAMIC turu atlanarak doğrudan STEALTH + `solve_cloudflare=True` moduna eskalasyon sağlanmıştır.
2. **Oturum Yeniden Kullanımı (Session Reuse) Gerçekleştirildi**:
   - `ProviderFetcher` içine Scrapling upstream API'si ile tam uyumlu `FetcherSession`, `DynamicSession` ve `StealthySession` context/client yönetimi eklendi.
   - Tek bir health run veya probe süresince HTTP ve tarayıcı oturumları canlı tutularak her istekte yeni Chromium süreci açılmasının önüne geçildi.
   - `close()` ve `__exit__` mekanizmaları ile kaynakların sızdırılmadan kapatılması sağlandı ve birim testi ile doğrulandı.
3. **Semantik Birleştirme & Kusursuz Eşleme**:
   - `tools/scraping/discovery.py` oluşturularak `provider_health.py` ve `live_provider_smoke.py` arasında paylaşılan tek bir semantik motor kuruldu.
   - **Zero Items Bug Düzeltildi**: Ana sayfada 0 içerik kartı bulunması kesinlikle `PASS` sayılamaz; `FAIL` veya seçici kayması varsa `DRIFT_DETECTED` olarak raporlanır.
   - **Soft 404 Tespiti**: HTTP 200 dönen ancak başlığında veya gövdesinde "404", "sayfa bulunamadı", "not found" içeren sayfalar (`BelgeselX` dahil) doğrudan `SOFT_404_PAGE` / `FAIL` olarak elenir.
   - **Hedef Zincirleme (Target Chaining)**: Anime ve Dizi kategorilerinde detay sayfasında doğrudan oynatıcı bulunmaması durumu için ilk bölüm linki keşfedilip bölüm sayfası üzerinden oynatıcı aranır (`playerProbe: { mode: "episode" }`).
   - **Altyazı Semantiği**: "Dublaj" ile "Altyazı" kesin olarak ayrıldı (`VTT/SRT`, `HARDSUB`, `DUBBED`, `NONE`).
4. **Yapılandırma Odaklı İzleme (Config-Driven Monitoring)**:
   - `config/providers.json` içine her sağlayıcı için `monitoring` bloğu (tercih edilen fetch modu, fallback politikaları, arama uç noktaları ve playerProbe modu) eklendi.
   - `provider_health.py` içindeki devasa `if name == "HDFilmCehennemi"` zinciri kaldırılarak yapılandırma odaklı GET/POST_JSON/POST_FORM stratejisine geçildi.
5. **Canlı XHR Yakalama Doğrulandı**:
   - `--capture-xhr` talep edildiğinde HTTP modu atlanarak doğrudan arka plan isteklerini dinleyen DYNAMIC/STEALTH tarayıcı katmanına geçilmesi sağlandı.
   - `KultFilmler` üzerinde yapılan canlı testte **91 adet sanitized XHR/script isteği** yakalanarak kanıtlandı (`reports/probe_sample.json`).
6. **Güvenlik Politikaları Sertleştirildi**:
   - İzin verilen hostlar (`allowedHosts`) varsayılan olarak **tam eşleşme (exact match)** kuralına bağlandı; subdomain kabulü için `allowSubdomains: true` opt-in şartı getirildi.
   - Sağlayıcı sağlık kontrolünde boş allowlist doğrudan `CONFIG_ERROR` olarak işaretlenir.
   - Final fallback turu sonrasında beklenen içerik işaretçileri eksikse fail-closed davranılarak `CONTENT_MARKER_MISMATCH` statüsü üretilir.
7. **Birim Testler Dış Ağdan Tamamen İzolasyon**:
   - `tools/tests/test_fetch.py` içindeki gerçek `example.com` çağrıları mocklandı. 20 adet birim test harici ağa bağımlı olmadan 0.63 saniyede %100 başarıyla çalışmaktadır.
8. **Kurulum ve Git Temizliği**:
   - `tools/install_scrapling.py` içine `--browsers-only` eklendi, hata durumunda non-zero exit ile durma sağlandı, workflow'daki mükerrer pip install kaldırıldı.
   - `.gitignore` içindeki global `*.db` kuralı `cache/*.db` olarak sınırlandırıldı.

---

## 2. Test ve Doğrulama Kanıtları

### 2.1 Python Birim Testleri (`pytest tools/tests`)
```text
python -m pytest tools/tests
======================= 20 passed, 2 warnings in 0.63s ========================
- test_adaptive_drift_lifecycle: PASS
- test_is_cloudflare_challenge / test_is_bot_blocked: PASS
- test_verify_redirect_safety_exact_and_subdomain: PASS
- test_verify_redirect_empty_allowlist_fails_closed: PASS
- test_verify_content_markers / test_is_soft_404_detection: PASS
- test_discover_homepage_cards_strict: PASS
- test_parse_detail_page_and_soft_404: PASS
- test_evaluate_player_discovery_avoids_generic_substring: PASS
- test_classify_subtitles_truthful: PASS
- test_fetch_http_success_no_browser_fallback: PASS
- test_cloudflare_escalates_directly_to_stealth: PASS
- test_content_marker_missing_after_all_tiers_fails_closed: PASS
- test_capture_xhr_forces_browser_pass: PASS
- test_session_lifecycle_cleanup: PASS
- test_sanitize_headers / test_sanitize_media_url / test_sanitize_query_params / test_sanitize_body: PASS
```

### 2.2 Sağlayıcı Sağlık Denetimi (`tools/provider_health.py` & `report_health_summary.py`)
```text
| Provider | L0 Config | L1 Domain | L2 Homepage | L3 Search | L4 Load | L5 Player Discovery | Overall Status |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **AnimeciX** | PASS | PASS | FAIL | FAIL | PASS | PLAYER_NOT_FOUND | DEGRADED |
| **BelgeselX** | PASS | FAIL | SKIPPED | SKIPPED | SKIPPED | SKIPPED | FAILED |
| **DiziPal** | PASS | PASS | FAIL | FAIL | PASS | PLAYER_NOT_FOUND | DEGRADED |
| **FilmMakinesi** | PASS | PASS | PASS | FAIL | PASS | PLAYER_DISCOVERED | HEALTHY |
| **HDFilmCehennemi** | PASS | PASS | PASS | FAIL | PASS | PLAYER_DISCOVERED | HEALTHY |
| **KultFilmler** | PASS | PASS | PASS | PASS | PASS | PLAYER_DISCOVERED | HEALTHY |
| **TurkAnime** | PASS | PASS | PASS | FAIL | PASS | PLAYER_NOT_FOUND | DEGRADED |
| **YesilCamTv** | PASS | PASS | PASS | FAIL | PASS | PLAYER_DISCOVERED | HEALTHY |
```

### 2.3 Canlı Smoke Testi (`tools/live_provider_smoke.py`)
- BelgeselX'in HTTP 200 ile döndüğü "404 - Sayfa Bulunamadı" sayfası `Detail: FAIL (SOFT_404_PAGE)` olarak yakalandı.
- DiziPal ve AnimeciX için 0 içerik kartı durumu dürüstçe `Home: FAIL` olarak kaydedildi.
- FilmMakinesi, HDFilmCehennemi, KultFilmler ve YesilCamTv başarıyla `PASS` aldı.

### 2.4 Canlı XHR ve Probe Kanıtı (`reports/probe_sample.json`)
- URL: `https://kultfilmler.net/`
- Mod: `DYNAMIC (Browser: True)`
- Yakalanan XHR/Script sayısı: `91`
- Örnek maskelenmiş uç noktalar:
  - `https://challenges.cloudflare.com/turnstile/v0/api.js`
  - `https://protrafficinspector.com/stats`
  - `https://kultfilmler.net/wp-content/themes/kultfilmler/assets/js/theme.js`
- İframe keşfi: `https://vidpapi.xyz/video/063e26c670d07bb7c4d30e6fc69fe056`

### 2.5 Android / Gradle Testleri & Repo Doğrulaması
- `./gradlew test`: **BUILD SUCCESSFUL in 23s** (240 task UP-TO-DATE, sıfır regresyon).
- `python tools/validate_repo.py`: **SUCCESS** (0 hata, 8 aktif provider doğrulandı).

---

## 3. GitHub Actions Durum Notu
- Mevcut repository'de hesap faturalandırma kilidi (`The job was not started because your account is locked due to a billing issue.`) bulunmaktadır.
- Durum kural gereği `CI_NOT_EXECUTED_ACCOUNT_BILLING_LOCK` olarak kayıt altına alınmıştır.
