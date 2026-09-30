---
name: fix-approved-review
description: Fix a finite set of current, verified CloudStreamHub review findings, then re-run independent verification without expanding the correction scope.
---

# Fix Approved Review Findings

Use only when there is a finite, current correction set approved by the user or produced by the active review workflow.

## Correction

Delegate the finding set to `fixer`.

The fixer must re-confirm each finding, repair the root cause/invariant across only the necessary affected surfaces, and avoid unrelated cleanup.

## Verification

After correction, delegate to `verifier`.

If AUTO_REQUIRED gates pass and remaining checks are genuinely manual/environment-dependent, do not keep the loop open solely because historical ledgers still contain old statuses.

## Closure

Use `reviewer` again only when the correction materially changes behavior or when the previous finding was BLOCKER/HIGH.

Do not create an endless correction cycle for LOW/style/advisory observations.
