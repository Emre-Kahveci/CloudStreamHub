---
description: Save the latest user-approved plan for the Antigravity implementation agent
agent: handoff-writer
---

The user is approving the latest implementation plan in this conversation.

Write a self-contained implementation contract to:

`.ai-workflow/APPROVED_PLAN.md`

Requirements:

- preserve the latest approved plan's scope, verified findings, invariants, file/symbol targets, test strategy, verification commands, and acceptance criteria;
- clearly identify any remaining assumption that the implementer must verify before editing;
- add a short `Implementation Agent Contract` section stating that the implementation agent must not redesign or expand scope silently;
- do not alter any repository file outside `.ai-workflow/APPROVED_PLAN.md`;
- overwrite the previous handoff document if one exists.

Additional approval note from the user, if any:

$ARGUMENTS
