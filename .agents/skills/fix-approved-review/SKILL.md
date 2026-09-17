---
name: fix-approved-review
description: Execute the finite approved correction contract with the fixer subagent, then run the finite internal verifier.
---

# Fix Approved Review Findings

Keep correction work out of the default/main Antigravity agent.

## Pre-flight

Require:

1. `.ai-workflow/APPROVED_PLAN.md`
2. `.ai-workflow/REVIEW_HISTORY.md`
3. `.ai-workflow/CORRECTION_PLAN.md`
4. intended repository workspace

The correction plan is a finite contract. Do not expand it during execution.

## Step 1 — Correction

Invoke `verified-review-fixer` with workspace `inherit`.

Task intent:

> Execute only the ACTIVE finite correction contract in `.ai-workflow/CORRECTION_PLAN.md`. Fix root causes across the necessary affected surfaces, not only the originally reported line. Run AUTO_REQUIRED gates. Do not treat MANUAL_REQUIRED/live/device checks as automatic blockers. Do not invent additional cleanup or new review scope.

If correction is BLOCKED, stop and report the concrete blocker.

## Step 2 — Internal verification

Invoke `implementation-verifier` with workspace `inherit`.

The verifier must decide current truth from source/diff/tests/contract, not stale ledger status labels.

- FAIL: surface concrete current blockers and do not proceed to independent review.
- PASS: direct the user to OpenCode `/review`.
