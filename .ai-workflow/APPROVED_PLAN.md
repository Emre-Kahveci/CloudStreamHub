# APPROVED IMPLEMENTATION PLAN

## Scope
1. **Smoke Output Redaction:** Apply `XhrRedactor` at the smoke-report producer boundary to ensure report-bound player URLs bypass redaction (RF-001, RF-009).
2. **Player Discovery:** Fix SCX generic player detection by excluding undecoded tokens and recognizing only valid decoded URLs (RF-002).
3. **Provider Tests:** Update `FullHDFilmizlesene` test fixture to use a synthetic deterministic stream token instead of a real ephemeral RapidVid token (RF-003).
4. **Fetcher Abstraction & Benchmarking:** Refactor benchmark to use `ProviderFetcher` and its correct configuration (`domainKey`, fallback flags, canonical/allowlist/markers) for one HTTP attempt per request count (RF-004, RF-005).
5. **Health Scoring:** Bind degraded health-score bounds between 35-50 and mark as reporting-only (RF-006).

## Acceptance Criteria
- Smoke report output correctly redacts `ajax/videosec&<REDACTED_QUERY>`.
- Player discovery skips undecoded SCX strings.
- Gradle test for FullHDFilmizlesene passes without real RapidVid tokens.
- Benchmark uses ProviderFetcher accurately with single HTTP attempt per iteration.
- Health scores correctly clamp degraded state within 35-50 bounds.
