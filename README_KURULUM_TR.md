# V4 — Güncel Workflow

Bu paket V3'ün yerini alır. Temel değişiklikler: internal verifier, kalıcı review ledger, root-cause correction planı ve OpenCode repo-içi permission iyileştirmesi.

## Günlük akış

```text
OpenCode: doğal Türkçe istek
→ /plan
→ /approve-plan
→ Antigravity Default Agent: Onaylanmış planı uygula
   → implementer subagent
   → implementation-verifier subagent (otomatik)
→ verifier PASS ise OpenCode /review
→ CHANGES_REQUIRED ise /approve-fixes
   (çoklu/eski review biriktiyse /closure-review → /approve-corrections)
→ Antigravity Default Agent: Onaylanmış review düzeltmelerini uygula
   → fixer subagent
   → implementation-verifier subagent (otomatik)
→ verifier PASS ise OpenCode /review
```

### Review dosyaları

- `.ai-workflow/REVIEW_HISTORY.md`: RF-xxx finding ledger
- `.ai-workflow/CORRECTION_PLAN.md`: açık bulgular için root-cause correction contract
- `.ai-workflow/VERIFICATION_REPORT.md`: Gemini internal verification sonucu

### OpenCode permission

Architect/reviewer aktif repo içinde read/search/Python/shell komutlarını izinsiz çalıştırabilir. Aktif repo dışı dosya/dizin erişimi kullanıcı onayı ister.

---

# OpenCode Desktop + ChatGPT + Google Antigravity Desktop — v2
## Kayıpsız Request → Plan → Implementation → Review workflow'u

Bu paket, kullanıcının doğal Türkçe anlatımını doğrudan teknik plana çevirmek yerine araya bir **Requirements / Intent Compiler** katmanı koyar.

Amaç: İlk mesajda rahatça, konuşur gibi ne istediğini anlatabilmen; buna rağmen planlama aşamasında ayrıntı, kısıt, örnek veya niyet kaybı yaşamamaktır.

---

# 1. Mimari

```text
SEN
│
│ doğal Türkçe / dağınık anlatım / örnekler / düzeltmeler
▼
OpenCode Desktop + ChatGPT
│
├─ Requirements / Intent Compiler
│    ├─ ham mesajı AYNEN saklar
│    ├─ explicit requirements çıkarır
│    ├─ constraints çıkarır
│    ├─ öneri ile zorunluluğu ayırır
│    ├─ örnek ile requirement'ı ayırır
│    ├─ ambiguity kaydı tutar
│    └─ repo'da doğrulanacak iddiaları işaretler
│
▼
.ai-workflow/REQUEST_SPEC.md
│
▼
ChatGPT Architect
│
├─ REQUEST_SPEC.md'nin ham + normalize bölümünü okur
├─ gerçek repository'yi araştırır
├─ caller/callee/test/persistence/contracts inceler
├─ belirsizlikleri source üzerinden çözmeye çalışır
└─ implementation-ready plan üretir
│
▼
/approve-plan
│
▼
.ai-workflow/APPROVED_PLAN.md
│
▼
Google Antigravity Desktop + Gemini
│
└─ implementation + tests + verification
│
▼
OpenCode Desktop + ChatGPT Reviewer
│
├─ git status
├─ git diff HEAD
├─ untracked files
└─ gerektiğinde surrounding source / callers / tests
│
▼
PASS veya FIX LOOP
```

Bu tasarımda ilk katman **özetleyici değildir**. Ham isteği de sakladığı için architect yalnızca ikinci el bir özet üzerinden çalışmaz.

---

# 2. Bilgi kaybını nasıl önlüyoruz?

`REQUEST_SPEC.md` içinde şu iki bilgi aynı anda bulunur:

1. **Raw Request / Revision History** — senin yazdığın metin aynen.
2. **Structured Interpretation** — gereksinimlerin düzenlenmiş hali.

Architect için kural:

> Normalize edilmiş yorum ile ham explicit kullanıcı ifadesi çelişirse ham kullanıcı ifadesi kazanır.

Dolayısıyla akış:

```text
Türkçe istek
   ↓
özet
   ↓
plan
```

değildir.

Doğrusu:

```text
Türkçe istek ───────────────┐
   │                         │
   ▼                         │
structured requirements      │
   │                         │
   └──────────────┬──────────┘
                  ▼
        Architect + Repository
                  ▼
                Plan
```

---

# 3. ZIP'i nereye çıkaracağım?

ZIP içeriğini repository root'una çıkar.

Örnek:

