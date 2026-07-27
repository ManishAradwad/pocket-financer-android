# CI/CD and Release Operations

This document is the durable operational reference for Pocket Financer's
continuous integration, versioning, signing, release automation, repository
controls, and dependency-update policy. It describes the live system after the
stable `v1.0.0` bootstrap completed on 2026-07-27.

It was last verified against the repository files and GitHub settings on
2026-07-27. The workflow and configuration files are executable truth; if they
and this document ever disagree, stop, verify the live behavior, and update
this runbook in the same pull request.

Use [releasing.md](releasing.md) for the detailed release checklist, signing-key
custody, hotfix procedure, and incident recovery.

## Current guarantees

- Changes to `main` go through a pull request and the required
  `Build, lint, and unit tests` check.
- The repository accepts squash merges only and keeps a linear history.
- `version.txt` is the stable SemVer source of truth.
- Release Please owns normal version bumps, changelog updates, `v*` tags, and
  GitHub Releases.
- Stable APKs are production-signed, version-checked, signature-checked, and
  accompanied by a SHA-256 checksum.
- A failed draft release can be rebuilt from its immutable tag with the
  corrected workflow from `main`.
- No AI service, AI reviewer, model API, or AI approval is part of CI,
  repository protection, version calculation, signing, or publication.

## Delivery flow

| Stage | Trigger | Deterministic gate | Output |
| --- | --- | --- | --- |
| Pull request | Pull request targeting `main` | Debug unit tests, Android lint, debug APK build | Reviewable, mergeable change |
| Main integration | Push to `main` | The same CI gate | Tested `main` and downloadable debug artifact |
| Release preparation | Releasable squash commit on `main` | Release Please version/changelog calculation | One updated release pull request |
| Release publication | Explicit merge of the release pull request | Unit tests, release lint, R8 build, signing and artifact verification | Stable GitHub Release, APK, checksum |
| Draft recovery | Manual Release workflow dispatch with `release_tag` | Existing draft/tag/SHA/version validation plus full release build | Recovered publication of the allocated version |

Merging an ordinary feature, fix, documentation, dependency, or CI pull request
never publishes an APK by itself. Publication begins only when the generated
Release Please pull request is deliberately merged.

## Pull-request and main CI

The workflow is `.github/workflows/ci.yml`.

### Triggers and permissions

- Pushes to `main`.
- Pull requests targeting `main`.
- Manual `workflow_dispatch`, including explicit dispatches on a Release Please
  branch.
- Repository token permission: `contents: read`.
- Superseded runs for the same pull request or ref are cancelled.
- Job timeout: 60 minutes on `ubuntu-latest`.

### Toolchain

- Checkout with persisted credentials disabled.
- Temurin JDK 17.
- Gradle wrapper validation and Gradle caching.
- Android platform 36 and platform tools.
- Android NDK `27.3.13750724`.

The stable release job additionally provisions Android Build Tools `36.0.0`
and CMake `3.22.1` explicitly.

### Required commands

