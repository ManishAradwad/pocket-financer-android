# Plan: Inference API Audit + Comprehensive Tests

**Created:** 2026-05-13 14:45  
**Status:** Planning (no execution yet)  
**Scope:** Two-phase — (1) audit & fix inference pipeline APIs, (2) write unit tests for non-UI modules

---

## Part 1: Inference Pipeline API Audit

### 1.1 Goal

Ensure the current llama.cpp JNI integration uses the latest, safest API practices as of the pinned commit `b7385` (May 2025 era). Several issues were identified during research:

### 1.2 Issues Found & Fixes

| # | Issue | Severity | File | Fix |
|---|-------|----------|------|-----|
| 1 | `llama_backend_init()` called every time `nativeLoadModel` is invoked; `llama_backend_free()` in `nativeUnloadModel`. The official llama.cpp docs state these must be called ONCE at app lifetime start/end, not per model. Calling them repeatedly can leak global state and cause crashes on second load. | **HIGH** | `inference/src/main/cpp/llama_jni.cpp` lines 42, 237 | Move `llama_backend_init()` to JNI `JNI_OnLoad` (one-time init when native lib loads), and `llama_backend_free()` to `JNI_OnUnload`. Remove them from loadModel/unloadModel. |
| 2 | Missing `n_ubatch` in context params. Current llama.cpp recommends setting physical batch size (`n_ubatch`) explicitly alongside logical batch size (`n_batch`). Without it, the engine defaults to `n_batch` which can cause oversized memory allocations on memory-constrained devices like Galaxy A50 (4GB). | **MEDIUM** | `inference/src/main/cpp/llama_jni.cpp` line 72 | Add `ctx_params.n_ubatch = 256;` (physical micro-batch, smaller = less peak memory). |
| 3 | Manual Qwen3 chat template formatting in `PromptBuilder.buildChatPrompt()`. llama.cpp provides `llama_chat_apply_template()` which reads the model's built-in Jinja template. Manual formatting is brittle — if the model uses a different tokenizer version or the template changes upstream, our hardcoded format breaks. | **MEDIUM** | `pipeline/src/main/java/.../PromptBuilder.kt` lines 75-93, `inference/src/main/cpp/llama_jni.cpp` (new JNI function) | Add `nativeApplyChatTemplate(handle, messagesJson)` JNI function that calls `llama_chat_apply_template()`. Update `LlamaEngine.kt` to expose it. Update `PromptBuilder.buildChatPrompt()` to use it (with manual fallback). The model's Jinja template handles `enable_thinking` automatically so we can drop the manual directive. |
| 4 | No performance data capture. llama.cpp exposes `llama_perf_context_data` struct with `t_p_eval_ms` (prompt processing time) and `t_eval_ms` (token generation time). Capturing these would help diagnose slowdowns on the A50. | **LOW** | `inference/src/main/cpp/llama_jni.cpp`, `inference/src/main/java/.../LlamaEngine.kt` | Add `nativeGetPerfData()` JNI function that samples `llama_perf_context` and returns `{t_load_ms, t_p_eval_ms, t_eval_ms, n_tokens}` as a JSON string. Wire into `LlamaEngine.inferForExtraction()` to include timing in `InferenceResult.Success`. Expose in SettingsViewModel test-run output. |
| 5 | `use_mmap` default is `true` in `llama_model_default_params()`. On some Android devices, mmap from internal storage fails silently or causes SIGBUS when reading GGUF files from `filesDir/`. The llama.cpp Android example explicitly sets `use_mmap = false` for this case. | **MEDIUM** | `inference/src/main/cpp/llama_jni.cpp` line 58 | Add `model_params.use_mmap = false;` before loading. |
| 6 | `llama_memory_seq_rm()` called in our KV cache overflow handling, but the call pattern looks potentially incorrect: we remove all sequences, then erase tokens from the beginning of the vector. The sequence 0 is then left in an inconsistent state. | **HIGH** | `inference/src/main/cpp/llama_jni.cpp` lines 130-135 | Refactor KV cache eviction: keep the last `keep` tokens, do a proper `llama_kv_cache_seq_rm` for tokens 0..(n_tokens-keep), then rebuild the token vector from the kept suffix. Actually the correct function is `llama_memory_seq_rm(llama_get_memory(ctx), 0, 0, n_tokens - keep)`. Current code passes `(-1, -1)` which removes ALL sequences. |
| 7 | Thread count is hardcoded to 4. In production, should use `std::thread::hardware_concurrency()` capped to a reasonable max (like 4-6). The A50 has 8 cores (4xA73 + 4xA53) but using all 8 causes thermal throttling and ANRs. | **LOW** | `inference/src/main/cpp/llama_jni.cpp` lines 73-74 | Add a JNI helper to detect optimal thread count. Cap at `min(hardware_concurrency, 4)` for stability on older devices. |

