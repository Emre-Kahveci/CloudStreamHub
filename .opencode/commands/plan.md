---
description: Build a repository-verified implementation plan from the lossless request specification
agent: architect
---

Create an implementation-ready architecture plan from the canonical request specification:

`.ai-workflow/REQUEST_SPEC.md`

Do NOT treat this command invocation as a replacement for the request specification.

Additional planning note from the user, if any:

$ARGUMENTS

Protocol:

1. Read `AGENTS.md`.
2. Read `.ai-workflow/REQUEST_SPEC.md` completely, including raw request/revision history.
3. If the spec is missing, empty, contradictory, or not `READY_FOR_ARCHITECTURE`, return `PLAN_STATUS: BLOCKED` rather than guessing.
4. Investigate the real repository and resolve source-verifiable uncertainties.
5. Produce the architect agent's full implementation-ready plan.
6. Do not modify repository files.
7. Preserve intent fidelity: raw explicit user wording wins over any conflicting normalization.
