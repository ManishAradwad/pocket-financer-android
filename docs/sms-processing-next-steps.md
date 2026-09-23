# Android SMS processing next steps

Status: **Android debug unit, lint, and build gates passed; emulator and device verification pending**
Last reconciled: 2026-09-23

The shared repository `pF_slm_selection` owns the canonical architecture,
versioned contracts, sanitized vectors, host GGUF evaluation, native-trace import,
and the planned Android/iOS native scoring lanes. This repository owns the
Windows/Gradle, Kotlin/JNI, Room, Compose, emulator, and Android-device
implementation lane.

## Implemented source baseline

The existing source includes strict extractor parsing, Unicode-scalar conversion,
exact minor-unit normalization, account resolution, sanitized vectors, durable
review/recovery state, review drafts, atomic confirmation, full-source review,
per-field highlights, and one active native text selection. Stored v1-v4
operations and readers remain supported without reinterpretation.

Commit `fcf6614` binds the shared automatic-routing contract to Android. The
`native-integration-v5` name is an internal immutable release identifier required
to preserve stored v1-v4 behavior; it is not another user-facing pipeline, screen,
or plan. New operations now use the single final policy: complete valid posted
results with one existing account and a clear duplicate assessment are persisted
atomically; valid `none` settles without a transaction; exceptions enter Review.
The frozen v4 path remains `review_only` for its existing operations and
original-configuration retries.

Implemented in `fcf6614`:

- hash verification for the shared automatic contract bundle;
- explicit retry lineage with a new operation and unchanged prior operation;
- additive review-case/2 evidence storage while review-case/1 remains unchanged;
- independent retention of safely grounded extractor fields and separately
  labelled analyzer suggestions;
- one Room transaction for transaction, revision, processing result, persistence
  decision, and owner-fenced settlement;
- source-event and fingerprint duplicate fencing, with retained parent reviews
  excluded from false duplicate classification;
- decoded-token callback propagation from JNI-facing extraction through the
  coordinator and `PipelineService` observer contract.

Commit `f6079ff` completes the existing Review surface for the automatic policy:

- `review-case/2` partial SLM fields are projected without converting analyzer
  suggestions into model output;
- all valid source spans remain highlighted while missing fields remain visibly
  unassigned;
- missing direction wording uses an explicit debit/credit owner control;
- confirmation requires a deliberate existing-account selection and every
  mandatory field to be valid;
- confirmation reuses the revision-bound atomic transaction path; and
- selecting a Needs Review card opens that case directly while the Review inbox
  remains available from its existing entry point.

Commit `01f3861` restores live generation visibility on the existing processing
surface. Automatic, historical, and manual operations now expose a bounded latest
decoded-token delta separately from bounded cumulative structured output. The
cumulative value is mandatory on every callback and is not reconstructed or
described as reasoning. Manual updates are fenced by run, candidate, and exact
attempt; automatic updates retain exact claim fencing. Terminal, cancellation,
stale-owner, and lifecycle paths scrub private transient text.

## Open Android observations

The emulator was not running during commits `fcf6614`, `f6079ff`, or `01f3861`,
so no emulator or physical-device behavior is claimed. Review and live-output
behavior are verified by focused JVM tests and Kotlin/instrumentation-test
compilation only. Runtime token timing, navigation, accessibility, process-death
recovery, and end-to-end persistence still require a fresh emulator.

## Next implementation change

Implemented and verified locally in `f6079ff`: review-case/2 projection through
the existing `GroundedReviewContent` and `EvidenceSelectionText`, explicit
direction fallback, deliberate existing-account selection, mandatory-field
confirmation gating, and direct Review-card navigation.

Implemented and verified locally in `01f3861`: separate live decoded-token and
cumulative structured-output presentation, coalescing, exact stale-operation
fencing, terminal/cancellation/lifecycle cleanup, and completed-output handling
across automatic, historical, and manual processing.

Planned verification next:

