---
name: verify-implementation
description: Run CloudStreamHub's independent verification gate against the current working tree, acceptance criteria, tests, repository validators, and provider/playback evidence.
---

# Verify Implementation

Delegate to `verifier`.

The verifier must classify gates as:

- `AUTO_REQUIRED`
- `MANUAL_REQUIRED`
- `OPTIONAL_DIAGNOSTIC`

AUTO_REQUIRED must pass.

A genuine manual/device/live/environment-dependent gate may remain `UNVERIFIED_MANUAL` without forcing failure.

Historical `.ai-workflow` statuses are not current truth unless the present task explicitly makes them active.

For provider/playback claims, verify the same technical layer being claimed. Build, HTTP, iframe, or embed success alone is not playback proof.

Return the verifier's `VERIFICATION_STATUS` plus concise evidence and blockers.
