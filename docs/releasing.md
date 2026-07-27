# Releasing Pocket Financer

Pocket Financer uses Release Please as a controlled release gate. Development
can continue quickly on short-lived pull requests, while a release is published
only when the repository owner chooses to merge the automated release pull
request.

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

### 3. Configure the release automation token

Create a fine-grained GitHub personal access token owned by the repository
owner, restricted to this repository, with the minimum access needed to create
and update release pull requests, tags, and releases. At minimum, grant
read/write access to repository contents and pull requests. If the repository's
label rules require it, grant issues read/write access as well.

Store it as:

```powershell
gh secret set RELEASE_PLEASE_TOKEN
```

The dedicated token is required because events produced by the default
`GITHUB_TOKEN` do not start all downstream workflows. Rotate it before expiry
and update the secret without changing any repository files.

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

Before merging the first automated release pull request, verify:

- Its proposed version is exactly `1.0.0`.
- `CHANGELOG.md` accurately describes the stable baseline and notable changes.
- All required CI checks pass at the release pull-request head.
- All four Android signing secrets and `RELEASE_PLEASE_TOKEN` are configured.
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
  `fix:`, or breaking; inspect the Release Please workflow; confirm the token is
  present and unexpired. Do not create a tag manually.
- **Release PR CI does not start:** confirm the workflow used
  `RELEASE_PLEASE_TOKEN`, not only `GITHUB_TOKEN`, and that the token can create
  pull-request events.
- **Signing fails:** verify only the existence and exact names of the four
  secrets, the stored alias, and the keystore backup. Do not echo decoded
  material or weaken the build to accept an unsigned APK.
- **Signer fingerprint changed:** stop publication. Restore the correct
  keystore from an offline backup and investigate secret/key replacement.
- **Release workflow fails after the release PR merge:** fix the workflow on a
  normal `codex/fix-<slug>` pull request, rerun the failed workflow when safe,
  and preserve the allocated version/tag. Do not invent a replacement version
  manually.
- **Signing key is lost:** a new key cannot update existing self-distributed
  installations. Treat this as a product migration requiring a new application
  identity or user uninstall/reinstall; do not silently rotate the key.
