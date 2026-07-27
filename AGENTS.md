# Repository Development Instructions

Read `copilot-instructions.md` for project architecture, build, test, and
emulator context. Verify documentation against the current code before relying
on it.

## Holistic Change Discipline

For every feature and bug fix, trace the behavior end to end before
implementation. Inspect every relevant production entry point and state owner,
including UI and ViewModels, persisted settings, foreground and background
processing, services and workers, inference and native code, repositories, and
restart or recovery paths.

Explicitly resolve all material states and transitions: defaults and upgrades,
idle, queued and in-flight behavior, concurrent or repeated actions,
cancellation, failure and retry, app or process restart, and downstream data or
cache compatibility. Consider privacy, security, latency, battery, memory,
accessibility, and truthful user feedback wherever relevant.

Runtime settings must have a defined consistency boundary. Choose explicitly
between safe immediate application, application to the next operation using a
per-operation snapshot, or temporarily disabling or deferring the control while
busy. Never allow one operation to run partly with the old value and partly with
the new value. Surface any material product choice rather than silently
guessing.

Test the primary behavior and relevant transitions or regressions at the lowest
practical layer. Run affected module tests and the appropriate build check.
Document intentionally unsupported cases and meaningful tradeoffs without
adding speculative complexity.

## Version Control and Release Discipline

All future Codex implementation work must use a short-lived branch and pull
request:

1. Update from `origin/main`.
2. Create a branch named `codex/<type>-<short-description>`, such as
   `codex/feat-budget-alerts` or `codex/fix-duplicate-import`.
3. Keep the branch focused, run the relevant tests and build checks, then push
   the branch.
4. Open a pull request targeting `main`.
5. Use a Conventional Commit pull-request title. The squash-merge title becomes
   the commit on `main`.

Codex should create the branch, commit, push, and open the pull request itself
when repository credentials permit; do not hand routine Git steps back to the
user. If authentication or repository policy blocks an action, report the exact
blocker without bypassing protection.

Do not commit or push feature, fix, refactor, documentation, dependency, or CI
work directly to `main`. Do not bypass required checks. A user may explicitly
authorize an exceptional bootstrap or recovery operation, but that does not
change the default for later work.

Use these release-relevant title forms:

- `feat: ...` for a backward-compatible feature (minor version).
- `fix: ...` for a backward-compatible correction (patch version).
- `feat!: ...` or a `BREAKING CHANGE:` footer for an incompatible change
  (major version).
- `docs:`, `test:`, `refactor:`, `build:`, `ci:`, and `chore:` for work that
  should not trigger a version by itself.

Release Please owns `version.txt`, `CHANGELOG.md`, `v*` tags, and stable GitHub
Releases during normal operation. Never manually edit a version or changelog in
a feature pull request, create or move a release tag, or upload a stable APK.
Merges to `main` cause Release Please to create or update one automated release
pull request. A release happens only when that automated release pull request is
merged.

Codex may inspect the release pull request and report whether its version,
changelog, and checks are correct. Codex must merge it only after the user
explicitly asks to publish or ship the release. Follow
`docs/ci-cd.md` for the authoritative automation and repository-controls
reference, and `docs/releasing.md` for signing setup, verification, hotfixes,
and recovery.