1. Connect the running emulator and run the specified routing, Review, live-output,
   retry, recovery, and no-duplicate scenarios. Do not convert local JVM or
   compile evidence into emulator evidence.
2. Keep corrections revision-bound, append-only, local label evidence. Explicit
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

## 2026-09-22 handoff checkpoint

Implemented commit: `fcf6614 feat(sms): enable automatic grounded routing`.

Verified locally:

- `./gradlew.bat :app:compileDebugKotlin --no-daemon`;
- focused `PipelineServiceTest`, frozen-v4 gate, automatic bundle hash, extractor
  partial-field, Room 7-to-8 migration, duplicate-fencing, and atomic rollback
  tests.

Not verified at the `fcf6614` checkpoint: emulator/device runtime, UI navigation,
accessibility, process-death presentation recovery, full unit/lint/build gates.
Its recorded next starting points were `SmsReviewRepository`,
`ReviewDetailScreen`, `GroundedReviewContent`, and `TransactionsScreen`, followed
by live output state.

Review/UI implementation commit:
`f6079ff feat(sms): complete partial evidence review`.

Verified locally for `f6079ff`:

- `./gradlew.bat :data:testDebugUnitTest --tests
  "com.pocketfinancer.data.repository.SmsReviewRepositoryV5Test"
  :app:compileDebugKotlin --no-daemon`;
- `./gradlew.bat :app:testDebugUnitTest --tests
  "com.pocketfinancer.ui.review.ReviewViewModelActionTest" --no-daemon`.

These are JVM/compile results only. Emulator navigation, accessibility, and
interaction behavior remain unverified. At the `f6079ff` checkpoint, the next
recommended starting point was live-output state and cleanup in
`AutomaticSmsProcessingActivity`, `HistoricalSmsProcessingActivity`,
`HomeSyncManager`, `HomeViewModel`, and `SmsTelemetryViewer`.

Live-output implementation commit:
`01f3861 feat(sms): show live structured generation`.

Verified locally for `01f3861`:

- focused `DefaultDirectCandidateSelectorTest`,
  `AutomaticSmsProcessingActivityTest`, `PipelineServiceTest`,
  `HistoricalSmsProcessingActivityTest`, `ManualSmsProcessingObserverTest`,
  `SmsProcessingPresentationTest`, `TrustworthyHomeStateTest`,
  `AutomaticSmsHomePresentationTest`, and
  `HistoricalSmsHomePresentationTest` via the three module
  `testDebugUnitTest` tasks;
- `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon`.

The compiled instrumentation coverage includes separate decoded-delta and
cumulative-output rendering, but it was not executed on an emulator. The complete
Gradle gate subsequently passed; the fresh-emulator matrix remains.
Relevant sources are `AutomaticSmsProcessingActivity`,
`HistoricalSmsProcessingActivity`, `ManualSmsProcessingObserver`,
`HomeSyncManager`, `SmsTelemetryModels`, and `SmsTelemetryViewer`.

## 2026-09-23 verification checkpoint

The legacy migration test fixture was updated to register the already-shipped
Room 7-to-8 migration when reopening its v3-upgraded store; production migration
registration and schema were unchanged. The focused
`./gradlew.bat :data:testDebugUnitTest --tests
"com.pocketfinancer.data.db.AppDatabaseMigrationTest" --no-daemon` passed.
The complete `./gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon`
then passed (356 actionable tasks). This is local JVM/lint/build verification,
not emulator or physical-device verification. The running `Pixel_9` emulator is
currently reported as `offline` by ADB, so instrumentation and end-to-end
runtime evidence remain pending. Next start at emulator connectivity, then run
the targeted instrumented and synthetic-SMS scenarios. Relevant files are
`AppDatabaseMigrationTest`, `AutomaticSmsProcessingActivity`,
`SmsReviewRepository`, `ReviewDetailScreen`, `TransactionsScreen`, and
`SmsTelemetryViewer`.
