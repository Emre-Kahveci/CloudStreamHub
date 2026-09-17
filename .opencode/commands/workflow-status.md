---
description: Inspect request, plan, implementation, and review handoff state without modifying anything
agent: architect
---

Report the current engineering workflow state without modifying files.

Inspect:

- `.ai-workflow/REQUEST_SPEC.md`
- `.ai-workflow/APPROVED_PLAN.md`
- `.ai-workflow/REVIEW_FINDINGS.md`
- `git status --short`
- `git diff --stat HEAD`

Return one of these states and explain why:

- `NEEDS_REQUEST_CAPTURE`
- `REQUEST_NEEDS_CLARIFICATION`
- `READY_FOR_ARCHITECTURE_PLAN`
- `PLAN_READY_FOR_IMPLEMENTATION`
- `IMPLEMENTATION_IN_PROGRESS_OR_PRESENT`
- `READY_FOR_REVIEW`
- `FIXES_READY_FOR_IMPLEMENTATION`
- `READY_FOR_FINAL_REVIEW`
- `CLEAN_OR_COMPLETED`
