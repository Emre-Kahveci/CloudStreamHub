# Shared Repository Engineering Rules

This file is intentionally shared by both OpenCode and Google Antigravity.

## Roles

The repository uses a separation-of-duties workflow:

- **ChatGPT / OpenCode Architect**: investigate, analyze, challenge assumptions, and produce implementation-ready plans. It must not modify product source code.
- **Gemini / Google Antigravity Implementer**: implement only an approved plan, run relevant verification, and report deviations.
- **ChatGPT / OpenCode Reviewer**: independently review the actual working-tree changes. It must not modify product source code.
- **Gemini / Google Antigravity Fixer**: fix only verified review findings that have been handed off.

Do not blur these roles unless the user explicitly changes the workflow.

## Repository First

Before making or recommending a material change:

1. Inspect the existing repository structure and conventions.
2. Trace the relevant execution path instead of guessing from filenames.
3. Identify existing tests, public contracts, persistence behavior, configuration, migrations, and integration boundaries that may be affected.
4. Prefer existing abstractions and patterns unless the approved plan explicitly requires a change.
5. Distinguish verified repository facts from assumptions.

## Scope Discipline

- Implement only the approved scope.
- Do not silently redesign adjacent modules.
- Do not perform opportunistic cleanup unrelated to the task.
- Preserve public behavior and compatibility unless the approved plan explicitly changes them.
- Keep changes minimal, reviewable, and reversible.
- If the approved plan conflicts with the repository's actual state, stop and report the conflict rather than improvising a new architecture.

## Source Safety

Never automatically run destructive or remote Git operations, including:

- `git push`
- `git reset --hard`
- `git clean -fd`
- `git clean -fdx`
- destructive rebase/history rewriting
- forced branch deletion

Do not create commits unless the user explicitly asks for a commit.

Do not modify secrets, credentials, `.env` files, certificates, signing assets, production deployment credentials, or private keys unless the task explicitly requires it and the user has approved that scope.

## Implementation Quality

For implementation work:

1. Read `.ai-workflow/APPROVED_PLAN.md` before touching source code.
2. Verify that the plan still matches the current working tree.
3. Implement in small coherent steps.
4. Add or update tests when behavior changes or the approved plan requires them.
5. Run the narrowest relevant verification first, then broader verification when appropriate.
6. Inspect the resulting diff before declaring completion.
7. Report any deviation from the approved plan explicitly.

Implementation is not complete merely because code was written.

## Review Quality

For code review:

- Use `git diff HEAD` as the **entry point**, not the boundary of the review.
- Inspect untracked files reported by `git status --short`.
- Read enough surrounding source to understand every material change.
- Follow callers, callees, interfaces, persistence paths, migrations, and tests when their semantics matter.
- Do not re-read unrelated parts of the repository without a concrete reason.
- Challenge implementation claims against the actual source and diff.
- Prioritize correctness, regressions, data integrity, API compatibility, concurrency, security, resource lifetime, error handling, and test quality.

## Testing

Use the repository's existing test/build/lint tooling. Do not invent a new test framework just for the task.

When verification cannot run, report:

- the exact command that was attempted or should be run,
- why it could not run,
- what remains unverified.

## Handoff Files

The workflow uses these files:

- `.ai-workflow/APPROVED_PLAN.md` — approved implementation contract from ChatGPT/OpenCode to Gemini/Antigravity.
- `.ai-workflow/REVIEW_FINDINGS.md` — verified review findings from ChatGPT/OpenCode to Gemini/Antigravity.

Treat them as coordination artifacts. Do not change their meaning silently.

## Antigravity delegation boundary

When this repository is opened in Antigravity, keep the normal/default agent as the primary conversation agent.

- For an intent to implement `.ai-workflow/APPROVED_PLAN.md`, use the workspace skill `implement-approved-plan`. The default agent must delegate to the `approved-plan-implementer` subagent and must not implement the plan itself.
- For an intent to fix `.ai-workflow/REVIEW_FINDINGS.md`, use the workspace skill `fix-approved-review`. The default agent must delegate to the `verified-review-fixer` subagent and must not perform the corrections itself.
- `approved-plan-implementer` and `verified-review-fixer` are subagents only (`mainAgent: false`). Never ask the user to select them as the primary agent.
- After implementation or fixes, independent review belongs to OpenCode/ChatGPT; Antigravity must not substitute its own final approval.

## Workspace Access Policy (OpenCode)

Inside the active repository/worktree, planning and review agents may freely read, list, search, inspect Git metadata, and run repository-local analysis commands including Python/PowerShell/shell/static-analysis commands without asking for permission.

Outside the active repository/worktree, access requires user approval. Do not inspect unrelated repositories, credentials, SSH material, browser data, or unrelated user/system files.

Repository-local shell freedom does not override role boundaries: architect/reviewer remain read-only and destructive Git/push operations remain prohibited.

## Completion Gate

Writing code is not completion.

Implementation or correction must pass its mandatory internal verification gate before independent review. A failing mandatory command means the work is not ready for review.

Fixes must address the root cause/invariant and all affected surfaces, not only the originally reported line.

## Finite Verification and Review Closure (V4.1)

The review loop must be finite.

- `REVIEW_HISTORY.md` is historical state. An old `OPEN` label alone is never proof that current code is broken.
- Verification gates must be classified as `AUTO_REQUIRED`, `MANUAL_REQUIRED`, or `OPTIONAL_DIAGNOSTIC`.
- `AUTO_REQUIRED` gates must pass before internal verification PASS.
- Genuine device/UI/live-network/external-environment checks may be `MANUAL_REQUIRED`; being unexecuted does not by itself block internal PASS.
- A correction pass operates from one finite ACTIVE `CORRECTION_PLAN.md`. Do not silently add unrelated cleanup or newly imagined requirements during execution.
- After correction + internal PASS, independent final review reopens the loop only for real blocking regressions, failed approved acceptance criteria, security/data-integrity/contract defects, or materially incorrect/incomplete behavior.
- Non-blocking LOW findings, cleanup/style suggestions, and advisory follow-ups must not create an endless correction loop.
