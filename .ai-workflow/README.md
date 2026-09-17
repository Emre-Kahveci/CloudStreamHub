# AI Workflow Handoff Directory

This directory is the neutral handoff boundary between:

- OpenCode Desktop + ChatGPT subscription authentication
- Google Antigravity Desktop + Google account authentication

## Files

- `REQUEST_SPEC.md` — lossless intent contract generated from the user's conversational request. It preserves the raw wording plus normalized requirements.
- `APPROVED_PLAN.md` — implementation contract created only after the user approves ChatGPT's repository-verified plan.
- `REVIEW_FINDINGS.md` — correction contract created only after the user approves ChatGPT's review findings.

## Workflow

```text
User's conversational Turkish request
        ↓
/request
        ↓
REQUEST_SPEC.md
        ↓
/plan
        ↓
User reviews/revises plan
        ↓
/approve-plan
        ↓
APPROVED_PLAN.md
        ↓
Antigravity implementation
        ↓
/review
        ↓
(optional) /approve-fixes → REVIEW_FINDINGS.md
```

The request layer is intentionally **lossless**: the raw user request is never replaced by the normalized interpretation.

Neither application shares the other's authentication token.