```text
C:\Users\donbay\Documents\GitHub\MiyoRES Platform\
│
├── AGENTS.md
├── opencode.jsonc
├── .opencode\
├── .agents\
├── .ai-workflow\
├── mevcut proje dosyaları...
└── .git\
```

Yanlış:

```text
MiyoRES Platform\
└── OpenCode_ChatGPT_Antigravity_Workflow_v2\
    ├── AGENTS.md
    └── .opencode\
```

Doğru:

```text
MiyoRES Platform\
├── AGENTS.md
├── .opencode\
├── .agents\
└── .ai-workflow\
```

Repository'de zaten `AGENTS.md` veya `opencode.jsonc` varsa körlemesine overwrite etme; mevcut içerikle merge et.

---

# 4. OpenCode Desktop kurulumu

OpenCode'un resmi Desktop sürümünü Windows için kur.

Resmi sayfa:

```text
https://opencode.ai/download
```

OpenCode Desktop'ta repository root'unu aç.

---

# 5. ChatGPT Plus bağlantısı

OpenCode içinde OpenAI provider'ını seç.

Browser tabanlı ChatGPT Plus/Pro authentication seçeneğini kullan.

API key girmiyorsan OpenAI API faturası oluşturmazsın; kullanım abonelik kapsamındaki erişim/limitlere bağlı olur.

Bu paket herhangi bir OpenAI API key içermez.

---

# 6. Default agent

`opencode.jsonc` içinde:

```json
"default_agent": "requirements-analyst"
```

ayarlandı.

Bu nedenle **yeni bir OpenCode session'ı açtığında** varsayılan agent Requirements Analyst olur.

Bunun anlamı şu:

Yeni görev için illa `/request` yazmak zorunda değilsin.

Doğrudan şöyle yazabilirsin:

```text
ProductController'ı refactor etmek istiyorum. Şu an controller çok büyük.
Ama endpointler ve response formatları değişmesin. Bence service katmanına
ayırabiliriz ama daha doğru bir yöntem varsa source'u inceleyip plan aşamasında
karar ver. Önce davranışı güvenceye alan testleri görmek istiyorum...
```

Requirements Analyst bu mesajı planlamaya başlamadan önce `REQUEST_SPEC.md` haline getirir.

> Not: `default_agent` yeni session'larda etkilidir. Eski açık session zaten başka agent seçmişse yeni session aç veya `/request` kullan.

---

# 7. `/request` — isteği kayıpsız derleme

İstersen daha açık şekilde:

```text
/request <isteğin>
```

kullan.

Örneğin:

```text
/request ProductController refactor'una devam edeceğiz. Public API değişmeyecek.
Mevcut test davranışı korunacak. Controller içindeki business logic service'e
alınabilir diye düşünüyorum ama bunu zorunlu tutmuyorum. Önce repository'deki
mevcut pattern'leri kontrol et. Database migration istemiyorum.
```

Agent yalnızca:

```text
.ai-workflow/REQUEST_SPEC.md
```

dosyasını yazabilir.

Product source'a write yetkisi yoktur.

Repository'yi derinlemesine taramaz.

Architecture tasarlamaz.

---

# 8. REQUEST_SPEC.md içeriği

Dosyada şunlar bulunur:

```text
0. Status
1. Raw Request / Revision History
2. Desired Outcome
3. Explicit Requirements
4. Explicit Constraints
5. User-Proposed Approaches
6. Explicit Non-Goals
7. Examples and Reference Scenarios
8. Acceptance Signals
9. Terminology and Named Entities
10. Ambiguities / Open Questions
11. Potential Contradictions
12. Repository Facts to Verify
13. Planning Guardrails
```

## Öneri ile zorunluluk ayrımı

Sen:

```text
Bence ayrı service olabilir.
```

dersen:

```text
PREFERENCE / IDEA
```

olarak işaretlenir.

Ama:

```text
Mutlaka ayrı bir service olacak.
```

dersen:

```text
MANDATORY
```

olarak korunur.

## Örnek ile requirement ayrımı

Sen:

```text
Mesela CreateOrder'daki pattern gibi olabilir.
```

dediğinde bu otomatik olarak:

```text
CreateOrder birebir kopyalanmalı
```

şeklinde yorumlanmaz.

---

# 9. İsteğe sonradan ekleme/düzeltme

İlk anlatımından sonra ek bilgi vermek istersen:

```text
/request Ek düzeltme: Database schema kesinlikle değişmeyecek. Internal method isimleri değişebilir.
```

Requirements Analyst:

