# Pocket Financer - Android Development instructions

This document serves as the onboarding and quickstart instruction file for any AI agent or copilot working on the **Pocket Financer Android** project. It outlines the core architecture, dataflow, testing/emulation commands, design systems, and current status to enable instant, high-context development.

---

## 🌟 Core System Architecture & Modules

Pocket Financer is a secure, privacy-first, on-device financial tracking and analytics application designed for Indian bank and card SMS notifications. It reads transactional alerts and populates a local dashboard using a local **Small Language Model (SLM)** backed by `llama.cpp` via a native JNI bridge.

The project is structured into six decoupled Gradle modules:

```
pocket-financer-android/
├── :app          # Jetpack Compose UI (Material 3 Dark Theme) + Hilt DI Root
├── :pipeline     # SMS → SLM → Database coordinator, Prompt building, & parser
├── :inference    # llama.cpp JNI Engine, NDK compiled Native C++ library, HF Downloader
├── :data         # Encrypted Database (Room + SQLCipher integration, DAOs, Entities)
├── :sms          # Real-time BroadcastReceiver + Inbox ContentProvider Scraper
└── :hardware     # Device Capability Profiler (validates RAM, CPU Neon, i8mm, & dotprod)
```

### Module Responsibilities & Key Files

1. **[`:app`](file:///d:/Personal_Projects/pocket-financer-android/app)**
   - **Main UI & Themes**: Custom M3 dark theme defined in [`ui/theme/Color.kt`](file:///d:/Personal_Projects/pocket-financer-android/app/src/main/java/com/pocketfinancer/ui/theme/Color.kt) and [`ui/theme/Theme.kt`](file:///d:/Personal_Projects/pocket-financer-android/app/src/main/java/com/pocketfinancer/ui/theme/Theme.kt).
   - **Navigation**: Structured using Compose Navigation in [`ui/navigation/Screen.kt`](file:///d:/Personal_Projects/pocket-financer-android/app/src/main/java/com/pocketfinancer/ui/navigation/Screen.kt) and [`ui/PocketFinancerRoot.kt`](file:///d:/Personal_Projects/pocket-financer-android/app/src/main/java/com/pocketfinancer/ui/PocketFinancerRoot.kt).
   - **Settings Screen**: Present in [`ui/settings/SettingsScreen.kt`](file:///d:/Personal_Projects/pocket-financer-android/app/src/main/java/com/pocketfinancer/ui/settings/SettingsScreen.kt) (manages hardware specs, downloads models, loads/unloads, and triggers test SMS).

2. **[`:pipeline`](file:///d:/Personal_Projects/pocket-financer-android/pipeline)**
   - **[`PipelineService.kt`](file:///d:/Personal_Projects/pocket-financer-android/pipeline/src/main/java/com/pocketfinancer/pipeline/PipelineService.kt)**: Coordinates SMS parsing flows, builds prompt, runs JNI inference, parses JSON output, and persists in database.
   - **[`SmsFilterPipeline.kt`](file:///d:/Personal_Projects/pocket-financer-android/pipeline/src/main/java/com/pocketfinancer/pipeline/SmsFilterPipeline.kt)**: 6-stage deterministic regex-based filter running in `< 1ms` to filter out personal numbers, marketing/OTP alerts, and non-transactional messages.
   - **[`PromptBuilder.kt`](file:///d:/Personal_Projects/pocket-financer-android/pipeline/src/main/java/com/pocketfinancer/pipeline/PromptBuilder.kt)**: Assembles the prompt, merging the system prompt and few-shot examples.
   - **[`ExtractionParser.kt`](file:///d:/Personal_Projects/pocket-financer-android/pipeline/src/main/java/com/pocketfinancer/pipeline/ExtractionParser.kt)**: Parses extracted grammar-constrained JSON string.

3. **[`:inference`](file:///d:/Personal_Projects/pocket-financer-android/inference)**
   - **[`LlamaEngine.kt`](file:///d:/Personal_Projects/pocket-financer-android/inference/src/main/java/com/pocketfinancer/inference/LlamaEngine.kt)**: Native JNI bridge wrapper containing loading, pre-fill cache, thread count config, and two-phase reasoning.
   - **GBNF Grammar**: Strict grammar schema defined in [`assets/sms_extraction.gbnf`](file:///d:/Personal_Projects/pocket-financer-android/inference/src/main/assets/sms_extraction.gbnf) enforcing strict JSON output.

4. **[`:data`](file:///d:/Personal_Projects/pocket-financer-android/data)**
   - **Room DB & SQLCipher**: Uses SQLCipher for AES-256 database encryption.
   - **Passphrase generation**: Derived via `getOrCreatePassphrase` inside [`db/AppDatabase.kt`](file:///d:/Personal_Projects/pocket-financer-android/data/src/main/java/com/pocketfinancer/data/db/AppDatabase.kt).

5. **[`:sms`](file:///d:/Personal_Projects/pocket-financer-android/sms)**
   - **Real-time broadcast**: [`SmsReceiver.kt`](file:///d:/Personal_Projects/pocket-financer-android/sms/src/main/java/com/pocketfinancer/sms/SmsReceiver.kt) catches incoming transaction alerts and emits them via Coroutine flow channels.
   - **ContentProvider scraper**: [`SmsReader.kt`](file:///d:/Personal_Projects/pocket-financer-android/sms/src/main/java/com/pocketfinancer/sms/SmsReader.kt) reads historical messages from the device inbox.

6. **[`:hardware`](file:///d:/Personal_Projects/pocket-financer-android/hardware)**
   - **Auto-Tuning Profiler**: [`SlmSelector.kt`](file:///d:/Personal_Projects/pocket-financer-android/hardware/src/main/java/com/pocketfinancer/hardware/SlmSelector.kt) profiles device RAM and CPU flags (e.g. ARM i8mm and dotprod) to download and map the optimal SLM size (Gemma 4 vs Qwen 3 vs fallback Qwen 3 0.6B).

---

## ⚡ The Extraction Pipeline (Two-Phase Reasoning)

Extracting structured transaction schema requires a power-saving and robust pipeline:

1. **Deterministic Filter (Phase 0)**: Uses regexes to assert details like currency, amounts, masked cards/accounts, action verbs, and excludes OTPs/collect requests. If failed, it stops immediately.
2. **Thinking Pass (Phase 1)**: Assembles prompt, appends `<think>`, and lets the model reason step-by-step. The native JNI engine generates tokens with a stop token set to `</think>`.
3. **Constrained Generation (Phase 2)**: Appends `</think>\n` and executes native completion with the GBNF grammar active. This forces the model's logits sampler to output only valid JSON matching the transaction schema (e.g., `amount`, `counterparty`, `type`, `account`).

---

## ⚙️ Caching & Memory Profiling

- **Prefix Caching**: Prefills the static system prompt + few-shot context once, saving the JNI engine KV Cache as `session_<sha256>.bin` on disk. Loads this state in `< 100ms` on subsequent runs.
- **Cache Cleanups**: Deletes stale session caches when the system prompt or examples change.
- **Thread Tuning**: Configures threads by passing `0` (delegating to llama.cpp's scheduler, which chooses performance cores without thermal build-up).
- **Arm KleidiAI Acceleration**: NDK builds leverage ARMv8.2-A integer math instructions (`i8mm` and `dotprod`) via KleidiAI to speed up matrix calculations.

---

## 🛠️ Testing, Build, & Emulation Commands

Use the following commands from a Windows PowerShell terminal to build, debug, and test the project:

### 1. Build & Test Commands
Compile code and run local JUnit tests (which mock and assert DB repositories, filters, prompts, and selection logic):
```powershell
# Run unit tests on all core modules (excludes heavy native builds)
.\gradlew.bat :data:test :pipeline:test :hardware:test :sms:test :inference:test -x :inference:build -x :app:build

# Build the Debug APK
& "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug
```

### 2. APK Deployment & ADB Control
Deploy the debug APK and control the process on the active Android Emulator (`emulator-5554`):
```powershell
# Install the compiled APK
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# Force-stop the app process
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am force-stop com.pocketfinancer

# Start the application's MainActivity launcher
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -n com.pocketfinancer/com.pocketfinancer.MainActivity
```

### 3. UI Interactions & Element Coordinates
Inspect the current screen layout or simulate gestures:
```powershell
# Dump the UI layout tree to locate clickable bounds
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell uiautomator dump /sdcard/window_dump.xml
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 pull /sdcard/window_dump.xml .\window_dump.xml

# Simulate Tap at specific coordinates (e.g. Settings Tab or RUN TEST SMS Button)
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell input tap <x_coord> <y_coord>

# Simulate Swipe (Scroll up from bottom center)
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell input swipe 500 1500 500 500
```

### 4. Application Sandbox Inspection & Logs
Check model storage paths and read device warning or crash logs:
```powershell
# List contents in the app secure storage (requires run-as)
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell run-as com.pocketfinancer ls -la files/models/

# Monitor current runtime logs (filtered/cleared)
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 logcat -d
```

---

## 📈 Roadmap & Next Tasks (Where to start)

1. **Dashboard, Transaction Register, and Device Profiling Screens (Current Step)**:
   - Build out the primary tabs (`Home`, `Insights`) inside [`ui/PocketFinancerRoot.kt`](file:///d:/Personal_Projects/pocket-financer-android/app/src/main/java/com/pocketfinancer/ui/PocketFinancerRoot.kt) (the `Transactions` tab is now fully implemented with date-grouped lists, daily subtotals, credit/debit filters, and an SLM details bottom sheet).
   - Ensure you use Material 3 dark components that fit the defined color tokens.
2. **Background Worker integration (Next Step)**:
   - Integrate an Android WorkManager flow that triggers in the background when SMS is received to parse messages offline without requiring the app to be open.
