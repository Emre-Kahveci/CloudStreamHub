---
description: Persist the latest closure review as one finite correction contract
agent: handoff-writer
---

The user approves the latest CLOSURE REVIEW in this conversation.

Write/update BOTH:

1. `.ai-workflow/REVIEW_HISTORY.md`
2. `.ai-workflow/CORRECTION_PLAN.md`

Rules:

- preserve stable `RF-xxx` IDs;
- deduplicate repeated symptoms under root causes;
- keep the ledger historical;
- create `CORRECTION_PLAN.md` with `Contract Status: ACTIVE`;
- correction plan must contain only the finite actionable set for THIS pass;
- classify every gate as `AUTO_REQUIRED`, `MANUAL_REQUIRED`, or `OPTIONAL_DIAGNOSTIC`;
- genuine live-network/device/UI/manual checks must not block internal PASS merely because automation cannot execute them;
- include exact automated commands and exact manual steps separately;
- do not add speculative cleanup;
- do not modify files outside `.ai-workflow/`.

Additional user instruction, if any:

$ARGUMENTS
