# CloudStreamHub — Codex Engineering Contract

This repository is maintained with Codex as the primary software-engineering assistant.

The user should be able to describe the desired outcome normally. The primary Codex agent is responsible for inspecting the repository, choosing the appropriate workflow, delegating to specialized subagents when useful, implementing the requested change, verifying it, reviewing it, and reporting the result. Do not require the user to manually select a subagent for routine work.

## 1. Project mission

CloudStreamHub is a Turkish CloudStream extension repository. The engineering goal is not merely to make providers compile: enabled providers must remain structurally valid, publishable, maintainable, and—when playback is in scope—capable of reaching truthful playback evidence.

Prefer small, evidence-based changes over broad rewrites.

Repository truth beats assumptions, stale notes, old review ledgers, and remembered behavior.

## 2. Repository map and invariants

Important surfaces include:

- Provider modules: top-level directories containing `build.gradle.kts`.
- Shared Kotlin code: `core/src/main/kotlin`.
- Shared tests: `core/src/test/kotlin` where applicable.
- Provider registry: `config/providers.json`.
- Domain/runtime resolution: `config/domains.json`.
- Repository validators and health tooling: `tools/`.
- CI validation/build/publish workflows: `.github/workflows/`.
- Generated plugin metadata: `build/plugins.json`.
- Built extension artifacts: `*.cs3`.
- Published distribution: `builds` branch.
- Repository manifest: `repo.json`.

`settings.gradle.kts` auto-discovers provider modules. Do not maintain a second hard-coded provider list unless an existing repository contract requires it.

When provider inventory changes, preserve consistency across the enabled provider registry, generated `plugins.json`, and built `.cs3` artifacts.

Do not directly hand-edit generated artifacts when the documented build process can regenerate them.

## 3. Default execution lifecycle

For a material task, use this lifecycle unless the task clearly needs a smaller subset:

`DISCOVER → TRACE → REPRODUCE → CLASSIFY → ROOT CAUSE → PLAN → IMPLEMENT → TEST → VERIFY → REVIEW → DOCUMENT`

Meaning:

1. **DISCOVER** — inspect the relevant repository structure, active configuration, tests, and conventions.
2. **TRACE** — trace the real execution path. Do not infer behavior from filenames alone.
3. **REPRODUCE** — reproduce the defect or establish a concrete failing condition when feasible.
4. **CLASSIFY** — identify the failing layer before editing.
5. **ROOT CAUSE** — state the invariant that is broken.
6. **PLAN** — choose the smallest complete fix.
7. **IMPLEMENT** — make only the changes required by the requested outcome.
8. **TEST** — run narrow tests first.
9. **VERIFY** — run the required repository gates and inspect the working tree.
10. **REVIEW** — independently challenge the final diff for regressions and incomplete fixes.
11. **DOCUMENT** — update durable project documentation only when behavior/contracts changed.

Do not stop at “code written” or “build succeeds.”

## 4. Automatic subagent routing

The primary agent owns orchestration.

Use specialized subagents automatically when they materially improve quality:

- `repo_mapper` — read-only architecture/repository exploration and change-impact mapping.
- `provider_debugger` — read-only CloudStream provider, extractor, network, manifest, and playback-path diagnosis.
- `implementer` — scoped code changes after the relevant behavior and boundaries are understood.
- `verifier` — independent verification against requested acceptance criteria and repository gates.
- `reviewer` — independent final review of the actual working-tree diff.
- `fixer` — correction of verified review findings without expanding scope.

Delegation rules:

- Small, obvious, low-risk edits may be handled directly by the primary agent.
- For ambiguous or cross-cutting work, inspect with `repo_mapper` before implementation.
- For provider/playback failures, use `provider_debugger` before changing code unless the root cause is already directly proven.
- Do not run multiple write-capable agents against the same files concurrently.
- After a material implementation, verification should be performed by `verifier`, not by relying only on the implementer's self-report.
- For non-trivial changes, run `reviewer` after verification.
- If review finds a real blocking defect, delegate the finite correction set to `fixer`, then re-run verification.
- Do not create an endless review/fix loop for style, polish, LOW-severity suggestions, or historical findings that are no longer reproducible.

