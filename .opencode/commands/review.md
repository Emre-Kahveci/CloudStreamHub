---
description: Independent review with finite closure semantics after internal verification
agent: reviewer
---

Review the implementation currently present in the working tree.

Additional review focus from the user:

$ARGUMENTS

Mandatory order:

1. Read `AGENTS.md`, `.ai-workflow/APPROVED_PLAN.md`, `.ai-workflow/REVIEW_HISTORY.md`, `.ai-workflow/CORRECTION_PLAN.md`, and `.ai-workflow/VERIFICATION_REPORT.md` when present.
2. Inspect `git status --short`, `git diff --stat HEAD`, `git diff HEAD`, and relevant untracked files.
3. If an ACTIVE finite correction contract exists and verification reports PASS, use CLOSURE / FINAL REVIEW semantics: verify contracted fixes first, then regressions; do not reopen the phase for non-blocking polish/follow-up findings.
4. Treat historical ledger statuses as history; verify current source.
5. Treat genuinely manual/live/device checks separately from automated correctness.
6. Do not modify code.