- eski ham mesajı silmez,
- yeni mesajı revision history'ye ekler,
- yeni intent'e göre structured bölümleri günceller,
- önceki explicit requirement gerçekten superseded değilse kaybetmez.

---

# 10. Spec status

İki temel durum vardır:

```text
SPEC_STATUS: READY_FOR_ARCHITECTURE
```

veya gerçekten kullanıcı kararı olmadan çözülemeyen bir çelişki varsa:

```text
SPEC_STATUS: NEEDS_USER_CLARIFICATION
```

Requirements Analyst mümkün olduğunca gereksiz soru sormaz.

Belirsizlikleri üç sınıfa ayırır:

```text
ARCHITECT_CAN_VERIFY
CONSERVATIVE_ASSUMPTION_ALLOWED
USER_DECISION_REQUIRED
```

Örneğin:

```text
"Bu methodun başka caller'ı var mı?"
```

sana sorulmaz; architect repository'den doğrular.

Ama:

```text
"Eski endpoint tamamen silinsin mi yoksa backward compatible kalsın mı?"
```

ve iki seçenek davranışı kökten değiştiriyorsa kullanıcı kararı gerekebilir.

---

# 11. `/plan` — asıl repository-aware mimari aşama

Request hazır olduğunda:

```text
/plan
```

çalıştır.

Bu aşamada architect:

1. `AGENTS.md` okur.
2. `REQUEST_SPEC.md` dosyasını tamamıyla okur.
3. Ham request'i de görür.
4. Structured interpretation'ı görür.
5. Repository'yi araştırır.
6. Source akışını doğrular.
7. Caller/callee ilişkilerini inceler.
8. Testleri inceler.
9. API/persistence/migration/concurrency gibi ilgili etkileri inceler.
10. Repo'da doğrulanabilecek belirsizlikleri çözer.
11. Implementation-ready plan üretir.

Plan artık doğrudan dağınık ilk prompt'tan değil:

```text
RAW INTENT
+
STRUCTURED INTENT
+
VERIFIED REPOSITORY EVIDENCE
```

üçlüsünden çıkar.

---

# 12. Planın intent fidelity kontrolü

Architect'in plan şablonuna ayrıca:

```text
Request fidelity check
```

bölümü eklendi.

Architect her explicit requirement ve constraint'in plana nasıl yansıdığını kontrol etmek zorunda.

Final self-check:

- explicit requirement kaybolmuş mu?
- explicit constraint korunmuş mu?
- örnek yanlışlıkla genel kurala dönüşmüş mü?
- preference yanlışlıkla mandatory architecture olmuş mu?
- repository bulgusu kullanıcı amacını sessizce değiştirmiş mi?
- Gemini original sohbeti görmeden planı implement edebilir mi?

---

# 13. Planı değiştirmek

Plan çıktısında bir şey hoşuna gitmezse normal konuşarak söyle:

```text
Burada yeni abstraction istemiyorum. Mevcut service pattern'i kullan.
Test planında integration testi de olsun.
```

ChatGPT planı revize eder.

Henüz `/approve-plan` çalıştırma.

---

# 14. `/approve-plan`

Planı gerçekten kabul ettiğinde:

```text
/approve-plan
```

çalıştır.

Bu komut:

```text
.ai-workflow/APPROVED_PLAN.md
```

üretir.

Bu dosya Gemini'nin implementation contract'ıdır.

Handoff writer yalnızca `.ai-workflow/` içine yazabilir; source code'a yazamaz.

---

# 15. Antigravity Desktop

Google Antigravity Desktop'ta Google AI Pro hesabınla resmi biçimde giriş yap.

OpenCode'da kullandığın aynı repository root'unu aç.

Paket içindeki custom agents:

```text
.agents/agents/implementer/agent.md
.agents/agents/fixer/agent.md
```

Antigravity tarafından workspace agent olarak keşfedilir.

---

# 16. Gemini implementation

Antigravity'de:

```text
approved-plan-implementer
```

agent'ını seç.

Sonra:

```text
Implement the approved plan.
```

veya:

```text
Onaylanmış planı uygula.
```

Agent önce:

```text
AGENTS.md
.ai-workflow/APPROVED_PLAN.md
```

okur.

Ardından ilgili repository source'u inceler ve implement eder.

---

# 17. `/review`

Implementation bittikten sonra OpenCode'a dön:

```text
/review
```

Reviewer önce:

```text
git status --short
git diff --stat HEAD
git diff HEAD
```

kullanır.

Ama diff review'un sınırı değildir.

Material bir değişikliği doğrulamak için gerekirse:

