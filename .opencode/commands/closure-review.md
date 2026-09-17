---
description: Consolidate accumulated reviews into one finite correction set and stop scope expansion
agent: reviewer
---

Perform a CLOSURE REVIEW before any further correction pass.

Required process:

1. Read `AGENTS.md`, `.ai-workflow/APPROVED_PLAN.md`, `.ai-workflow/REVIEW_HISTORY.md`, and `.ai-workflow/VERIFICATION_REPORT.md` when present.
2. Inspect `git status --short`, `git diff --stat HEAD`, and `git diff HEAD`.
3. Deduplicate previous findings and group symptoms by root cause.
4. Re-verify previously reported BLOCKER/HIGH/MEDIUM findings against the CURRENT tree.
5. Produce ONE FINITE correction set. Do not add unrelated cleanup or quality ideas.
6. For each gate classify it as `AUTO_REQUIRED`, `MANUAL_REQUIRED`, or `OPTIONAL_DIAGNOSTIC`.
7. Separate actual defects from manual/unavailable verification gaps.
8. Do not modify files.

Output sections suitable for `/approve-corrections`:

- `REVIEW_HISTORY_UPDATE`
- `FINITE_CORRECTION_PLAN`

Finish with:

`CLOSURE_STATUS: READY_FOR_CORRECTION`

or

`CLOSURE_STATUS: NO_OPEN_FINDINGS`
