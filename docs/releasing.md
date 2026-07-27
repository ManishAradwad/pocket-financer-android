# Releasing Pocket Financer

Pocket Financer uses Release Please as a controlled release gate. Development
can continue quickly on short-lived pull requests, while a release is published
only when the repository owner chooses to merge the automated release pull
request.

This is also the durable CI/CD reference for future maintainers and agents. It
was last verified against the repository files and live GitHub settings on
2026-07-27. Executable configuration is the final authority; when it changes,
update this guide in the same pull request.

## Live automation reference

### Pull-request CI

`.github/workflows/ci.yml` runs for pushes to `main`, pull requests targeting
`main`, and manual dispatches. Its required job is named
`Build, lint, and unit tests` and runs:

```text
./gradlew testDebugUnitTest --no-daemon --stacktrace
./gradlew lintDebug --no-daemon --stacktrace
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

It uses JDK 17, Android platform 36, NDK `27.3.13750724`, and validated Gradle
wrapper/caching on `ubuntu-latest`. Test reports, lint reports, and the debug
APK are retained for 14 days. Missing reports are tolerated so failures retain
diagnostics; a missing debug APK fails CI. Superseded runs on the same pull
request or ref are cancelled.

### Version and release automation

`version.txt` contains stable `major.minor.patch` SemVer. Gradle derives:

```text
versionName = major.minor.patch
versionCode = major * 1,000,000 + minor * 1,000 + patch
```

Minor and patch components must be between 0 and 999, and the derived
`versionCode` must be between 1 and 2,100,000,000. Debug builds use application
ID `com.pocketfinancer.debug` and a `-debug` version suffix; stable builds use
`com.pocketfinancer`.

`.github/workflows/release.yml`, `release-please-config.json`, and
`.release-please-manifest.json` own normal releases:

- `fix:` requests a patch, `feat:` a minor, and `!` or
  `BREAKING CHANGE:` a major release.
- Non-release Conventional Commit types do not request a version by themselves.
- Release Please maintains one release pull request, then creates a draft
  `v<version>` GitHub Release when that pull request is explicitly merged.
- With no optional `RELEASE_PLEASE_TOKEN`, the workflow uses `GITHUB_TOKEN` and
  explicitly dispatches `ci.yml` on the release pull-request branch.
- The Release Please job has `actions`, `contents`, `issues`, and pull-request
  write permissions. The signed publication job has only `contents: write`.
- Normal and recovery runs share the non-cancelling `stable-release`
  concurrency group, so publications cannot race.

The 75-minute publication job checks out the exact release commit or recovery
tag, validates tag/version/SHA agreement, decodes the production keystore,
runs debug unit tests, release lint, and the minified release build, and rejects
missing, unsigned, wrongly versioned, or wrongly signed APKs. It verifies the
APK with `apksigner`, compares its signer against
`release-signing-cert.sha256`, emits a versioned APK and SHA-256 file, and only
then publishes the draft as stable and latest. Release diagnostics are retained
for 90 days, and temporary signing material is removed even after failure.

Signed app artifact tasks fail closed when signing configuration is absent.
`lintRelease` alone does not require signing. A failed publication can be
recovered only from an existing draft tag through the Release workflow's
`release_tag` input; the recovery path validates the immutable tag and commit
before rebuilding.

### Repository and dependency controls

The live GitHub controls at the last verification were:

- `main` requires pull requests and a current
  `Build, lint, and unit tests` result.
- Protection applies to administrators; the required approval count is zero,
  and review conversations must be resolved.
- Linear history is required; force pushes and branch deletion are disabled.
- Only squash merge is enabled, and merged source branches are deleted.
- Default Actions token permission is read-only.
- GitHub Actions may create pull requests so Release Please can operate. GitHub
  presents this as a combined create/approve setting, but no repository
  workflow submits pull-request approvals.
- The `release` environment currently has no required reviewer. The explicit
  release-pull-request merge is the human publication gate.

`.github/dependabot.yml` checks Gradle and GitHub Actions weekly on Monday in
`Asia/Kolkata`. Minor and patch updates are grouped into at most one open pull
request per ecosystem. Major updates are ignored by automated version-update
PRs and require an intentional compatibility change. Nothing auto-merges.

Required signing secret names are:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

All four names existed at the last verification. Their values and the keystore
must never be stored in Markdown, logs, issues, or repository files.
`RELEASE_PLEASE_TOKEN` is optional and was not configured at the last
verification.

### Optional Copilot review

The repository owner's personal GitHub setting currently requests GitHub
Copilot code review for pull requests authored by that account. This review is
accepted but non-gating: it is not required by branch protection, does not
approve the pull request, and is not consumed by versioning, signing,
publication, or any Pocket Financer workflow.

No OpenAI or Codex action runs in GitHub, no hosted model API or AI key is
required for CI/CD, and branch protection does not require a `codex/*` author or
branch. The Android app's local `llama.cpp`/GGUF inference is product runtime,
not release governance.

## Release model

1. A `codex/*` pull request passes CI and is squash-merged into `main`.
2. Release Please reads the Conventional Commit on `main`.
3. Release Please creates or updates one release pull request containing the
   proposed `version.txt` and `CHANGELOG.md` changes.
4. More feature and fix pull requests can merge while that release pull request
   remains open; Release Please keeps it current.
5. The owner explicitly merges the automated release pull request.
6. Automation creates the immutable `v<version>` tag and stable GitHub Release,
   builds the production APK, signs and verifies it, and attaches the APK plus
   its SHA-256 checksum.

Do not manually create a release pull request, edit its generated version or
changelog without diagnosing the underlying commit history/configuration,
create or move a `v*` tag, or upload a stable APK outside the workflow.

Version calculation follows SemVer:

- `fix:` produces a patch release, for example `1.0.0` to `1.0.1`.
- `feat:` produces a minor release, for example `1.0.1` to `1.1.0`.
- `feat!:` or `BREAKING CHANGE:` produces a major release.
- Other supported Conventional Commit types do not request a release by
  themselves, but can appear in a later release as configured.

`version.txt` is the release source of truth. Gradle derives the APK
`versionName` and monotonically increasing `versionCode` from it. Release
Please, not feature branches, changes this file during normal operation.

## One-time repository setup

### 1. Create and protect the production signing key

Generate the key on a trusted local machine, outside the repository:

```powershell
keytool -genkeypair -v `
  -keystore pocket-financer-release.jks `
  -alias pocket-financer `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Use long, unique store and key passwords. Record the alias, passwords, creation
date, certificate SHA-256 fingerprint, and recovery location in the owner's
password manager. The reviewed public fingerprint is also committed in
`release-signing-cert.sha256`; the release workflow rejects any APK signed by a
different certificate.

Make at least two encrypted offline backups in separate physical locations.
Never commit the keystore, copy it into the repository, attach it to a task, or
paste its base64 value or passwords into a pull request, issue, log, or chat.
Losing this key means existing installations cannot be updated with a newly
signed APK.

Inspect and record the certificate:

```powershell
keytool -list -v -keystore pocket-financer-release.jks -alias pocket-financer
```

### 2. Configure GitHub Actions signing secrets

Create a base64 representation locally without adding a file to the repository:

```powershell
$keystoreBytes = [IO.File]::ReadAllBytes(
  (Resolve-Path ".\pocket-financer-release.jks")
)
$keystoreBase64 = [Convert]::ToBase64String($keystoreBytes)
$keystoreBase64 | gh secret set ANDROID_RELEASE_KEYSTORE_BASE64
Remove-Variable keystoreBase64, keystoreBytes
```

Set the remaining repository secrets. The commands prompt securely for each
value:

```powershell
gh secret set ANDROID_RELEASE_STORE_PASSWORD
gh secret set ANDROID_RELEASE_KEY_ALIAS
gh secret set ANDROID_RELEASE_KEY_PASSWORD
```

The release build decodes the key to a temporary path and exposes that path as
`ANDROID_RELEASE_KEYSTORE_PATH`. Gradle reads that variable plus
`ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and
`ANDROID_RELEASE_KEY_PASSWORD`. A release must fail closed if any value is
missing.

Never print or retrieve secret values to troubleshoot. Confirm only that the
secret names exist:

```powershell
gh secret list
```

### 3. Optionally configure a dedicated release automation token

The active workflow can use the built-in `GITHUB_TOKEN`. When that token creates
or updates a release pull request, the Release workflow explicitly dispatches
`ci.yml` on the release branch so the required check starts without a personal
token.

If repository policy later requires a dedicated identity, create a fine-grained
GitHub personal access token owned by the repository owner, restricted to this
repository, with the minimum access needed to create and update release pull
requests, tags, and releases. At minimum, grant read/write access to repository
contents and pull requests. If the repository's label rules require it, grant
issues read/write access as well.

Store it as:

```powershell
gh secret set RELEASE_PLEASE_TOKEN
```

When configured, rotate the dedicated token before expiry and update the secret
without changing repository files. When it is absent, preserve the Release
workflow's `actions: write` permission and explicit CI dispatch fallback.

### 4. Protect `main`

Create a GitHub branch ruleset targeting `main`:

- Require changes through a pull request.
- Require the `Build, lint, and unit tests` CI check.
- Require all review conversations to be resolved.
- Block force pushes and branch deletion.
- Do not allow required checks to be bypassed routinely.
- Require one approval only when a second trusted reviewer is available;
  otherwise the explicit owner merge plus required checks remains the gate.

In repository merge settings, enable squash merging and automatic deletion of
merged head branches. Treat the pull-request title as the authoritative squash
commit message.

Release Please also uses a pull request, so these controls preserve the manual
release gate without granting automation a direct bypass to `main`.

## Stable update invariants

Every stable APK that is intended to update an existing installation must
preserve all three of these properties:

- The application ID remains `com.pocketfinancer`.
- The Android `versionCode` is greater than every previously published stable
  APK. The current mapping is `major * 1,000,000 + minor * 1,000 + patch`.
- The APK is signed by the same production signing key.

Changing any one is a product migration, not a routine release. Stop and
document the user impact before doing so.

Debug builds intentionally use `com.pocketfinancer.debug`, allowing Codex and
developer builds to coexist with the stable app without sharing its encrypted
data or signing identity.

## First stable release (`v1.0.0`)

The existing `v0.1.0` prerelease APK was debug-signed by the old CI workflow.
The first production-signed APK intentionally changes that signing identity.
Existing prerelease users must:

1. Export or otherwise preserve any data they need, if a supported export path
   is available.
2. Uninstall the debug-signed prerelease.
3. Install the stable `v1.0.0` APK.

The app declares `android:allowBackup="false"`, so uninstalling erases its local
encrypted database, downloaded model, and settings, and Android cannot restore
them automatically. Put this warning prominently in the `v1.0.0` release
notes. Do not imply that an in-place upgrade from the prerelease is supported.

The verified production baseline published on 2026-07-27 is:

- Release:
  <https://github.com/ManishAradwad/pocket-financer-android/releases/tag/v1.0.0>
- Tag commit: `b97c09cf5b12e8f59c9613ebc866a479ac2e475e`
- Package/version: `com.pocketfinancer`, version `1.0.0`, version code `1000000`
- APK size: `98,541,947` bytes
- APK SHA-256:
  `5E1E55621FCDEEB6514A2A43B50AE6EAE90D47B5DA4822FCD49502902512891B`
- Signer certificate SHA-256:
  `08:65:6A:08:56:A1:C1:4E:30:4E:8E:C9:F6:06:82:F8:EC:7C:E4:09:DB:CC:A9:61:07:75:D5:2F:BC:88:45:B1`
- APK Signature Scheme v2 verification passed; the release is stable, latest,
  not draft, and not a prerelease.

Before merging the first automated release pull request, verify:

- Its proposed version is exactly `1.0.0`.
- `CHANGELOG.md` accurately describes the stable baseline and notable changes.
- All required CI checks pass at the release pull-request head.
- All four Android signing secrets are configured. If
  `RELEASE_PLEASE_TOKEN` is absent, the built-in-token CI dispatch fallback is
  intact.
- The production keystore has verified offline backups.
- The certificate SHA-256 fingerprint is recorded privately.
- The migration warning above is present in the release notes.

After `v1.0.0`, every update must use the same production key. Normal in-place
updates then work as long as the package ID remains unchanged and each release
has a greater `versionCode`.

## Routine release through Codex

Ask Codex to audit before publishing:

> Inspect the open automated Release Please pull request. Confirm its proposed
> SemVer version, generated changelog, required CI checks, and release readiness
> against `docs/releasing.md`. Report blockers and do not merge.

Review the report. When the timing and contents are correct, explicitly
authorize publication:

> Merge the verified automated Release Please pull request and monitor the
> release workflow to completion. Verify the immutable tag, stable GitHub
> Release, signed versioned APK, and SHA-256 checksum. Do not create or modify
> release artifacts manually.

Codex should use the existing automated pull request; it must not manufacture a
replacement release PR. It should stop and report a blocker if the proposed
version is wrong, checks fail, required secrets are unavailable, or the
changelog is materially incomplete.

## Post-release verification

For every release:

1. Confirm the tag and release version match `version.txt`.
2. Confirm the GitHub Release is published, stable (not a prerelease), and
   marked latest when appropriate.
3. Download the APK and checksum from the GitHub Release rather than reusing a
   local build.
4. Validate the checksum:

   ```powershell
   Get-FileHash .\pocket-financer-v1.0.0.apk -Algorithm SHA256
   Get-Content .\pocket-financer-v1.0.0.apk.sha256
   ```

5. Validate the signature and certificate:

   ```powershell
   apksigner verify --verbose --print-certs .\pocket-financer-v1.0.0.apk
   ```

6. Confirm the signer SHA-256 matches the privately recorded production
   certificate fingerprint.
7. Install on a clean test device and smoke-test launch, onboarding, required
   permissions, SMS ingestion, local persistence, and restart recovery.

Substitute the actual version in commands and filenames.

## Emergency hotfix

Do not patch `main`, a tag, or a published APK in place.

1. Branch from current `origin/main` as `codex/fix-<urgent-slug>`.
2. Implement the smallest safe fix and regression test.
3. Open a pull request titled `fix: <description>`.
4. Require CI and perform the same review as any other production change.
5. Squash-merge it into `main`.
6. Inspect the updated automated Release Please PR; it should propose a patch
   version unless the hotfix is intentionally breaking.
7. Explicitly merge that release PR and complete all post-release checks.

This path preserves history, generates a higher Android `versionCode`, and
keeps installed users upgradeable.

## Bad release and rollback

Android normally rejects installing an APK with a lower `versionCode`, so
republishing an older APK is not a safe rollback for existing users. Prefer a
fix-forward patch release.

For a severe release:

1. Add a prominent warning to the affected GitHub Release and stop directing
   new users to it. Do not move or reuse its tag.
2. If distribution must stop, mark the affected release as not latest or as a
   prerelease while preserving the tag and audit history.
3. Revert the offending squash commit on a new
   `codex/fix-revert-<slug>` branch, add a regression test, and open a `fix:`
   pull request.
4. Merge through CI, then publish the resulting higher patch version by merging
   the automated Release Please PR.
5. Document any data migration or compatibility impact in the new release.

Never delete or retarget a published version to make different bytes appear
under the same tag. Users and checksums must be able to rely on released
artifacts being immutable.

## Recovery and troubleshooting

- **Release PR does not appear:** confirm the merged squash title is `feat:`,
  `fix:`, or breaking, and inspect the Release Please workflow. If
  `RELEASE_PLEASE_TOKEN` is configured, confirm that dedicated token remains
  valid. Otherwise confirm the workflow still uses `github.token`, GitHub
  Actions may create pull requests, and the job retains its documented
  permissions. Do not create a tag manually.
- **Release PR CI does not start:** with the built-in `GITHUB_TOKEN`, the
  Release workflow explicitly dispatches `ci.yml` on the updated release
  branch. Confirm the release job still has `actions: write`, `ci.yml` still
  supports `workflow_dispatch`, and the dispatched run targets the release PR
  head. If `RELEASE_PLEASE_TOKEN` is configured, its normal pull-request event
  is expected to start CI instead.
- **Signing fails:** verify only the existence and exact names of the four
  secrets, the stored alias, and the keystore backup. Do not echo decoded
  material or weaken the build to accept an unsigned APK.
- **Signer fingerprint changed:** stop publication. Restore the correct
  keystore from an offline backup and investigate secret/key replacement.
- **Release workflow fails after the release PR merge:** fix the workflow on a
  normal `codex/fix-<slug>` pull request and preserve the allocated version/tag.
  After the fix reaches `main`, dispatch the **Release** workflow from `main`
  with `release_tag` set to the existing draft tag. The recovery path requires
  that tag to still be a draft, checks out `refs/tags/<release_tag>`, derives and
  validates its exact version and commit, then rebuilds it with the corrected
  workflow. Do not rerun an old job after changing the workflow, because GitHub
  reruns use the workflow definition from the original commit. Do not invent a
  replacement version manually.
- **Signing key is lost:** a new key cannot update existing self-distributed
  installations. Treat this as a product migration requiring a new application
  identity or user uninstall/reinstall; do not silently rotate the key.
