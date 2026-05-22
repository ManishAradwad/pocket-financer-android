# Android Emulator Testing Commands Reference

This document compiles the exact commands used to build, install, debug, and interact with the application on the Android emulator from the command line (PowerShell/CMD).

---

## 1. Build the APK
Compiles the project and generates the debug APK using the embedded Java environment in Android Studio:
```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug
```

## 2. Install the APK
Installs/re-installs the compiled APK on the active emulator instance (`emulator-5554`):
```powershell
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Control the App Process
Force-stops and restarts the application by invoking its primary launcher activity:
```powershell
# Force-stop the application
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am force-stop com.pocketfinancer

# Start MainActivity
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -n com.pocketfinancer/com.pocketfinancer.MainActivity
```

## 4. UI Interaction & Navigation
Simulates tap and swipe gestures directly on the emulator.

### A. Dump Screen Hierarchy (To find element coordinates)
To inspect what is currently on the screen and locate clickable bounds:
```powershell
# 1. Dump UI hierarchy XML to emulator storage
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell uiautomator dump /sdcard/window_dump.xml

# 2. Pull the XML file to the local machine to read it
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 pull /sdcard/window_dump.xml .\window_dump.xml
```

### B. Simulate Tap
Simulates a touch at a specific `(x, y)` coordinate:
```powershell
# Tap coordinates for Settings Tab (Center of [896,2277][1009,2319] -> 952, 2298)
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell input tap 952 2298

# Tap coordinates for RUN TEST SMS Button (Center of [423,1931][657,1973] -> 540, 1952)
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell input tap 540 1952
```

### C. Simulate Swipe (Scroll)
Performs a swipe gesture from `(start_x, start_y)` to `(end_x, end_y)` over a duration:
```powershell
# Swipe from bottom-middle upwards to scroll down
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell input swipe 500 1500 500 500
```

---

## 5. Verify App Files (Sandbox Access)
Executes file listing within the application's secure storage using `run-as`:
```powershell
# List the downloaded model file details
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell run-as com.pocketfinancer ls -la files/models/Qwen3-1.7B-Q4_K_M.gguf
```

---

## 6. Monitoring Logs
Dumps the device logs to inspect warnings, runtime errors, or crash traces:
```powershell
# Read current log dump
& "C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 logcat -d
```
