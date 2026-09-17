---
name: verify-implementation
description: Run the read-only implementation-verifier subagent as a finite internal gate before independent OpenCode review.
---

# Verify Implementation

Invoke `implementation-verifier` with workspace `inherit`.

Task intent:

> Verify the CURRENT working tree against the ACTIVE approved implementation/correction contract. Treat historical review-ledger statuses as history, not as automatic failures. Classify gates as AUTO_REQUIRED, MANUAL_REQUIRED, or OPTIONAL_DIAGNOSTIC. PASS requires all AUTO_REQUIRED gates and contracted fixes to be valid; genuine manual/live/environment-dependent checks may remain UNVERIFIED_MANUAL without blocking PASS. Write `.ai-workflow/VERIFICATION_REPORT.md`. Do not edit product code or contracts.

If verification returns FAIL:

- surface only the concrete current blockers;
- do not send the user to OpenCode `/review` yet.

If verification returns PASS:

- tell the user to run OpenCode `/review` for independent review;
- do not require the historical ledger to already say VERIFIED_FIXED.
