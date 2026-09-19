# PawBridge Backend Agent Guide

## Scope

This file applies to the entire `pawbridge-backend-k8s` repository.
Read the closest `AGENTS.md` before changing files. A more specific nested
`AGENTS.md` may add or override rules for its directory.

## Source of Truth

- Verify current facts from Git, source code, tests, and deployed resources.
- Treat Obsidian `Projects/pawbridge` notes as the canonical home for project
  plans, decisions, migration records, and investigation notes.
- Keep repository Markdown only when it must version with code, such as the
  root README, PR template, this guide, or an executable runbook tied to a
  concrete script or manifest.
- Never describe a local edit, test, image build, or manifest render as a live
  deployment or production verification.

## UI and Figma Work

- Before changing or creating a PawBridge screen, inspect the current deployed
  screen, the latest approved Figma baseline, and the connected design system.
- Reuse the current header, footer, content width, navigation, typography,
  colors, and shared components before designing page-specific content.
- When a matching asset exists in the connected Figma design system, use its
  linked component or variant instance. Do not redraw, detach, or copy it into
  raw frames merely to reproduce the same appearance.
- If a reusable pattern is missing, add or extend the appropriate design-system
  component before repeating it across screens. Bind component-controlled
  color, spacing, typography, radius, stroke, and state values to the existing
  variables and styles instead of hardcoding them in screen frames.
- Before reporting a Figma screen complete, audit component instance links and
  variable or style bindings in addition to visual similarity. A detached copy
  is not design-system reuse.
- Label structural explorations as structure concepts. Do not present them as
  completed designs.
- Preserve existing Figma screens. Create comparison or successor pages and
  record the page, version, status, and implementation state in Figma history.
- Create desktop and mobile layouts for the full page, including the header and
  footer. Cover normal, loading, empty, error, selected-filter, and overflow
  states when the flow can reach them.
- Before reporting a Figma task complete, render and inspect every changed
  desktop and mobile frame for clipping, alignment, spacing, responsive
  behavior, and consistency with the current baseline.
- Keep Figma approval separate from frontend implementation. Do not describe a
  concept as implemented, merged, deployed, or production-verified.

## Git and Pull Requests

- Use the latest `origin/dev` as the default base unless the user explicitly
  selects another base.
- Before creating or switching a branch or worktree, verify whether the remote
  base ref is current, record the exact base SHA, inspect `HEAD...base`
  divergence and the base-relative effective diff, then show the proposed base,
  branch name, and worktree path and wait for approval. If the remote could not
  be refreshed, report that limitation instead of calling the ref current.
- One branch equals one pull request with one primary review purpose.
- Do not mix feature work, bug fixes, refactoring, CI, documentation cleanup,
  data repair, or deployment changes unless they are inseparable parts of one
  explicit contract. Explain any exception before implementation.
- Use a clean worktree based on `origin/dev` when the current worktree already
  contains changes. Do not stash, reset, discard, or rewrite user changes
  without explicit approval.
- Never stage a dirty repository with `git add -A` or `git add .`. Stage only
  the exact paths owned by the current PR.
- Do not commit CRLF-only changes, generated build output, IDE files, logs,
  dumps, credentials, changes whose normalized content is already on the base,
  or untracked copies of paths already tracked with the same base content.
- Use branch prefixes that state the change type: `feat/`, `fix/`, `ci/`,
  `docs/`, or `chore/`.
- Use a concise `type: Korean noun phrase` commit title and show the exact title
  before committing, then wait for approval.
- Before pushing, show the exact push command and wait for approval.
- Before creating a PR, show the exact command, Korean PR title, and full PR
  body, then wait for approval.
- Use `.github/PULL_REQUEST_TEMPLATE.md` and check only items proven by the
  actual diff and verification evidence.
- Do not force-push, rewrite shared history, or merge without explicit approval.

## Change Boundaries

- Prefer a service-scoped PR. A change spanning services must follow one
  concrete API, event, or data contract and include all protection required to
  keep that contract reviewable.
- Keep unrelated cleanup and opportunistic refactoring out of a functional PR.
- Treat files under `infrastructure/` as runtime contracts. Inspect connector,
  topic, mapping, and reindex changes together with affected producers and
  consumers. Keep independently deployable changes in separate PRs; combine
  them only when one explicit contract requires atomic review and rollout.
- Keep destructive or one-time data scripts out of application resources by
  default. Put load-test fixtures under the owning test project and require an
  explicit safety guard for scripts that truncate, delete, reindex, or replay.

## Verification

- This repository has no root Gradle project. Run the Java version and wrapper
  declared by the target service from that service directory, for example
  `cd store-service && bash ./gradlew test`.
- Run the narrowest relevant test first, then the target service test suite
  when risk justifies it.
- For Kafka and Outbox changes, verify topic, key, headers, payload shape,
  retry/acknowledgment behavior, connector configuration, and failure paths.
- For MySQL-to-Elasticsearch changes, distinguish code tests, manifest
  validation, isolated reindex tests, and live alias cutover evidence.
- If a required runtime or tool is unavailable, report an environment blocker;
  do not call the code verified and do not install tools without approval.

## Troubleshooting Records

- After completing a feature, record actual development, test, or deployment
  troubleshooting in the owning project's existing Obsidian structure without
  waiting for another request.
- Record the symptom and trigger, impact, confirmed cause and evidence,
  attempted actions, applied fix, verification result, unresolved issues, and
  prevention follow-up.
- Separate suspected causes from confirmed causes, proposals from applied
  changes, and local verification from production verification. Do not record
  an unresolved issue as fixed.
- Do not create an empty troubleshooting note when no troubleshooting occurred.
  Redact credentials, tokens, signed URLs, and other secret values.
- Keep projects separate, link the record from the relevant existing document,
  and read the saved file back before reporting the documentation complete.

## Secrets and Deployment

- Never commit runtime `.env` files, Kubernetes Secret values, R2 credentials,
  registry tokens, kubeconfigs, database dumps, or redaction output containing
  secrets. A reviewed `.env.example` containing no secret is allowed.
- Secret and credential fields in templates and examples must use placeholders.
  Safe public endpoints, bucket names, service names, and feature defaults may
  use real version-controlled values.
- Building or pushing an image and changing a manifest are separate from
  deploying it. Live Kubernetes, Kafka Connect, database, and Cloudflare
  mutations require explicit approval, a rollback path, and post-change checks.

## Documentation Cleanup

- Before deleting repository documentation, confirm that reusable information
  is preserved in Obsidian or in a version-coupled runbook that remains beside
  the code.
- Perform documentation cleanup in a dedicated PR and verify links, secret
  candidates, and references to removed paths.
