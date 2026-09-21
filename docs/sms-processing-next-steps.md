# Android SMS processing next steps

Status: **platform handoff; not a completion claim**
Last reconciled: 2026-09-22

The shared repository `pF_slm_selection` owns the canonical architecture,
versioned contracts, sanitized vectors, host GGUF evaluation, native-trace import,
and the planned Android/iOS native scoring lanes. This repository owns the
Windows/Gradle, Kotlin/JNI, Room, Compose, emulator, and Android-device
implementation lane.

## Implemented source baseline

The additive v4 source includes strict extractor parsing, Unicode-scalar conversion,
exact minor-unit normalization, account resolution, sanitized vectors, v4
operation routing, Room migration, durable review/recovery state, review drafts,
atomic confirmation, full-source review, per-field highlights, and one active
native text selection. V1/v2/v3 readers and stored operations remain supported.

V4 is `review_only`. A complete valid result is therefore still retained for
review. Recorded automated and emulator runs are historical evidence only. They do
not prove that any previously observed emulator behavior is fixed, and they do not
replace a fresh run or a physical-device gate.

## Open Android observations

The latest emulator trial did not show the previously available live decoded-token
stream or the intended source-SMS span review experience. Its routing also did not
match the exception-only Review policy. Treat all three as open observations:
reproduce the exact flow on the current branch, identify the runtime/navigation
path actually used, and preserve evidence before implementing a fix.

## Next implementation change

After the shared repository freezes an additive successor contract:

1. Route a complete, strictly valid, uniquely resolved, non-duplicate posted result
   directly and atomically into Transactions.
2. Route only incomplete, invalid, ambiguous, abstained, interrupted,
   incompatible, or failed operations to Review.
3. Keep valid `none` handling separate from Transactions and Review according to
   the versioned evidence-retention policy.
4. Preserve legacy release routing; never silently reinterpret a stored v1-v4
   operation.
5. Keep corrections revision-bound, append-only, local label evidence. Explicit
   export and adjudication are required before approved, source-grounded,
   split-safe labels may improve the SLM or another pipeline component.

## Transparency and review verification

The processing UI must show the unchanged SMS, advisory analyzer evidence, model
request, live decoded token deltas/cumulative structured output, raw result,
validation, route, persistence result, and later owner correction as distinct
facts. Never display invented chain of thought and never put private text in logs.

Re-run accessibility coverage for the full source SMS, separate amount/direction/
account/counterparty highlights, screen-reader labels, focus order, and the
single-active-selection invariant. A later failure must not hide fields that were
successfully grounded earlier: Review shows every valid partial field from the
last completed stage on the unchanged SMS body.

## Shared evaluation lane

`pF_slm_selection` already provides the host GGUF evaluator and encrypted
native-trace import. A dedicated Android native evaluator/scorer is still planned.
It will run the exact app contract and model on emulator/device, export a
provenance-bound encrypted trace bundle, and score aggregate contract, model,
routing, review, recovery, latency, and resource results in the shared repository.
Host GGUF evidence is not Android runtime evidence.

## Windows/Android verification order

1. Verify shared bundle hashes and sanitized parity vectors without modifying
   frozen release assets.
2. Run focused parser, money, scalar, account, routing, Room migration, recovery,
   atomic confirmation, and presentation tests.
3. Run the complete Gradle unit/instrumentation/lint/build gates.
4. Use a fresh emulator state plus explicit migrated-store fixtures. Record new
   results separately from `docs/windows-sms-sync-validation.md`.
5. Run the target physical Android device matrix for model identity, inference,
   live decoding, process death, accessibility, memory, latency, thermal, and
   battery behavior.

Do not enable rollout or describe the full SMS implementation as complete until
the shared evaluation strategy and physical-device gates pass.