Subagents must inspect actual source and return evidence. The primary agent remains responsible for the final integrated answer.

## 5. CloudStream provider truth model

For provider work, reason about the complete chain:

`provider discovery/search → load() → episode/movie data → loadLinks() → extractor/resolver → candidate media URL → headers/cookies/referrer → manifest/file → media segments → player`

A successful build, HTTP 200, HTML page load, iframe discovery, embed URL, or extractor callback alone is **not** sufficient evidence that playback works.

Classify failures by layer before fixing. Typical layers include:

- provider discovery / registration
- current domain resolution
- search/main-page parsing
- detail-page parsing
- episode/movie identifier construction
- `loadLinks`
- embed extraction
- anti-bot / request headers / cookies / referer
- HLS/DASH/file URL resolution
- manifest retrieval
- media segment retrieval
- player compatibility

Do not assume a Media3/player error is a codec issue until URL type, redirects, headers, manifest, and segment access have been checked.

When reporting provider state, prefer evidence-based states such as:

- `IMPLEMENTED_WORKING`
- `DEGRADED`
- `BLOCKED_WITH_EVIDENCE`
- `DEAD_WITH_EVIDENCE`

Do not present “eligible”, “probably fixed”, or “builds successfully” as equivalent to working playback.

## 6. Provider implementation discipline

Before modifying a provider:

- Inspect its module `build.gradle.kts`.
- Read the provider implementation and any custom extractors it depends on.
- Check shared helpers in `core/` before adding duplicate utilities.
- Check `config/providers.json` and `config/domains.json`.
- Check relevant health/tooling behavior when metadata or domain logic changes.
- Search for tests that already encode the expected behavior.
- Prefer dynamic runtime domain resolution and existing repository abstractions.

When adding or replacing a provider:

- Keep the implementation isolated to the minimum required module/shared helper surfaces.
- Do not copy large upstream code blocks without checking provenance and license compatibility.
- Prefer public, reproducible source behavior.
- Do not add credential theft, secret extraction, DRM circumvention, or private-account bypass logic.
- Do not hard-code short-lived tokens, cookies, or user credentials.
- Preserve the repository's domain allowlisting and runtime-resolution model.

## 7. Health monitoring semantics

Health tooling must report the layer it actually verified.

Treat structural/provider health and real playback health as different evidence classes.

- L0–L5-style checks may establish reachability, parsing, embed discovery, or intermediate resolution.
- L6–L8-style evidence is reserved for later playback-path validation such as media URL/manifest/segment truth.

Do not upgrade an issue to “playback working” from weaker evidence.

For automated issue management:

- Treat issue identity as `provider + problem_type` where the repository tooling does so.
- Avoid repetitive comments when state did not materially change.
- Recovery must correspond to the tier/problem that originally failed.
- Disabled providers must not create noisy active-domain/provider-health churn.
- Closing an issue requires evidence that the relevant failing condition recovered, not merely that another lower tier passed.

## 8. Verification gates

Use the narrowest relevant test first, then expand.

Common local Windows commands:

```powershell
python tools/validate_repo.py
.\gradlew.bat testDebugUnitTest --parallel --continue
.\gradlew.bat make makePluginsJson
python tools/validate_repo.py --verify-artifacts
```

CI/Linux equivalents use `./gradlew`.

When provider behavior or live resolution is in scope, select the relevant repository tooling rather than inventing ad-hoc success criteria, for example:

```powershell
python tools/live_provider_smoke.py
python tools/provider_health.py
python tools/generate_playback_matrix.py
python tools/playback_verifier.py
```

