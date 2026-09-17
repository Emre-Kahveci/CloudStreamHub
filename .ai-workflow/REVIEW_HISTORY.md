# Review History / Finding Ledger

Historical state only. An old status alone is not proof that current code is broken.

## Findings

### RF-001 — Raw player endpoint parameters in smoke output
Severity: HIGH
Status: VERIFIED_FIXED
Files/Symbols: `tools/live_provider_smoke.py`, `reports/live_provider_smoke.json`, `tools/tests/test_redaction.py`
Root cause: Report-bound player URLs bypassed redaction.
Required correction: Apply `XhrRedactor` at the smoke-report producer boundary and retain a producer-level regression test.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`
Evidence: Current smoke output uses `ajax/videosec&<REDACTED_QUERY>`; `test_smoke_report_producer_redaction` passes.

### RF-002 — SCX content caused false player discovery
Severity: MEDIUM
Status: VERIFIED_FIXED
Files/Symbols: `tools/scraping/discovery.py`, `tools/tests/test_discovery.py`
Root cause: Generic player detection examined undecoded SCX tokens.
Required correction: Exclude SCX from generic matching and recognize only decoded URLs.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`
Evidence: Empty/malformed SCX remains `PLAYER_NOT_FOUND`; valid decoded SCX remains discovered.

### RF-003 — Test fixture contained a real RapidVid stream token
Severity: HIGH
Status: VERIFIED_FIXED
Files/Symbols: `FullHDFilmizlesene/src/test/kotlin/com/cloudstream/tr/fullhdfilmizlesene/FullHDFilmizleseneParserTest.kt`
Root cause: Test data used ephemeral provider material.
Required correction: Use synthetic deterministic decryption input.
Verification class: AUTO_REQUIRED
Required verification: `.\gradlew.bat :FullHDFilmizlesene:test`
Evidence: The provider test constructs a synthetic token; Gradle test passed in closure review.

### RF-004 — Benchmark bypassed repository fetch abstraction
Severity: HIGH
Status: VERIFIED_FIXED
Files/Symbols: `tools/benchmark_requests.py`
Root cause: Direct probing bypassed `ProviderFetcher`.
Required correction: Use `ProviderFetcher` and its status model.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`
Evidence: Current benchmark imports and uses `ProviderFetcher`; no benchmark direct `urlopen` path remains.

### RF-005 — Live benchmark configuration and request metric contract
Severity: HIGH
Status: VERIFIED_FIXED
Files/Symbols: `tools/benchmark_requests.py`, `tools/tests/test_benchmark.py`, `config/providers.json`, `config/domains.json`, `reports/benchmark_baseline.json`
Root cause: Benchmark originally ignored `domainKey` configuration and could count logical fetches despite fallback escalation.
Required correction: Resolve `domainKey`, pass configured canonical/allowlist/markers, and disable fallbacks for one HTTP attempt per request count.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`; `python tools/benchmark_requests.py --live --iterations 1`
Evidence: Independent mock capture confirmed configured allowlist, canonical, markers, and both fallback flags reach the fetcher.

### RF-006 — Degraded health scores were not bounded
Severity: MEDIUM
Status: VERIFIED_FIXED
Files/Symbols: `tools/provider_health.py`, `tools/tests/test_health.py`
Root cause: Health-score derivation lacked bounded degraded behavior.
Required correction: Keep degraded values within 35–50 and reporting-only.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`
Evidence: Range tests pass and reports label playback as unverified by automation.

### RF-007 — Workflow implementation/review contract was unavailable
Severity: MEDIUM
Status: VERIFIED_FIXED
Files/Symbols: `.ai-workflow/APPROVED_PLAN.md`, `.ai-workflow/REVIEW_FINDINGS.md`, `.ai-workflow/REVIEW_HISTORY.md`
Root cause: Earlier workflow files were placeholders.
Required correction: Restore approved workflow artifacts; encoding follow-up was tracked separately.
Verification class: AUTO_REQUIRED
Required verification: UTF-8 text-read verification of workflow files.
Evidence: Artifacts were restored in the prior correction pass; the subsequent V4.1 reset is tracked as RF-012.

### RF-008 — Whitespace errors in changed tracked files
Severity: LOW
Status: VERIFIED_FIXED
Files/Symbols: changed tracked source/test files
Root cause: Trailing whitespace and extra EOF blank lines.
Required correction: Remove whitespace errors without behavior change.
Verification class: AUTO_REQUIRED
Required verification: `git diff --check HEAD`
Evidence: Closure review command passed.

### RF-009 — Smoke redaction lacked producer-level coverage
Severity: MEDIUM
Status: VERIFIED_FIXED
Files/Symbols: `tools/live_provider_smoke.py`, `tools/tests/test_redaction.py`
Root cause: Tests covered only helper redaction, not emitted smoke data.
Required correction: Exercise `test_provider()` and serialized player-discovery output.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`
Evidence: Producer test verifies status preservation and absence of raw pseudo-query values.

### RF-010 — Review findings contract was not UTF-8 readable
Severity: MEDIUM
Status: VERIFIED_FIXED
Files/Symbols: `.ai-workflow/REVIEW_FINDINGS.md`
Root cause: The approved correction contract had a non-text-readable encoding.
Required correction: Preserve content as UTF-8 Markdown.
Verification class: AUTO_REQUIRED
Required verification: `python -c "from pathlib import Path; Path('.ai-workflow/REVIEW_FINDINGS.md').read_text(encoding='utf-8')"`
Evidence: UTF-8 text read passed before the later workflow reset.

### RF-011 — Benchmark test did not assert configured canonical resolution
Severity: MEDIUM
Status: FOLLOW_UP
Files/Symbols: `tools/tests/test_benchmark.py`, `tools/benchmark_requests.py`
Root cause: The test fixture omits a distinct domain-config `canonical`, so it asserts fallback to `knownDetail` rather than configured canonical resolution.
Required correction: Add a distinct configured canonical value and assert it reaches `ProviderFetcher`.
Verification class: AUTO_REQUIRED
Required verification: `python -m pytest tools/tests`
Evidence: Current production code resolves configured canonical correctly; this is regression-test completeness only and does not reopen the current phase.

### RF-012 — Active workflow contracts were reset to placeholders
Severity: MEDIUM
Status: OPEN
First seen: Closure Review
Last checked: Closure Review
Files/Symbols: `.ai-workflow/APPROVED_PLAN.md`, `.ai-workflow/REVIEW_HISTORY.md`, `.ai-workflow/VERIFICATION_REPORT.md`, `.ai-workflow/CORRECTION_PLAN.md`
Root cause: The V4.1 workflow artifacts were reset without preserving the active approved scope, historical ledger, or verification state.
Required correction: Restore the actual approved scope, this stable historical ledger, and applicable verification state; activate and then close the finite RF-012 contract. Do not change product code.
Verification class: AUTO_REQUIRED
Required verification: Read all workflow artifacts as UTF-8 text and confirm they contain non-placeholder approved content consistent with the current working tree.
Evidence: `APPROVED_PLAN.md` says no implementation should start from its placeholder; `REVIEW_HISTORY.md` has no findings; `VERIFICATION_REPORT.md` says verification has not run; `CORRECTION_PLAN.md` is inactive.
