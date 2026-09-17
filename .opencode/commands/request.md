---
description: Convert a free-form Turkish or natural-language request into a lossless structured task specification before architecture planning
agent: requirements-analyst
---

Convert the user's request below into a LOSSLESS request specification and write it to:

`.ai-workflow/REQUEST_SPEC.md`

USER REQUEST / AMENDMENT:

$ARGUMENTS

Rules:

- Preserve the user's text verbatim in the raw request/revision section.
- Do not produce an implementation plan.
- Do not choose architecture.
- Do not inspect the repository deeply.
- Do not modify product code.
- Distinguish desired outcome, explicit requirements, explicit constraints, proposed approaches, examples, non-goals, acceptance signals, ambiguity, and repository facts that must be verified later.
- If a previous request specification exists, treat this input as a new amendment unless the user explicitly says it replaces the previous request.