### 1.3 Step-by-Step Execution (Part 1)

0. **Pre-step: Bump llama.cpp from b7385 → b9198** (~5 min)
   - Update `inference/src/main/cpp/CMakeLists.txt` line 11: `GIT_TAG b7385` → `GIT_TAG b9198`
   - This is the latest stable release as of May 17, 2026
   - All our API surface (sampler chain, chat template, perf context, KV cache) is verified compatible
   - **Verify:** Clean build succeeds. `./gradlew :inference:build` passes C++ compilation.

1. **Fix #1: `llama_backend_init` lifecycle** (~15 min)
   - Add `JNI_OnLoad` function in `llama_jni.cpp` that calls `llama_backend_init()` once
   - Add `JNI_OnUnload` function that calls `llama_backend_free()` once
   - Remove `llama_backend_init()` from `nativeLoadModel`
   - Remove `llama_backend_free()` from `nativeUnloadModel`
   - **Verify:** Build succeeds. On device, load → unload → load again should work without crash.

2. **Fix #2: Add `n_ubatch`** (~5 min)
   - Add `ctx_params.n_ubatch = 256;` after line 72
   - **Verify:** Build succeeds.

3. **Fix #5: Disable mmap** (~5 min)
   - Add `model_params.use_mmap = false;` after line 59
   - **Verify:** Model loading still works on device.

4. **Fix #6: KV cache eviction** (~15 min)
   - Review current `llama_memory_seq_rm` call: currently `llama_memory_seq_rm(llama_get_memory(inst->ctx), 0, -1, -1)` which removes all sequences
   - Replace with: `llama_memory_seq_rm(llama_get_memory(inst->ctx), 0, 0, n_tokens - keep)`, then rebuild token vector from `tokens.begin() + to_delete` to end.
   - **Verify:** Test with a prompt that exceeds context size — should not crash.

5. **Fix #7: Dynamic thread count** (~10 min)
   - Add a static helper in the anonymous namespace: `get_optimal_thread_count()` that returns `min(hardware_concurrency, 4)`
   - Use it in `ctx_params.n_threads` and `n_threads_batch`

