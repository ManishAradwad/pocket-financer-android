# Device-Agnostic Audit Plan

**Date:** 2026-05-18
**Context:** Samsung Galaxy A50 (Exynos 9610, 4GB RAM, Mali-G72 GPU) is the test device.
Goal: identify any code that assumes or biases toward specific device hardware/vendors.

---

## Summary

One **critical** device-specific bias found. Two **moderate** limitations. Everything else is
capability-based and device-agnostic.

---

## Finding 1 (CRITICAL): GPU acceleration gatekept to Adreno-only

**File:** `hardware/src/main/java/com/pocketfinancer/hardware/DeviceCapabilities.kt`
**Lines:** 212–215

```kotlin
fun isGpuAccelerationSupported(): Boolean {
    val gpu = getGpuInfo() ?: return false
    val cpu = getCpuInfo() ?: return false
    return gpu.hasAdreno && cpu.hasI8mm && cpu.hasDotProd
}
```

**What this means:** Only Qualcomm Snapdragon devices with Adreno GPUs + ARM i8mm + dotprod
CPU features are classified as "GPU acceleration supported." All other GPU vendors are excluded:

| GPU Vendor | Found In | Gets GPU Accel? |
|---|---|---|
| Adreno (Qualcomm) | Snapdragon phones | ✅ (if i8mm + dotprod) |
| Mali (ARM) | Exynos, Tensor, Dimensity, Kirin | ❌ |
| PowerVR (Imagination) | Some MediaTek, Unisoc | ❌ |
| Others | — | ❌ |

**Impact:** This flag directly controls which SLM tier is selected in
`hardware/src/main/java/com/pocketfinancer/hardware/SlmSelector.kt`:

```kotlin
// Tier 1: Qwen3-1.7B Q8_0 — requires ram >= 4.0f AND gpuAccel
ram >= 4.0f && gpuAccel -> SlmTier.QWEN3_1_7B_Q8_0

// Tier 3: Gemma4 E2B Q8_0 — requires ram >= 6.0f AND gpuAccel
ram >= 6.0f && gpuAccel -> SlmTier.GEMMA4_E2B_Q8_0
```

**Consequences:**

- **Galaxy A50** (Exynos, Mali, 4GB): would select Qwen3-1.7B Q4_K_M (tier 2, 4-bit quant)
  even though it has enough RAM for Q8_0. The device is permanently blocked from the best
  quality tier because its GPU isn't Adreno.

- **High-end Pixel 9 Pro** (Tensor G4, Mali, 16GB): would NOT get Qwen3-1.7B Q8_0 or
  Gemma4 E2B Q8_0 despite having 4x the RAM needed. Same as the Galaxy A50 on SLM
  selection — a 16GB flagship gets the same model tier as a 4GB mid-range.

- **Samsung Galaxy S24 Ultra** (Snapdragon variant): gets Q8_0 tier. Same device in
  Exynos markets: doesn't.

**But wait — is it actually using GPU?** No. The CMakeLists.txt builds llama.cpp with
ALL GPU backends disabled (`GGML_VULKAN=OFF`, `GGML_OPENCL=OFF`, etc.). In
`LlamaEngine.loadModel()`, default `gpuLayers = 0`. So `isGpuAccelerationSupported()`
is a capability heuristic, NOT an actual GPU compute check. The flag means
"this device *could* support GPU offloading in the future," not "we are using GPU now."

The real correlation: Qualcomm tends to ship Adreno + ARM v8.6+ (i8mm, dotprod) together
on premium SoCs. But this correlation doesn't hold universally — many non-Qualcomm SoCs
also have i8mm and dotprod (e.g., Google Tensor G3/G4, some Exynos chips). The code
requires *both* Adreno *and* the CPU features, so even a non-Qualcomm SoC with all the
right CPU features is still excluded by the GPU vendor check.

**Separate concern in same function — `assessDevice()` reimplements the same logic inline:**

```kotlin
val gpuAccel = gpu != null && cpu != null && gpu.hasAdreno && cpu.hasI8mm && cpu.hasDotProd
```

This means there are two copies of the same Adreno-gating logic that must stay in sync.

---

## Finding 2 (MODERATE): ABI filters exclude 32-bit devices

**Files:**
- `inference/build.gradle.kts` line 18: `abiFilters += listOf("arm64-v8a", "x86_64")`
- `app/build.gradle.kts` line 23: `abiFilters += listOf("arm64-v8a", "x86_64")`