```text
changed file
  ↓
surrounding source
  ↓
caller / callee
  ↓
relevant tests
  ↓
contract / persistence / behavior
```

inceler.

Untracked dosyalar `git status --short` üzerinden ayrıca kontrol edilir.

---

# 18. Review sonucu

Reviewer findings:

```text
BLOCKER
HIGH
MEDIUM
LOW
```

seviyelerinde raporlanır.

Final status:

```text
REVIEW_STATUS: PASS
```

veya:

```text
REVIEW_STATUS: CHANGES_REQUIRED
```

---

# 19. `/approve-fixes`

Review findings doğruysa:

```text
/approve-fixes
```

çalıştır.

Bu:

```text
.ai-workflow/REVIEW_FINDINGS.md
```

üretir.

Antigravity'de:

```text
verified-review-fixer
```

agent'ını seç ve:

```text
Fix the approved review findings.
```

de.

Sonra OpenCode'a dön:

```text
/review
```

---

# 20. `/workflow-status`

Durumu kontrol etmek için:

```text
/workflow-status
```

kullan.

Olası durumlar:

```text
NEEDS_REQUEST_CAPTURE
REQUEST_NEEDS_CLARIFICATION
READY_FOR_ARCHITECTURE_PLAN
PLAN_READY_FOR_IMPLEMENTATION
IMPLEMENTATION_IN_PROGRESS_OR_PRESENT
READY_FOR_REVIEW
FIXES_READY_FOR_IMPLEMENTATION
READY_FOR_FINAL_REVIEW
CLEAN_OR_COMPLETED
```

---

# 21. Günlük kullanımın özeti

Yeni bir iş açtığında:

```text
1. Yeni OpenCode session

2. Doğrudan doğal Türkçe ile isteğini anlat
   veya:
   /request <isteğin>

3. REQUEST_SPEC.md oluşur

4. /plan

5. Planı tartış / revize et

6. /approve-plan

7. Antigravity:
   approved-plan-implementer
   → Implement the approved plan.

8. OpenCode:
   /review

9. Gerekirse:
   /approve-fixes

10. Antigravity:
    verified-review-fixer
    → Fix the approved review findings.

11. OpenCode:
    /review
```

---

# 22. Token / performans etkisi

Yeni katman bir miktar ek token kullanır çünkü ilk mesaj bir kez daha yapılandırılır.

Ancak bu agent:

- repo'yu derinlemesine okumaz,
- architecture üretmez,
- code review yapmaz,
- implementation yapmaz.

Bu nedenle maliyeti kontrollüdür.

Karşılığında architect'in önüne daha temiz bir intent contract gelir.

Özellikle uzun, konuşma dilindeki görevlerde şu hataları azaltır:

- bir kısıtı atlama,
- bir örneği zorunlu davranış sanma,
- kullanıcının çözüm önerisini requirement sanma,
- farklı cümlelerdeki çelişkiyi fark etmeme,
- repo varsayımını gerçek kabul etme,
- planın asıl amaçtan sapması.

En önemlisi: raw request saklandığı için yeni katman **single point of information loss** değildir.

---

# 23. Dosya yapısı

```text
repo-root/
│
├── AGENTS.md
├── opencode.jsonc
│
├── .opencode/
│   ├── agents/
│   │   ├── requirements-analyst.md
│   │   ├── architect.md
│   │   ├── handoff-writer.md
│   │   └── reviewer.md
│   │
│   └── commands/
│       ├── request.md
│       ├── plan.md
│       ├── approve-plan.md
│       ├── review.md
│       ├── approve-fixes.md
│       └── workflow-status.md
│
├── .ai-workflow/
│   ├── README.md
│   ├── REQUEST_SPEC.md
│   ├── APPROVED_PLAN.md
│   └── REVIEW_FINDINGS.md
│
└── .agents/
    └── agents/
        ├── implementer/
        │   └── agent.md
        └── fixer/
            └── agent.md
```

---

# 24. İlk test

Gerçek refactor'a geçmeden küçük bir görevle dene.

Yeni OpenCode session aç.

Doğrudan yaz:

```text
README dosyasının en sonuna WORKFLOW_TEST satırı eklemek istiyorum.
Başka hiçbir dosya değişmesin. Bu sadece workflow testi.
```

Requirements Analyst `REQUEST_SPEC.md` üretmeli.

Ardından:

```text
/plan
```

Plan doğruysa:

```text
/approve-plan
```

Antigravity:

```text
Implement the approved plan.
```

OpenCode:

```text
/review
```