6. **Fix #3: Chat template via `llama_chat_apply_template()`** (~30 min)

   *Why this matters:* Our hardcoded Qwen3 template (`<|im_start|>system\n...<|im_end|>`) hardcodes the thinking directive in the system message. The model's actual Jinja template handles `enable_thinking` properly, sets correct token IDs, and works across Qwen3 versions. A template mismatch causes the model to output garbled tokens or ignore the thinking phase entirely.

   *Architecture decision:* Chat template application lives at `PipelineService` level, not inside `PromptBuilder`. This keeps `PromptBuilder` a pure string-builder (single responsibility) and testable without the JNI bridge.

   *Implementation steps:*
   - **Step A: Add JNI function `nativeApplyChatTemplate` in `llama_jni.cpp`**
     ```cpp
     extern "C" JNIEXPORT jstring JNICALL
     Java_com_pocketfinancer_inference_LlamaEngine_nativeApplyChatTemplate(
         JNIEnv *env, jclass, jlong handle, jstring jmessages, jboolean add_assistant_prefix)
     ```
     - Parse `jmessages` as JSON array of `[{role, content}]` objects
     - Get the model's chat template with `llama_model_chat_template(model, nullptr)`
     - Call `llama_chat_apply_template(vocab, tmpl, messages_json, add_assistant_prefix, buf, bufsz)`
     - Return rendered string or empty string on failure
   - **Step B: Add Kotlin wrapper in `LlamaEngine.kt`**
     ```kotlin
     data class ChatMessage(val role: String, val content: String)
     
     fun applyChatTemplate(
         messages: List<ChatMessage>, 
         addAssistantPrefix: Boolean = true
     ): String?
     ```
     - Serialize messages to JSON array string
     - Call JNI; return null on empty/failure result
   - **Step C: Add `applyChatTemplate()` helper to `PipelineService`**
     - Private method that takes system prompt + user prompt, wraps in ChatMessages, calls `llamaEngine.applyChatTemplate()`
     - Falls back to `promptBuilder.buildChatPrompt()` if JNI returns null
   - **Step D: Update `PipelineService.processSingle()`**
     - Replace `promptBuilder.buildChatPrompt(rawPrompt, enableThinking = true)` with `applyChatTemplate(rawPrompt)`
   - **Step E: Keep `PromptBuilder.buildChatPrompt()` as fallback**
     - Remove the hardcoded thinking directive from system message (the model's template adds it)
     - Keep the method for backward compatibility and fallback

   *Files changed:* `llama_jni.cpp` (add ~50 lines), `LlamaEngine.kt` (add ~30 lines), `PipelineService.kt` (add ~25 lines, modify ~5 lines), `PromptBuilder.kt` (modify ~5 lines), `PromptBuilderTest.kt` (adjust test expectations)

7. **Fix #4: Performance data capture** (~20 min)

   *Why this matters:* Your A50 has only 4GB RAM and you're running on CPU. Without timing data, you can't tell whether the model is actually generating tokens (slowly) or completely stuck. This is essential for diagnostic work in this session.

   *Implementation steps:*
   - **Step A: Add JNI function `nativeGetPerfData` in `llama_jni.cpp`**
     ```cpp
     extern "C" JNIEXPORT jstring JNICALL
     Java_com_pocketfinancer_inference_LlamaEngine_nativeGetPerfData(
         JNIEnv *env, jclass, jlong handle)
     ```
     - Call `llama_perf_context(ctx)` → returns `llama_perf_context_data` struct
     - Build JSON: `{"t_load_ms":..., "t_p_eval_ms":..., "t_eval_ms":..., "n_tokens":...}`
     - Return as jstring
   - **Step B: Add Kotlin wrapper in `LlamaEngine.kt`**
     - Add `PerformanceData` data class: `(tLoadMs: Long, tPromptEvalMs: Long, tEvalMs: Long, nTokens: Int)`
     - Add `getPerformanceData(): PerformanceData?` method (parse JSON)
   - **Step C: Update `InferenceResult.Success`**
     - Add `perf: PerformanceData?` field (nullable for backward compat)
   - **Step D: Update `LlamaEngine.inferForExtraction()`**
     - After phase 2 completes, call `nativeGetPerfData()` and attach to Success result
   - **Step E: Update `PipelineService.processSingle()`**
     - Include timing in `PipelineStep` when emitting `EXTRACTED` stage
   - **Step F: Update `SettingsViewModel.runTestSms()`**
     - Display perf data in test output (tokens/sec, total time)

   *Files changed:* `llama_jni.cpp` (add ~35 lines), `LlamaEngine.kt` (add ~45 lines), `PipelineService.kt` (modify ~5 lines), `SettingsViewModel.kt` (modify ~5 lines)

---

## Part 2: Test Suite for Non-UI Code

### 2.1 Philosophy

- **Unit tests only** — no UI (Compose) tests, no integration tests requiring a device
- **Test behavior, not implementation** — verify inputs → outputs, not internal state
- **Use constructor injection** — no Hilt in unit tests (per Google's Hilt testing guide)
- **Real Room with in-memory DB** — avoid mocking Room, use `Room.inMemoryDatabaseBuilder` for DAO/repository tests
- **Mock external dependencies** — use MockK for Android framework classes (Context, ContentResolver)
- **Test coroutines properly** — use `runTest` from `kotlinx-coroutines-test`
- **Test flows with Turbine** — for Flow-based APIs (repository methods, PipelineService state)
- **One test class per source file** — mirrors project structure

### 2.2 Modules to Test & What to Test

#### Module: `inference` (JNI-purely, not testable in unit tests)
- **Skipped.** The JNI bridge requires a device. Would need instrumented tests later.

#### Module: `pipeline`

| Test Class | Source Under Test | What It Tests | Why |
|------------|-------------------|---------------|-----|
| `ExtractionParserTest` | `ExtractionParser.kt` | `parse()` for valid JSON, null output, malformed JSON, nonnull filter (null amount/type/account), amount coercion (₹1,500 → 1500.0, "500" → 500.0), type coercion (only "debit"/"credit"), counterparty optionality | The parser is the critical correctness gate. Wrong parsing = wrong transactions saved. |
| `PromptBuilderTest` | `PromptBuilder.kt` | `buildExtractionPrompt()` output contains system prompt, few-shot examples, sender/body, and ends with "Output: ". `buildChatPrompt()` contains ``, `` tokens, thinking directive when enabled | Prompt format determines whether the model produces correct output. Wrong format = model confusion. |
| `PipelineServiceTest` | `PipelineService.kt` | Queue overflow (drops at 200), sequential processing (only one at a time), pipeline states emitted (ENQUEUED→EXTRACTING→EXTRACTED→SAVED for valid SMS, →SKIPPED for null), account resolution, transaction save, error handling when model not loaded | Orchestrator logic is complex — needs verification that queue/state/save flow works correctly. |

#### Module: `data`

| Test Class | Source Under Test | What It Tests | Why |
|------------|-------------------|---------------|-----|
| `TransactionRepositoryTest` | `TransactionRepository.kt` + `TransactionDao.kt` | Insert transaction via repo, verify it appears in `getAll()`, check `getByDateRange()` returns correct subset, verify UUID generation, ensure domain model conversion (Entity → Transaction) is correct | Persistence layer must be correct — lost or wrong transactions are a data integrity bug. |
| `AccountRepositoryTest` | `AccountRepository.kt` + `AccountDao.kt` | `getOrCreate()` creates new account if not found, returns existing if found. `ensureDefault()` creates default account. Label matching | Account auto-resolution is a key pipeline step. |
| `TransactionDaoTest` | `TransactionDao.kt` | Direct DAO: insert, query by account, query by date, conflict replace | Lower-level than repository tests — catches SQL/schema issues. |
| `AccountDaoTest` | `AccountDao.kt` | Direct DAO: insert, query by id, query by label | Same rationale. |

#### Module: `sms` (requires Android Context — tested via Robolectric or fakes)

| Test Class | Source Under Test | What It Tests | Why |
|------------|-------------------|---------------|-----|
| `SmsRepositoryUnitTest` | `SmsRepository.kt` | `hasPermissions()` returns true/false based on mocked Context. `fetchHistory()` delegates correctly to SmsReader with correct `daysBack` → timestamp conversion. | Permission logic is security-critical. |
| `SmsReaderUnitTest` | `SmsReader.kt` | ContentResolver query construction: correct URI, projection, selection args for date range. Cursor parsing (address, body, date, type). Offset/limit handling. Address regex filter. | SMS reading is the data ingestion entry point — bugs here mean missed transactions. |

#### Module: `hardware`

| Test Class | Source Under Test | What It Tests | Why |
|------------|-------------------|---------------|-----|
| `DeviceCapabilitiesUnitTest` | `DeviceCapabilities.kt` | RAM parsing from /proc/meminfo or ActivityManager (via mocked API). Storage check via mocked StatFs. GPU detection via GLES mock. RAM tier classification (BLOCKED <3.5, WARNING 3.5-4). Model size fit check. | Hardware checks gate whether the user can even run the SLM. Wrong = user blocked unfairly or crash on load. |
| `SlmSelectorUnitTest` | `SlmSelector.kt` | With given DeviceInfo, verify correct SlmTier is selected. Qwen3 preferred over Gemma4. Higher quant preferred when RAM allows. When RAM too low, returns null (no viable tier). Explanation string generation. | SLM selection directly impacts which model gets downloaded. Wrong selection = download fails or model too big. |

### 2.3 Test Dependencies to Add

In `gradle/libs.versions.toml`:
```toml
[versions]
mockk = "1.13.13"
turbine = "1.2.0"
kotlinx-coroutines-test = "1.10.1"
robolectric = "4.14.1"

[libraries]
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinx-coroutines-test" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
```

In each module's `build.gradle.kts`, add to `dependencies`:
```kotlin
testImplementation(libs.mockk)
testImplementation(libs.coroutines.test)
testImplementation(libs.turbine)
```

For `:sms` and `:hardware` modules (which need Android framework classes):
```kotlin
testImplementation(libs.robolectric)
```

For `:data` module:
```kotlin
testImplementation(libs.room.testing)  // already declared
```

### 2.4 Test Structure Conventions

- Package: `com.pocketfinancer.<module>` under `src/test/java/`
- Class naming: `<ClassName>Test.kt`
- Method naming: backtick-enclosed descriptive names (Kotlin convention)
  - Example: `` `parse should return null for non-financial SMS` ``
- Arrange-Act-Assert pattern (Given-When-Then where helpful)
- Use `runTest { }` for all coroutine tests
- Use MockK `relaxed = true` only when truly needed
- Use `@Before`/`@After` for setup/teardown

### 2.5 Order of Implementation (Part 2)

Tests should be written in this priority order (most critical first):

1. **`ExtractionParserTest`** — Highest impact, pure logic, no Android deps
2. **`PromptBuilderTest`** — Pure logic, needs mock Context for asset loading
3. **`SlmSelectorUnitTest`** — Pure logic, no Android deps
4. **`DeviceCapabilitiesUnitTest`** — Needs Robolectric or mocked Android APIs
5. **`TransactionDaoTest`** — Room in-memory, data layer foundation
6. **`AccountDaoTest`** — Room in-memory
7. **`TransactionRepositoryTest`** — Depends on DAO tests passing
8. **`AccountRepositoryTest`** — Depends on DAO tests passing
9. **`PipelineServiceTest`** — Complex, needs mock LlamaEngine, PromptBuilder, etc.
10. **`SmsReaderUnitTest`** — Needs Robolectric for ContentResolver
11. **`SmsRepositoryUnitTest`** — Depends on SmsReader mock

### 2.6 Validation

Run all tests with:
```bash
./gradlew test
```

Expected: all tests green, zero failures.

---

## Part 3: Risks, Tradeoffs, Open Questions

### Risks
- **Fix #1 (backend_init lifecycle):** `JNI_OnLoad` runs when the native library is loaded by `System.loadLibrary()`. If the Activity/Fragment lifecycle causes the library to be loaded/unloaded multiple times (unusual but possible), `llama_backend_free()` in `JNI_OnUnload` may race with in-flight inference. Mitigation: add a static `std::atomic<int> refcount` in the JNI bridge.
- **Fix #6 (KV cache eviction):** Changing the memory management logic could introduce crashes if the token vector and memory sequence get out of sync. Test carefully with prompts larger than context size.
- **Fix #3 (chat template via JNI):** The `llama_chat_apply_template()` function expects a specific JSON format (`[{role, content}]`). If the model's GGUF doesn't contain a Jinja template (some fine-tuned GGUFs strip it), the call returns empty. Our manual fallback handles this case. Also, the template may add BOS/EOS tokens that we need to account for in the thinking-phase prefixing.
- **Fix #4 (perf data):** `llama_perf_context()` returns data from the last decode batch. If called before any `llama_decode()`, it returns zero-filled data. We must guard by only calling it after at least one successful decode.

### Open Questions — RESOLVED

1. **llama.cpp commit bump: b7385 → b9198 (latest stable).** Verified: all our API surface (`llama_backend_init`, `llama_model_load_from_file`, `llama_sampler_chain`, `llama_chat_apply_template`, `llama_perf_context`, `llama_memory_seq_rm`, `n_ubatch`, `use_mmap`) is present and signature-compatible in b9198. No breaking changes for our use. **Decision:** Bump to b9198. Update `CMakeLists.txt` line 11 from `GIT_TAG b7385` to `GIT_TAG b9198`. This also means fix #3 (chat template) and fix #4 (perf data) are well-supported.

2. **SMS/hardware testing approach: Robolectric for SmsReader, MockK for DeviceCapabilities.** Robolectric is slower but verifies real ContentResolver URI construction and cursor parsing — critical for data ingestion correctness. DeviceCapabilities uses Android framework APIs (`ActivityManager`, `GLES20`, `StatFs`) that MockK handles well because we only need to verify call patterns, not Android internals. **Decision:** `SmsReaderUnitTest` uses Robolectric. `DeviceCapabilitiesUnitTest` uses pure MockK.

---

## Summary

| Task | Time Estimate | Priority |
|------|--------------|----------|
| Pre-step: bump llama.cpp to b9198 | 5 min | PRE |
| Fix #1: backend_init lifecycle | 15 min | HIGH |
| Fix #2: n_ubatch | 5 min | MEDIUM |
| Fix #5: use_mmap=false | 5 min | MEDIUM |
| Fix #6: KV cache eviction | 15 min | HIGH |
| Fix #7: dynamic threads | 10 min | LOW |
| Fix #3: llama_chat_apply_template | 30 min | MEDIUM |
| Fix #4: perf data capture | 20 min | LOW |
| ExtractionParserTest | 30 min | HIGH |
| PromptBuilderTest | 25 min | HIGH |
| SlmSelectorUnitTest | 15 min | HIGH |
| DeviceCapabilitiesUnitTest | 25 min | MEDIUM |
| TransactionDaoTest + AccountDaoTest | 30 min | HIGH |
| TransactionRepositoryTest + AccountRepositoryTest | 25 min | HIGH |
| PipelineServiceTest | 35 min | MEDIUM |
| SmsReaderUnitTest + SmsRepositoryUnitTest | 25 min | MEDIUM |
| Add test dependencies to build files | 10 min | — |
| **Total** | **~5.5 hours** | |

Fix execution order: Start with the pre-step (b9198 bump) then do all JNI fixes in one pass (fixes #1, #2, #5, #6, #7, #3, #4 — each touches `llama_jni.cpp` but the edits are independent). The CMakeLists.txt bump triggers a full native rebuild (~10-15 min), which is why it goes first. Fix #3 (chat template) is the most complex — it adds a new JNI function and touches Kotlin code across 4 files. Fix #4 (perf data) also adds a JNI function but is simpler.

Tests and API fixes are independent streams — tests don't need the C++ changes to compile.