**Impact:** Devices with 32-bit ARM CPUs (armeabi-v7a) cannot install the app. This
excludes:
- Very old Android phones (pre-2015)
- Some Android Go devices
- Some budget tablets and TV boxes

Galaxy A50 is arm64-v8a so this doesn't affect it. This is a **deliberate scope limitation**
for an LLM-inference app (32-bit devices don't have enough RAM for model inference anyway),
so likely a reasonable trade-off. But worth documenting as it narrows the device pool.

---

## Finding 3 (LOW): Native thread count capped at 4

**File:** `inference/src/main/cpp/llama_jni.cpp`, line 38–41

```cpp
static int get_optimal_thread_count() {
    int cores = (int)std::thread::hardware_concurrency();
    return std::max(1, std::min(cores, 4));
}
```

**Impact:** On 8+ core devices (flagships), inference uses only 4 threads, leaving
performance on the table. On 4-core devices (Galaxy A50 is an octa-core: 4×A73 + 4×A53),
this is appropriate. The comment says "capped at 4 for thermal safety" — this is a
valid concern for sustained inference on passively cooled phones.

This is a **thermal trade-off**, not a Galaxy A50-specific bias. All devices get the same
4-thread cap.

---

## What is device-agnostic (no changes needed)

| Area | Why it's fine |
|---|---|
| RAM gates (3.5GB block, 4.0GB warn) | Based on model size, not any device's RAM |
| Storage check (`StatFs` + `canFit()`) | Works on any Android device |
| CPU feature detection (i8mm, dotprod, fp16) | Parses `/proc/cpuinfo` — universal |
| GPU detection (Adreno/Mali/PowerVR) | Uses EGL/OpenGL — universal, returns all types |
| SMS reader (`content://sms/inbox`) | Standard Android ContentProvider |
| SMS receiver (`SMS_RECEIVED_ACTION`) | Standard broadcast |
| UI (Compose + Material3) | fillMaxSize, weight, scroll — responsive |
| JNI bridge | Dynamic thread detection, no #ifdef ARCH |
| CMake | No vendor-specific build flags |
| Java/Kotlin | No `Build.MODEL` or `Build.MANUFACTURER` checks |
| minSdk 26 | Covers 95%+ of active Android devices |
| Permissions (READ_SMS, RECEIVE_SMS, INTERNET) | Standard Android permissions |
| Theme | Dark-only Material3, no device-specific theming |

---

## Proposed Changes

### Phase 1: Fix GPU acceleration gate

**Goal:** Make GPU acceleration detection vendor-agnostic. Any device with the right CPU
features (i8mm + dotprod) should be considered "capable," regardless of GPU vendor.

**Files to change:**

1. `hardware/src/main/java/com/pocketfinancer/hardware/DeviceCapabilities.kt`
   - Modify `isGpuAccelerationSupported()` to remove the `gpu.hasAdreno` requirement
   - Keep the CPU feature checks (i8mm + dotprod are the real performance predictors)
   - Fix the duplicate logic in `assessDevice()` to call `isGpuAccelerationSupported()`
     instead of reimplementing

   **Before:**
   ```kotlin
   fun isGpuAccelerationSupported(): Boolean {
       val gpu = getGpuInfo() ?: return false
       val cpu = getCpuInfo() ?: return false
       return gpu.hasAdreno && cpu.hasI8mm && cpu.hasDotProd
   }
   ```

   **After (Option A — pure CPU-feature-based):**
   ```kotlin
   fun isGpuAccelerationSupported(): Boolean {
       val cpu = getCpuInfo() ?: return false
       return cpu.hasI8mm && cpu.hasDotProd
   }
   ```

   **After (Option B — rename + any GPU, plus CPU features):**
   ```kotlin
   /** Returns true if the device has hardware features needed for larger model tiers. */
   fun isHighPerformanceDevice(): Boolean {
       val gpu = getGpuInfo() ?: return false
       val cpu = getCpuInfo() ?: return false
       // Any modern GPU + key CPU features
       return (gpu.hasAdreno || gpu.hasMali || gpu.hasPowerVR) &&
              cpu.hasI8mm && cpu.hasDotProd
   }
   ```

   Option B is safer — it still requires a detectable GPU (avoiding emulators/VMs that
   don't report a GPU), but removes the Qualcomm-only bias. Option A is simpler but
   risks flagging headless/test environments.

   Also fix `assessDevice()`:
   ```kotlin
   // Before:
   val gpuAccel = gpu != null && cpu != null && gpu.hasAdreno && cpu.hasI8mm && cpu.hasDotProd
   // After:
   val gpuAccel = isHighPerformanceDevice()  // or isGpuAccelerationSupported()
   ```

2. `hardware/src/main/java/com/pocketfinancer/hardware/SlmSelector.kt`
   - Update comments/doc to reflect the broader criteria
   - No code changes needed — it just reads `device.isGpuAccelerationSupported`

3. `hardware/src/test/java/com/pocketfinancer/hardware/SlmSelectorUnitTest.kt`
   - The test fixture `deviceWith()` at line 37 hardcodes Mali GPU as the non-accelerated
     case: `gpuType = "Mali (ARM)"`
   - After the fix, many tests will need updating since Mali devices with i8mm+dotprod
     will now be classified as accelerated
   - Need new test cases: "Mali + i8mm + dotprod → accelerated", "Adreno without
     i8mm → not accelerated"

4. `hardware/src/test/java/com/pocketfinancer/hardware/DeviceCapabilitiesUnitTest.kt`
   - Verify this file exists and add test coverage for the new GPU-agnostic logic

### Phase 2 (optional): Consider raising thread cap for 8+ core devices

**Goal:** Allow inference to use more threads on high-core-count devices while keeping
thermal safety on mid-range.

**File:** `inference/src/main/cpp/llama_jni.cpp`

**Approach:** Could use a simple heuristic: if cores >= 8, use cores/2 (up to 6).
This would keep Galaxy A50 at 4 threads (octa-core → 4) and let 12-core flagships
use 6 threads. Low priority — current cap is conservative but not broken.

### Phase 3 (documentation): Decide on 32-bit support

**Goal:** Explicitly decide whether armeabi-v7a exclusion is permanent or should be
reconsidered.

**Files:** `inference/build.gradle.kts`, `app/build.gradle.kts`

No code changes needed — just document the decision. The RAM requirements (2.5GB
minimum for the lightest model) effectively exclude 32-bit devices anyway (they
typically have ≤2GB).

---

## Test Plan

1. Run existing hardware tests: `./gradlew :hardware:test`
   - Expect: `SlmSelectorUnitTest` will have failures after Phase 1 — need to update
     test fixtures to reflect the new GPU-agnostic logic

2. Add new test cases:
   - `Mali GPU + i8mm + dotprod → isGpuAccelerationSupported() = true`
   - `Adreno GPU + no i8mm → isGpuAccelerationSupported() = false`
   - `PowerVR GPU + i8mm + dotprod → isGpuAccelerationSupported() = true`
   - `No GPU detected → isGpuAccelerationSupported() = false`
   - `Galaxy A50 simulation (Mali, 4GB, no i8mm?) → Qwen3 Q4_K_M selected`
   - `Pixel 9 simulation (Mali, 16GB, i8mm+dotprod) → Qwen3 Q8_0 selected`
   - `Snapshot 680 (Adreno 610, 6GB, no i8mm?) → Qwen3 Q4_K_M selected`

3. Full CI: `./gradlew :hardware:test :data:test :pipeline:test :sms:test --no-daemon`

---

## Risks / Open Questions

1. **Does Mali actually support GPU offloading in llama.cpp?**
   If llama.cpp Vulkan/OpenCL genuinely doesn't work on Mali (driver bugs, missing
   extensions), then `isGpuAccelerationSupported()` returning `true` for Mali devices
   is misleading even post-fix. The name should change to something that doesn't promise
   GPU acceleration (e.g., `isHighPerformanceDevice`, `canHandleLargeModels`).

2. **What CPU features does Galaxy A50 actually have?**
   Exynos 9610 uses Cortex-A73 + Cortex-A53 cores. Cortex-A73 does NOT have i8mm
   or dotprod (those came with Cortex-A75/A55 and later). So Galaxy A50 would already
   fail the i8mm+dotprod check regardless of the Adreno gate. The GPU check is
   redundant for this specific device.

3. **Should the cap be raised from 4 threads on 8+ core devices?**
   Trade-off between inference speed and thermal throttling. Could be a user setting
   rather than hardcoded.

4. **Does the Gemini Nano / Android AICore path add more device-specific assumptions?**
   Not yet in the codebase, but worth noting that the Android AICore API has its own
   device compatibility matrix (Tensor G3+, Snapdragon 8 Gen 3+, etc.).