```text
./gradlew testDebugUnitTest --no-daemon --stacktrace
./gradlew lintDebug --no-daemon --stacktrace
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

`testDebugUnitTest` resolves matching tasks across included Gradle modules. The
required branch-protection context is the GitHub Actions job named
`Build, lint, and unit tests`.

### CI artifacts

Artifacts are retained for 14 days:

- JUnit results and unit-test reports.
- Android lint HTML, XML, SARIF, and text reports.
- The full debug APK.

Missing report files are tolerated so a failed task can still preserve whatever
diagnostics exist. A missing debug APK fails the artifact-upload step.

## Version model

`version.txt` must contain stable SemVer in the form `major.minor.patch`.
Pre-release or build metadata is rejected. Minor and patch values must each be
between 0 and 999.

Gradle derives:

```text
versionName = major.minor.patch
versionCode = major * 1,000,000 + minor * 1,000 + patch
```

The derived `versionCode` must be between `1` and `2,100,000,000`, so `0.0.0`
and versions whose major component makes the result exceed Android's accepted
range are rejected.

Examples:

| Version | Android version code |
| --- | ---: |
| `1.0.0` | `1000000` |
| `1.0.1` | `1000001` |
| `1.2.3` | `1002003` |

The stable application ID remains `com.pocketfinancer`. Debug builds use
`com.pocketfinancer.debug` and append `-debug` to `versionName`, so developer
and production installations can coexist without sharing signing identity or
encrypted data.

## Release Please

The workflow is `.github/workflows/release.yml`; configuration lives in
`release-please-config.json` and `.release-please-manifest.json`.

The repository uses Release Please's root `simple` release strategy:

- `fix:` requests a patch release.
- `feat:` requests a minor release.
- `!` or `BREAKING CHANGE:` requests a major release.
- `docs:`, `test:`, `refactor:`, `build:`, `ci:`, and `chore:` do not request a
  release by themselves.
- Tags include the `v` prefix.
- Release Please initially creates a draft, non-prerelease GitHub Release.
- The generated release pull request updates `version.txt` and `CHANGELOG.md`.

The release pull request remains open and accumulates later releasable commits.
Do not manually edit its generated version or changelog to force a desired
result; correct the underlying Conventional Commit history or configuration.

### Release pull-request CI

Release Please uses `RELEASE_PLEASE_TOKEN` when that optional secret is present
and otherwise uses the built-in `GITHUB_TOKEN`.

When the built-in token creates or updates a release pull request, the Release
workflow explicitly dispatches `ci.yml` on that pull request's head branch.
The dispatch itself requires `actions: write`. The Release Please job's complete
permission set is `actions: write`, `contents: write`, `issues: write`, and
`pull-requests: write`. It avoids any dependency on Codex, a personal
workstation credential, or an AI reviewer to start the required check.

## Signed release build

The release build runs only when Release Please creates a release after its
pull request is merged, or when an existing draft tag is supplied through the
recovery input.

Normal and recovery release runs share the `stable-release` concurrency group.
Running jobs are not cancelled, so publications are serialized instead of
racing each other.

The job:

1. Checks out the exact Release Please SHA, or exact `refs/tags/<release_tag>`
   during recovery.
2. Confirms the checked-out `version.txt`, tag, version, and commit SHA agree.
3. During recovery, refuses to continue unless the matching GitHub Release is
   still a draft and the tag resolves to the checked-out commit.
4. Decodes the production keystore into runner temporary storage.
5. Runs:

   ```text
   ./gradlew testDebugUnitTest lintRelease :app:assembleRelease \
     --no-daemon --stacktrace
   ```

6. Rejects missing or unsigned release APKs.
7. Checks the APK `versionName`.
8. Runs `apksigner verify --verbose --print-certs`.
9. Accepts both legacy `Signer #N` and scheme-qualified `V2 Signer:` certificate
   labels, then compares the first signer SHA-256 digest with
   `release-signing-cert.sha256`.
10. Produces `pocket-financer-v<version>.apk` and its `.sha256` file.
11. Preserves lint reports, R8 mapping, and native debug symbols for 90 days,
    including when an earlier release step fails.
12. Adds the first-stable migration notice for `1.0.0`.
13. Uploads the verified APK and checksum to the draft release.
14. Publishes it as stable, non-prerelease, and latest.
15. Removes the temporary keystore under `if: always()`.

The release job has a 75-minute timeout, runs in the `release` environment, and
has `contents: write` only. The environment currently has no required reviewer;
merging the verified release pull request is the intentional human publication
decision.

## Signing material and secrets

Required repository Actions secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Optional automation secret:

- `RELEASE_PLEASE_TOKEN`

The built-in token plus explicit CI dispatch is the active fallback when the
optional secret is absent. That optional secret was absent at the last
verification; all four required Android signing secret names were present.

Secret values and the production keystore are intentionally not recorded in
Markdown or any other repository file. GitHub exposes only their configured
names to repository administration, not their values.

Signed release artifact tasks fail before building when any signing variable is
missing, the keystore path is unreadable, or signing configuration is
incomplete. The guarded tasks are `assembleRelease`, `bundleRelease`,
`packageRelease`, and `signReleaseBundle` for the app module. `lintRelease`
alone does not require signing. There is no debug-signing fallback for release
artifacts.

The reviewed public certificate SHA-256 fingerprint is:

```text
08:65:6A:08:56:A1:C1:4E:30:4E:8E:C9:F6:06:82:F8:EC:7C:E4:09:DB:CC:A9:61:07:75:D5:2F:BC:88:45:B1
```

The repository owner must keep at least two encrypted offline backups of the
valid production keystore and recovery record. Never print secret values in
logs, comments, issues, or troubleshooting output.

## Repository controls

The current GitHub repository settings are:

- `main` is protected.
- Changes must use a pull request.
- `Build, lint, and unit tests` must pass and must be based on current `main`.
- Protection applies to administrators.
- Required approving-review count is zero.
- Review conversations must be resolved.
- Linear history is required.
- Force pushes and branch deletion are disabled.
- Squash merge is enabled; merge commits and rebase merges are disabled.
- Merged source branches are deleted automatically.
- Default Actions token permission is read-only.
- GitHub Actions may create pull requests so Release Please can operate.

The GitHub setting that permits Actions to create pull requests is presented by
GitHub as a combined "create and approve pull requests" setting. No Pocket
Financer workflow calls the pull-request approval API or submits an approval.

## Dependency updates

Dependabot checks Gradle and GitHub Actions weekly on Monday in
`Asia/Kolkata`.

For each ecosystem:

- At most one version-update pull request is open.
- Minor and patch updates are grouped.
- Major version updates are ignored by automatic version-update PR creation and
  require a deliberate compatibility change.
- Conventional `chore(deps):` or `chore(ci):` prefixes are used.
- No dependency pull request is auto-merged.

Security updates are a separate GitHub/Dependabot mechanism and may appear
outside the version-update grouping when enabled and necessary.

## Stable `v1.0.0` baseline

The first production-signed stable release was published on 2026-07-27:

- Release: <https://github.com/ManishAradwad/pocket-financer-android/releases/tag/v1.0.0>
- Tag commit: `b97c09cf5b12e8f59c9613ebc866a479ac2e475e`
- Package: `com.pocketfinancer`
- Version code: `1000000`
- APK size: `98,541,947` bytes
- APK SHA-256:
  `5E1E55621FCDEEB6514A2A43B50AE6EAE90D47B5DA4822FCD49502902512891B`
- APK Signature Scheme v2 verification: passed.
- Signer certificate verification: passed.
- Release state: stable, latest, not draft, not prerelease.
- Signed release recovery run:
  <https://github.com/ManishAradwad/pocket-financer-android/actions/runs/30283305460>
- Post-bootstrap `main` CI run:
  <https://github.com/ManishAradwad/pocket-financer-android/actions/runs/30285848121>
- Release pull-request CI run:
  <https://github.com/ManishAradwad/pocket-financer-android/actions/runs/30286075639>

The earlier `v0.1.0` APK used the old debug signing identity. Upgrading to
`v1.0.0` therefore requires uninstalling the prerelease first. Because the app
sets `android:allowBackup="false"`, that uninstall removes the encrypted local
database, model files, and settings. The `v1.0.0` release notes contain this
warning.

## AI and Codex boundary

CI/CD and repository governance are deterministic and tool-agnostic:

- No OpenAI or Codex action runs in GitHub.
- No hosted model API or AI key is required.
- No AI reviewer or AI approval is required.
- Branch protection does not inspect the branch author or require a
  `codex/*` prefix.
- CI behavior is identical regardless of whether a human, IDE, script, or
  coding assistant prepared the pull request.

Repository documentation contains a Codex-oriented contributor workflow because
Codex uses `codex/*` branches when it prepares changes. That is a contributor
convention, not a GitHub enforcement rule.

The Android product itself intentionally contains local `llama.cpp`/GGUF
inference. That product runtime is separate from CI governance. The
`android_mock_ui` directory also contains unused Gemini package/key scaffolding;
it is not a Gradle module, is not imported by its current source, and is not
built by Android CI.

## Known gaps and deliberate non-goals

The current automation does not yet provide:

- Emulator or device instrumentation tests in CI.
- An SBOM or signed build provenance.
- A dedicated SAST or dependency-vulnerability workflow beyond installed
  GitHub integrations and Dependabot.
- Immutable commit-SHA pinning for GitHub Actions; actions currently use
  reviewed major-version tags.
- A required reviewer on the `release` environment.
- Cryptographic pinning of runtime GGUF model downloads.

The native build shallow-fetches `llama.cpp` at a selected upstream tag. Runtime
model URLs use upstream Hugging Face `resolve/main` paths. Those supply-chain
boundaries are independent of Codex and should be hardened separately if
stronger reproducibility is required.

## Operational rules

- Never push routine work directly to `main`.
- Never bypass required CI.
- Never manually move or reuse a published `v*` tag.
- Never replace bytes beneath an existing published version.
- Never upload an unsigned or locally substituted stable APK.
- Never echo signing material.
- Prefer a higher fix-forward patch release over attempting to downgrade users.
- Update this document whenever workflow triggers, permissions, required
  checks, signing inputs, version calculation, repository protection, artifact
  retention, or release recovery behavior changes.
