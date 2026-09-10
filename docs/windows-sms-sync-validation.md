# Windows SMS sync validation — 2026-09-10

Baseline: Android PR #40, commit 64a91f7871f59036e8ac45e5f5023f8a268b788c.

## Failures reproduced

- The Windows checkout used core.autocrlf=true. Frozen SMS assets were LF in Git
  and CRLF in the worktree/APK. Every admitted message failed the pinned currency
  asset hash check before structural analysis. Repeated import retried the same
  incompatible asset bytes.
- After preserving the release bytes, the selector deadline was treated as user
  cancellation. Historical import stopped and displayed Setup paused.

## Changes

- Pin the frozen asset directory to LF using .gitattributes. Do not normalize at
  runtime or weaken the expected hashes.
- Add an Android instrumentation test that hashes the actual packaged currency,
  prompt, and locale profile assets against the frozen runtime constants.
- Settle a local selector timeout as retained review with runtime_timeout and a
  recorded failed attempt. Parent cancellation still propagates as cancellation.

## Verification so far

- Windows debug build/install and packaged-asset instrumentation test passed.
- 110 pipeline unit tests passed, including selector timeout settlement and user cancellation.
- Retried the emulator's existing failed import without clearing application data.
  The complete batch finished: 24 messages checked, 14 eligible, with the same app
  process alive throughout and no new sync exception. Pipeline lint also passed.
  This is emulator functional evidence, not physical-device or model-accuracy evidence.
- Automatic persistence remains disabled. No private messages or model output are
  included in this report.

Other uncommitted recovery/review tests are separate ongoing work and are not
part of the Windows asset fix's validation claim.
