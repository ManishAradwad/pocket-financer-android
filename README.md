# Pocket Financer — Android

Privacy-first, on-device financial tracking for Indian bank SMS.

Pocket Financer reads your transactional SMS messages (bank alerts, UPI payments, credit card swipes) and builds a dashboard of your finances — all processed entirely on-device using a local SLM. Zero data leaves your phone. No cloud servers. No trackers.

## How It Works

```
SMS arrives → BroadcastReceiver → Pipeline
                                    ↓
              Qwen3-1.7B Q8_0 (llama.cpp) — two-phase inference
              Phase 1: reasoning (<think> tokens, 1024 budget)
              Phase 2: GBNF-constrained JSON extraction
                                    ↓
              ExtractionParser → SQLCipher-encrypted Room DB
                                    ↓
              Compose UI → Dashboard, Transactions, Insights
```

## Architecture

```
pocket-financer-android/
├── :inference    llama.cpp JNI bridge + prompt/grammar assets
├── :data         Room DB with SQLCipher AES-256 encryption
├── :sms          SMS inbox reader + BroadcastReceiver
├── :hardware     RAM/GPU/CPU/storage detection
├── :pipeline     SMS → prompt → model → parse → save orchestration
└── :app          Jetpack Compose UI (M3 dark theme)
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| LLM Runtime | llama.cpp (CMake + NDK + JNI) |
| Model | Qwen3-1.7B (Q8_0 GGUF, ~1.8GB) |
| Database | Room + SQLCipher (AES-256) |
| DI | Hilt |
| Build | Gradle KTS, AGP 8.8.2, Kotlin 2.1.20 |
| CI | GitHub Actions (lint → test → APK) |

## Device Requirements

| RAM | Status |
|-----|--------|
| < 3.5 GB | Blocked — device cannot run the model |
| 3.5 – 4.0 GB | Warning — limited headroom, background apps may close |
| > 4.0 GB | Fully supported |

GPU acceleration via Vulkan requires Adreno GPU + i8mm + dotprod CPU features. Falls back to CPU gracefully.

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- JDK 17
- Android SDK 36
- NDK 27.3

### Build

```bash
# Clone
git clone https://github.com/ManishAradwad/pocket-financer-android.git
cd pocket-financer-android

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Release build
./gradlew assembleRelease
```

The GGUF model is downloaded on first launch from HuggingFace. See `:inference/scripts/fetch_model.sh`.

## Project Status

Backend pipeline complete. UI screens (Phase 6) in progress.

## License

MIT — see [LICENSE](LICENSE)

## Acknowledgments

- [llama.cpp](https://github.com/ggerganov/llama.cpp) — on-device LLM inference
- [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) — original React Native app that inspired the SMS pipeline architecture
- The SLM evaluation pipeline at `pF_slm_selection` that identified Qwen3-1.7B Q8_0 as the optimal model
