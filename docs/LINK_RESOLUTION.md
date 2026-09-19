# Link Çözümleme Mimarisi (Link Resolution)

Bu doküman, oynatma verisinin kalıcılığı, `PlaybackPayload` tasarımı ve `loadLinks()` pipeline'ının detaylarını açıklar.

## 1. Kararlı Veri (Stable) vs. Geçici Veri (Ephemeral)

| Tür | Açıklama | Saklama Yeri | Örnekler |
|---|---|---|---|
| **Kararlı Veri (Stable)** | Zamanla değişmeyen, içeriğin veya bölümün sabit referansı. | `Episode.data` veya `LoadResponse.data` | `contentId`, `episodeId`, `pageUrl`, `season/episode` |
| **Geçici Veri (Ephemeral)** | Birkaç dakika veya saat geçerli olan oturum ve yayın akışı linkleri. | Asla Git'te veya Payload'da tutulmaz; yalnızca `loadLinks` runtime anında üretilir. | `.m3u8` playlist, `.mp4` CDN linki, imzalı query parametreleri (`?token=xyz`), oturum çerezleri |

> [!CAUTION]
> Asla geçici yayın URL'lerini commit etmeyiniz veya sabit bir veritabanında saklamayınız. Bunlar CDN oturumu sona erdiğinde bozulacaktır.

---

## 2. Tip Güvenli PlaybackPayload Tasarımı

Bölüm verisi tek bir ID ile temsil edilemiyorsa düzensiz metin ayırıcılar (`id|url|season`) yerine JSON serileştirilmiş veri modeli kullanılmalıdır:

```kotlin
@Serializable
data class PlaybackPayload(
    val contentId: String,
    val episodeId: String? = null,
    val pageUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val sourceName: String? = null
)
```

JSON dönüşümü için CloudStream'in standart Jackson veya KotlinX Serialization modülü kullanılır:

```kotlin
// Episode üretirken:
val payload = PlaybackPayload(
    contentId = "film-123",
    pageUrl = episodeUrl
)
val episodeData = AppUtils.toJson(payload)

newEpisode(episodeData) {
    this.name = "Bölüm 1"
    this.season = 1
    this.episode = 1
}
```

---

## 3. loadLinks Pipeline ve Extractor Entegrasyonu

```kotlin
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // 1. Payload çözümleme
    val payload = try {
        AppUtils.parseJson<PlaybackPayload>(data)
    } catch (e: Exception) {
        PlaybackPayload(contentId = data, pageUrl = data)
    }

    val targetUrl = payload.pageUrl ?: return false

    // 2. Sayfa ve player kaynağını çekme
    val doc = app.get(targetUrl).document
    val videoSources = extractPlayerSources(doc)

    var foundAny = false

    // 3. Extractor veya doğrudan akış üretimi (StreamValidator ile doğrulanmış)
    val count = BoundedParallelResolver.resolveProgressive(
        candidates = videoSources,
        provider = name,
        resolver = { src, emitLink ->
            if (src.isDirectStream) {
                val preflight = StreamValidator.validateStream(
                    url = src.streamUrl,
                    headers = mapOf("Referer" to mainUrl),
                    provider = name
                )
                if (preflight.isValid) {
                    emitLink(
                        newExtractorLink(
                            source = name,
                            name = "$name Stream",
                            url = src.streamUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = preflight.streamType
                        )
                    )
                }
            } else {
                loadExtractor(src.embedUrl, referer = mainUrl, subtitleCallback, emitLink)
            }
        },
        onLinkFound = { link ->
            callback(link)
            foundAny = true
        }
    )

    return foundAny || count > 0
}
```

---

## 4. Altyazı Pipeline'ı

Altyazı dosyaları tespit edildiğinde CloudStream'in `newSubtitleFile` veya `SubtitleFile` arayüzü ile bildirilir:

```kotlin
subtitleCallback(
    newSubtitleFile(
        lang = "tr",
        url = subtitleUrl
    )
)
```
ISO 639-1 dil kodları (`tr`, `en`, vb.) tercih edilmelidir.
