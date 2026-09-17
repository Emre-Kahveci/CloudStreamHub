# Correction Plan

Contract Status: CLOSED

## RF-012 — Restore the active workflow contract and historical audit trail

### Root cause
V4.1 workflow artifacts were reset to starter placeholders despite a current working tree with prior implementation, review, and verification history.

### Affected surfaces to enumerate before completion
1. Implementation-contract producer and consumer: `.ai-workflow/APPROVED_PLAN.md` and the implementation workflow.
2. Correction-contract producer and consumer: `.ai-workflow/REVIEW_FINDINGS.md`, this plan, and the fixer workflow.
3. Historical ledger: `.ai-workflow/REVIEW_HISTORY.md` with RF-001 through RF-012.
4. Verification record: `.ai-workflow/VERIFICATION_REPORT.md` and its relationship to current-tree commands.

### Required correction
Restore the actual approved scope and acceptance criteria for the current implementation, retain the stable RF ledger, and replace starter verification text with the applicable verification record. Preserve the existing product working tree; this pass changes coordination artifacts only. When all automatic gates pass, set the resulting correction contract to closed historical state for independent review.

### Acceptance criteria
- No workflow artifact required by implementation or correction says work must stop because it is a placeholder.
- `APPROVED_PLAN.md` is self-contained enough to audit the current implementation scope and acceptance criteria.
- `REVIEW_HISTORY.md` contains RF-001 through RF-012 with historical statuses.
- `VERIFICATION_REPORT.md` records actual executed verification or explicitly identifies commands that remain unexecuted.

### Gates

#### AUTO_REQUIRED
```powershell
python -c "from pathlib import Path; [Path(p).read_text(encoding='utf-8') for p in ['.ai-workflow/APPROVED_PLAN.md','.ai-workflow/REVIEW_FINDINGS.md','.ai-workflow/REVIEW_HISTORY.md','.ai-workflow/VERIFICATION_REPORT.md','.ai-workflow/CORRECTION_PLAN.md']]"
python -m pytest tools/tests
.\gradlew.bat :FullHDFilmizlesene:test
python tools/validate_repo.py
git diff --check HEAD
```

All `AUTO_REQUIRED` commands must pass before internal verification PASS.

#### MANUAL_REQUIRED
These checks do not block internal PASS solely because they require external conditions.

1. Run `python tools/benchmark_requests.py --live --iterations 1` in an approved network-enabled environment; inspect that each enabled provider has a result and one HTTP attempt per iteration.
2. On a supported client/device, open FullHDFilmizlesene, search/load a title, and verify actual playback starts without exposing ephemeral URLs or tokens in persisted/report output.

#### OPTIONAL_DIAGNOSTIC
```powershell
python tools/benchmark_requests.py --iterations 2 --report-path reports/benchmark_verify.json
```

Inspect generated benchmark and smoke reports for redacted TurkAnime pseudo-query values and documented request-count semantics.

## Excluded from this pass
- RF-011 canonical-test completeness is a `FOLLOW_UP`; it does not reopen this finite correction pass.
- No source, test, configuration, provider, or report behavior changes beyond coordination-artifact restoration.