Do not blindly run every live/network tool for unrelated changes.

If a provider module changed, account for the version-bump invariant enforced by `tools/verify_version_bumps.py`.

Before declaring a material task complete:

1. `git status --short`
2. inspect the relevant `git diff`
3. confirm no unrelated generated or untracked files were introduced
4. map requested acceptance criteria to concrete evidence
5. report any check that could not run and why

### Gate classes

Classify verification work as:

- `AUTO_REQUIRED` — deterministic repository-local validation that must pass.
- `MANUAL_REQUIRED` — device/UI/manual playback/external environment checks that cannot be reproduced reliably in the current environment.
- `OPTIONAL_DIAGNOSTIC` — useful investigation that is not a blocking acceptance gate.

A genuine `MANUAL_REQUIRED` check may remain unverified without converting a valid automated result into failure. Never claim that the manual check passed.

## 9. Review standard

Review the resulting behavior, not just syntax.

Use `git diff HEAD` as an entry point, then inspect surrounding code when semantics require it.

Prioritize:

- correctness and regression risk
- provider/extractor contract breakage
- false-positive health reporting
- invalid or stale domain behavior
- concurrency/cancellation/resource lifetime
- header/cookie/referer propagation
- exception handling that masks failure
- test assertions that do not prove the requested behavior
- accidental provider inventory drift
- generated artifact drift
- secrets or unsafe logging

A review finding must include concrete evidence, impact, and a viable correction direction.

Do not reopen a completed correction cycle for purely stylistic preferences.

## 10. Scope and code quality

- Implement the requested outcome, not adjacent wish-list cleanup.
- Prefer existing abstractions over duplicate helpers.
- Do not introduce a new dependency when the repository can already solve the problem cleanly.
- Preserve public behavior unless the user explicitly asks to change it.
- Keep patches reviewable and reversible.
- Fix root causes/invariants rather than only the first observed symptom.
- When a root cause affects multiple equivalent surfaces, fix the necessary set consistently.
- Add or strengthen tests when behavior changes and automation can prove it.
- Do not weaken validation to make a failing change pass.

## 11. Git and external side effects

Default behavior is local and reversible.

Never run these without an explicit user request:

- `git push`
- force push
- `git reset --hard`
- destructive `git clean`
- destructive history rewrite
- forced branch deletion
- release/publish/deploy actions
- GitHub issue/PR mutations

Do not create a commit unless the user explicitly asks.

Read-only Git inspection is encouraged.

If the user asks for a commit, inspect the diff first and commit only the intended files.

## 12. Secrets and sensitive material

Do not modify or expose:

- `.env` secrets
- API tokens
- cookies containing account credentials
- signing keys/certificates
- private keys
- production credentials

unless the user's task explicitly requires a safe, authorized change.

Never print secrets into logs or reports.

## 13. Legacy workflow files

The repository may contain historical Antigravity/OpenCode coordination files under `.ai-workflow/` and legacy Antigravity agent definitions under `.agents/agents/`.

They are **not automatically authoritative** for a new Codex task.

Only treat an `.ai-workflow/*` file as an active contract when the current user request explicitly references it or the active Codex skill requires it.

Historical `REVIEW_HISTORY.md`, old `CORRECTION_PLAN.md`, or stale `OPEN` labels are evidence of past work, not proof of a current defect.

Codex project agents live under `.codex/agents/`.
Codex project skills are intentionally kept under `.agents/skills/`, which Codex supports for repository-scoped skill discovery.

## 14. Communication

The user prefers outcome-oriented work.

During execution:

- give concise progress updates for long tasks;
- surface a concrete blocker as soon as it is proven;
- do not ask the user to choose an agent for routine workflows;
- do not ask for information already present in the repository;
- distinguish verified facts from inference.

Final reports should contain:

- what changed
- why
- verification performed
- important remaining uncertainty
- one clear next action only when a next action is actually required
