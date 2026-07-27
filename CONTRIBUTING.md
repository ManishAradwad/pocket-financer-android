# Contributing to Pocket Financer

Pocket Financer uses short-lived branches, protected pull requests,
Conventional Commit titles, and automated releases. `main` should always be a
tested, releasable development branch.

## Codex-first development

Codex should handle repository changes through a branch and pull request, not
by pushing implementation commits directly to `main`.

Start a task with a request like:

> Implement `<feature>` end to end. Follow `AGENTS.md`, update from `main`,
> create a focused `codex/feat-<slug>` branch, run the relevant tests and build,
> push it, and open a ready pull request with a Conventional Commit title. Do
> not push directly to `main`.

For a bug, use `codex/fix-<slug>`. Codex may choose another short type when it
better describes the work, but every working branch must remain under
`codex/`.

The normal flow is:

```text
origin/main
  -> codex/<type>-<slug>
  -> pull request + required CI
  -> squash merge
  -> Release Please updates its release pull request
  -> owner explicitly merges the release pull request
  -> signed stable APK + checksum + GitHub Release
```

Before editing, Codex must read `AGENTS.md` and `copilot-instructions.md` and
verify relevant documentation against the current code.

Codex should perform the Git operations itself when authenticated: create the
branch, commit, push, open the pull request, inspect CI, and update the same
branch if a check exposes a real defect. It should report a concrete
authentication or policy blocker instead of asking the user to perform routine
steps or weakening repository protection.

## Pull requests

Keep each pull request focused on one coherent change. Use the template and
describe the user-visible result, affected runtime paths and state transitions,
tests run, and any intentional tradeoffs.

The pull-request title is also the eventual squash-commit message. Use one of
these forms:

| Title | Release effect |
| --- | --- |
| `feat: add category budgets` | Minor version |
| `fix: prevent duplicate SMS ingestion` | Patch version |
| `feat!: replace the export format` | Major version |
| `docs: explain model setup` | No release by itself |
| `test: cover retry recovery` | No release by itself |
| `refactor: isolate parser state` | No release by itself |
| `build: update Android packaging` | No release by itself |
| `ci: harden APK validation` | No release by itself |
| `chore: refresh tooling` | No release by itself |

Use an optional scope when useful, for example `fix(sms): reject duplicate
candidate IDs`. For an incompatible change without `!`, put
`BREAKING CHANGE: <migration impact>` in the squash-commit body and verify it is
preserved when merging. Prefer `!` in the title when there is any doubt.

Before requesting merge:

- Trace the change end to end as required by `AGENTS.md`.
- Add or update tests at the lowest practical layer.
- Run the affected module tests and the appropriate app build or lint check.
- Confirm the required GitHub Actions checks pass.
- Resolve review conversations and update documentation when behavior changes.

Use squash merge. Delete the short-lived branch after merging. Do not merge a
feature pull request with a failing or pending required check.

To have Codex finish an already-open feature pull request:

> Inspect the current feature pull request and its required checks. Resolve any
> valid failure on the same branch. If every required check passes and the PR
> matches its stated scope, squash-merge it and confirm that Release Please
> updated its automated release pull request. Do not publish a release.

## Versions and release notes

Feature pull requests must not manually edit `version.txt`, `CHANGELOG.md`,
release tags, or GitHub Release assets. Release Please derives the next SemVer
version from squash commits on `main` and maintains those files in its
automated release pull request.

Several features and fixes can accumulate in that pull request while
development continues. Merging ordinary pull requests does not publish an APK.
The owner chooses the release moment by explicitly merging the automated
Release Please pull request after its checks and notes are correct.

To ask Codex to prepare a release safely:

> Inspect the open automated Release Please pull request. Verify its proposed
> version, changelog, CI results, and release readiness against
> `docs/releasing.md`. Report any blockers. Do not merge it yet.

To publish after reviewing that report:

> Publish the release by merging the verified automated Release Please pull
> request. Monitor the release workflow and verify the signed APK, checksum,
> tag, and stable GitHub Release. Do not create a tag or release manually.

The second request is explicit authorization to publish externally. See
`docs/releasing.md` for initial setup, signing-key custody, incident handling,
and verification.