Sistem çalışıyorsa test değişikliğini geri al.

---

# 25. Önemli kullanım kuralı

Her bağımsız büyük görev için tercihen yeni bir OpenCode session aç.

Böylece:

- eski görev context'i yeni intent'e karışmaz,
- default `requirements-analyst` temiz başlar,
- REQUEST_SPEC revision history yalnızca aynı logical task için kullanılır.

Aynı görevin ek gereksinimlerinde ise yeni session yerine tekrar `/request` kullanarak revision eklemek daha doğrudur.

---

# V3 ÖNEMLİ DEĞİŞİKLİK — Antigravity'de agent SEÇMEYİN

V2'de Antigravity tarafında `approved-plan-implementer` ve `verified-review-fixer` agent'larını primary agent olarak seçme yaklaşımı kullanılıyordu. Bu artık kullanılmamalıdır.

Antigravity custom-agent seçiminin başka konuşmalara taşınabildiği durumlarda yanlış agent'ın sonraki sohbetlerde aktif kalmasını önlemek için iki rol V3'te **subagent-only** hale getirildi:

```text
approved-plan-implementer
  mainAgent: false
  subagent: true

verified-review-fixer
  mainAgent: false
  subagent: true
```

Antigravity'de her zaman normal **Default Agent** ile konuşun.

## Implementation

OpenCode tarafında `/approve-plan` tamamlandıktan sonra Antigravity Default Agent'a yalnızca şunu söyleyin:

```text
Implement the approved plan.
```

veya:

```text
Onaylanmış planı uygula.
```

Default Agent workspace skill'i `implement-approved-plan` olarak algılar ve `approved-plan-implementer` subagent'ını çağırır.

Akış:

```text
Default Agent
   ↓ Skill discovery
implement-approved-plan
   ↓ invoke_subagent (workspace=inherit)
approved-plan-implementer [SUBAGENT]
   ↓
implementation + tests + verification
   ↓
Default Agent'a completion report
   ↓
OpenCode /review
```

## Review fixes

OpenCode `/approve-fixes` tamamlandıktan sonra Antigravity Default Agent'a:

```text
Fix the approved review findings.
```

veya:

```text
Onaylanmış review bulgularını düzelt.
```

deyin.

Akış:

```text
Default Agent
   ↓ Skill discovery
fix-approved-review
   ↓ invoke_subagent (workspace=inherit)
verified-review-fixer [SUBAGENT]
   ↓
fix + targeted verification
   ↓
Default Agent'a completion report
   ↓
OpenCode /review
```

### Neden daha iyi?

- Normal Antigravity sohbetlerinde Default Agent değişmez.
- Implementation rolü başka konuşmalara sızmaz.
- Subagent kendi izole context'iyle çalışır.
- Handoff context'i sohbetten değil `.ai-workflow` contract dosyalarından gelir.
- Planlama/implementation/review separation-of-duties korunur.

---

# V4.1 — Sonsuz Review Döngüsü Düzeltmesi

V4.1'in ana farkı review/verification kapanışını finite hale getirmesidir.

## Gate sınıfları

- `AUTO_REQUIRED`: otomasyonda çalışmalı ve geçmeli.
- `MANUAL_REQUIRED`: cihaz/UI/live-network/external ortam gerektirir; otomatik PASS'i tek başına engellemez.
- `OPTIONAL_DIAGNOSTIC`: faydalı ancak blocking olmayan ölçüm/diagnostic.

## Ledger kuralı

`REVIEW_HISTORY.md` tarihçedir. Eski `OPEN` statüsü tek başına verifier FAIL sebebi değildir. Verifier current source/diff/tests üzerinden finding'i yeniden doğrular.

## Correction lifecycle

`CORRECTION_PLAN.md` tek ve finite correction pass'ini temsil eder. `Contract Status: ACTIVE` ile oluşturulur. Fixer bu scope dışına yeni cleanup/requirement eklemez.

## Final review kapanışı

Correction + internal verification PASS sonrası `/review`, mevcut correction set'ini ve regression'ları doğrular. Yeni LOW/polish/style/follow-up önerileri current phase'i yeniden açmaz. Döngü yalnız gerçek blocking regression, acceptance failure, security/data-integrity/contract problemi veya materially incorrect/incomplete davranış için yeniden açılır.

## 12 review'dan çıkış

1. `/closure-review`
2. `/approve-corrections`
3. Antigravity Default Agent: `Onaylanmış correction planını uygula ve internal verification çalıştır.`
4. `VERIFICATION_STATUS: PASS`
5. OpenCode: `/review`
