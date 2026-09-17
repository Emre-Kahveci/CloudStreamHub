---
name: implement-approved-plan
description: Delegate implementation of the approved plan to the implementation subagent, then automatically run the internal verifier before independent OpenCode review.
---

# Implement Approved Plan

The default/main Antigravity agent must not implement the approved plan itself.

## Pre-flight

Verify:

1. `.ai-workflow/APPROVED_PLAN.md` exists and is non-empty.
2. `AGENTS.md` exists.
3. The current workspace is the intended repository.

## Step 1 — Implementation

Invoke subagent `approved-plan-implementer` with workspace `inherit`.

Task intent:

> Implement the approved repository plan. Treat `AGENTS.md`, `.ai-workflow/REQUEST_SPEC.md`, and `.ai-workflow/APPROVED_PLAN.md` as the contract. Inspect the actual repository before editing. Implement only approved scope. Do not report completion merely because code was written: run required verification, inspect the final diff and untracked files, and continue fixing until mandatory implementation gates pass or a genuine blocker exists.

If implementation is BLOCKED, stop.

## Step 2 — Mandatory internal verification

After implementation reports COMPLETED, invoke `implementation-verifier` with workspace `inherit`.

Do not perform independent review in the parent agent.

- If verifier returns FAIL: tell the user internal verification failed and surface the exact failures. Do not direct them to OpenCode `/review` yet.
- If verifier returns PASS: direct the workflow to OpenCode `/review`.
