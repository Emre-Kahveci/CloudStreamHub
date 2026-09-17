---
description: Persist the latest review findings as a finite root-cause correction contract
agent: handoff-writer
---

The user approves the latest actionable review findings in this conversation for correction.

Update BOTH:

- `.ai-workflow/REVIEW_HISTORY.md`
- `.ai-workflow/CORRECTION_PLAN.md`

Requirements:

- assign/reuse stable `RF-xxx` IDs;
- deduplicate recurring findings;
- preserve severity, evidence, paths/symbols and root cause;
- mark newly actionable findings `OPEN` in the historical ledger;
- do NOT reopen already `VERIFIED_FIXED` findings without new concrete evidence;
- create ONE FINITE `CORRECTION_PLAN.md` with `Contract Status: ACTIVE`;
- include only findings that must be corrected in this pass;
- classify every verification gate as `AUTO_REQUIRED`, `MANUAL_REQUIRED`, or `OPTIONAL_DIAGNOSTIC`;
- manual/device/live/external-environment checks must not be marked AUTO_REQUIRED unless the current environment is explicitly guaranteed to support them;
- group related symptoms by root cause;
- include exact acceptance commands/steps;
- do not add speculative cleanup or unrelated improvements;
- do not modify files outside `.ai-workflow/`.

Additional user instruction, if any:

$ARGUMENTS
