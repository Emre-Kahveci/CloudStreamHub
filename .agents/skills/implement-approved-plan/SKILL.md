---
name: implement-approved-plan
description: Implement an explicitly approved CloudStreamHub plan or task contract with Codex subagents, then verify and independently review the result.
---

# Implement Approved Plan

Use when the user explicitly references an approved plan, implementation contract, or `.ai-workflow/APPROVED_PLAN.md`.

## Pre-flight

Confirm the referenced plan exists, is non-empty, and still matches the current repository.

If it is stale or contradictory, report the concrete conflict rather than inventing a replacement architecture.

## Implementation

Delegate to `implementer`.

The plan is a scope boundary, not permission for unrelated cleanup.

## Verification

After implementation returns COMPLETED, delegate to `verifier`.

Do not treat historical review-ledger labels as automatic failures.

## Independent review

If verification passes and the change is non-trivial, delegate to `reviewer`.

If blocking current findings exist, send only that finite finding set to `fixer`, then re-run verification.

Do not require the user to switch agents manually.
