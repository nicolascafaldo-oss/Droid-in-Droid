# KestrelVM — Local Build, Native Toolchain & Physical Device Gameplan

This guide walks you through running **KestrelVM** on your physical Android phone, compiling the native **Rust (`libkestrel_sandbox.so`)** and **C++17 (`libkestrel_hal.so`)** engines on your local machine, and safely syncing changes back through Git to AI Studio without breaking cloud builds.

---

## 0. Current Architecture Snapshot

| Layer | Path | Current State |
| :--- | :--- | :--- |
| **Host App (Kotlin / Compose)** | `app/src/main/java/com/example/` | **Ready.** Launch-to-Choose/Load VM flow, full-screen OEM launcher boot, PiP floating mode, two-way APK/file import-export, live CPU/RAM/Battery/`EGL14`/`GLES20`/IMU telemetry, and **Hardware** diagnostic console. |
| **Rust Sandbox Engine** | `native/kestrel_sandbox_rust/` | **Source ready (`kestrel_sandbox` v0.1.0).** Needs `cargo-ndk` build on your machine to emit `libkestrel_sandbox.so`. Until `.so` files are packaged, `NativeEngineBridge` runs its Kotlin userspace fallback cleanly. |
| **C++17 Virtual HAL** | `native/kestrel_hal_cpp/` | **Source ready (`kestrel_hal` v0.1.0).** Needs Android NDK + CMake `3.22.1+` build on your machine to emit `libkestrel_hal.so` linked against `EGL`, `GLESv2`, `android`, and `log`. |

---

## 1. Local Machine Prerequisites

Install the following tools on your workstation (Linux, macOS, or Windows/WSL2):

1. **Java Development Kit (JDK) 17 or 21**
   - Required by Android Gradle Plugin `9.1.1` and Gradle `9.3.1` (`sourceCompatibility` / `targetCompatibility` is Java 11).
   - Verify: `java -version`
2. **Android SDK & Platform Tools (`adb`)**
   - Via Android Studio **SDK Manager** (or `sdkmanager`):
     - **Android SDK Platform 36** (`compileSdk` 36 minor 1 / `targetSdk` 36)
     - **Android SDK Build-Tools 36.x**
     - **Android SDK Platform-Tools** (`adb`)
     - **NDK (Side by side)** — recommend **r27** (e.g., `27.2.12479018`) or r26+
     - **CMake** `3.22.1` or newer
3. **Rust Toolchain + Android Targets**
   - Install Rust via [rustup](https://rustup.rs/), then add `cargo-ndk` and Android targets:
     ```bash
     rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
     cargo install cargo-ndk
     ```
4. **Physical Android Phone (Android 7.0 / API 24+, ARM64 recommended)**
   - Go to **Settings -> About phone** and tap **Build number** 7 times to enable **Developer options**.
   - In **Settings -> System -> Developer options**, enable **USB debugging**.
   - Connect via USB and accept the **"Allow USB debugging?"** RSA fingerprint prompt on the phone.
   - Verify connection:
     ```bash
     adb devices
     ```
     *(You should see your device serial followed by `device`, not `unauthorized`.)*

---

## 2. One-Time Repo Setup (After `git clone`)

### A. Create `debug.keystore` at Project Root
`app/build.gradle.kts` signs `debug` builds using `${rootDir}/debug.keystore` with alias `androiddebugkey` and password `android` (kept out of Git via `.gitignore`).

Generate it once in the repository root:
```bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```
*(Or copy your existing `~/.android/debug.keystore` into the repo root as `./debug.keystore`.)*

### B. Configure Environment / SDK Path
- If you do **not** open the project in Android Studio first, create an untracked `local.properties` (already in `.gitignore`) or export `ANDROID_HOME` / `ANDROID_SDK_ROOT`:
  ```bash
  export ANDROID_HOME="$HOME/Android/Sdk"        # macOS: "$HOME/Library/Android/sdk"
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
  ```
- Optional: copy `.env.example` to `.env` (already in `.gitignore`):
  ```bash
  cp .env.example .env
  ```

---

## 3. Phase 1 — Quick Verification on Your Physical Phone (Kotlin Mode)

Run the JVM test suite and install the current app directly onto your phone before touching native toolchains:

```bash
./gradlew :app:testDebugUnitTest
./gradlew installDebug
adb shell am start -n com.example/.MainActivity
```
*(On Windows PowerShell/CMD, use `.\gradlew.bat`.)*

### On-Device Checklist
- **Boot & Fullscreen Flow:** App opens directly to **Choose / Load VM** -> tap **Boot & Load** -> guest launches immediately in immersive fullscreen OEM Android launcher (`Factory Unlocked` & `Root` enabled by default).
- **Hardware Telemetry (`Hardware` tab):** Confirm real device readings:
  - GPU Renderer / Vendor / GLES version (from live `EGL14` + `GLES20`)
  - CPU core count, live frequencies (`/sys/devices/system/cpu/...`), RAM, Battery temp
  - Live Accelerometer + Gyroscope `SensorManager` feed when tilting the phone
- **Picture-in-Picture & Controls:**
  - Tap the **Viewport** pill in the top-right corner of the fullscreen VM (or tap Back) to return to the compact `42.dp` hypervisor bar + Viewport / Files / Hardware tabs.
  - Note on `Vol Up + Vol Down + Power`: Android SystemUI on many unrooted stock ROMs intercepts `KEYCODE_POWER` before apps see it. Use the top-right **Viewport** pill on touchscreens, or test the 3-key chord with a physical/external keyboard or custom-ROM key passthrough.
- **Two-Way File Transfer (`Files` tab):** Import an `.apk` or media file via Android Photo Picker / Document Picker and verify it appears inside the guest `/sdcard/Download` or installs to `/data/app`.

---

## 4. Phase 2 — Compile Native Rust & C++17 Libraries Locally

To switch `NativeEngineBridge` from **Kotlin Userspace Fallback** to the real compiled `.so` shared libraries without modifying `app/build.gradle.kts` in ways that would break AI Studio cloud builds, build both libraries directly into `app/src/main/jniLibs/<abi>/`.

### Step 1: Set NDK Path
Find your installed NDK folder under `$ANDROID_HOME/ndk/` and export it:
```bash
export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"
echo "Using NDK: $ANDROID_NDK_HOME"
```

### Step 2: Build the Rust Sandbox Engine (`libkestrel_sandbox.so`)
From the repository root:
```bash
cargo ndk \
  --manifest-path native/kestrel_sandbox_rust/Cargo.toml \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -p 24 \
  -o app/src/main/jniLibs \
  build --release
```
Expected outputs:
- `app/src/main/jniLibs/arm64-v8a/libkestrel_sandbox.so`
- `app/src/main/jniLibs/armeabi-v7a/libkestrel_sandbox.so`
- `app/src/main/jniLibs/x86_64/libkestrel_sandbox.so`

### Step 3: Build the C++17 Virtual HAL (`libkestrel_hal.so`)
Build each ABI with the NDK CMake toolchain (no edits to `app/build.gradle.kts` needed):
```bash
CMAKE_BIN="$(ls -d "$ANDROID_HOME"/cmake/*/bin/cmake | sort -V | tail -n 1)"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

for ABI in arm64-v8a armeabi-v7a x86_64; do
  BUILD_DIR=".cxx/kestrel_hal/$ABI"
  "$CMAKE_BIN" -S native/kestrel_hal_cpp -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release
  "$CMAKE_BIN" --build "$BUILD_DIR" --config Release
  mkdir -p "app/src/main/jniLibs/$ABI"
  cp "$BUILD_DIR/libkestrel_hal.so" "app/src/main/jniLibs/$ABI/libkestrel_hal.so"
done
```

### Step 4: Rebuild APK & Verify Native JNI Load on Phone
```bash
./gradlew installDebug
adb logcat -s KestrelVirtualHAL:* AndroidRuntime:E
```
Open **KestrelVM -> Boot a VM -> Exit to Viewport -> Hardware tab** and check **JNI Bridge**:
- **Before native build:** `Kotlin Userspace Fallback (Rust JNI ready) | Kotlin HAL Bridge + GLES20 Surface (C++ JNI ready)`
- **After native build (Success):** `kestrel_sandbox-rs/0.1.0 (seccomp-bpf + binder-proxy) | kestrel_hal-cpp17/0.1.0 (hwcomposer + gralloc + sensors)`

---

## 5. Git Push & AI Studio Round-Trip Rules

Before pushing back to Git and pulling into AI Studio, verify these rules so the cloud build remains green:

1. **Do NOT add an unconditional `externalNativeBuild` block to `app/build.gradle.kts`.**
   AI Studio's build container compiles Kotlin/Gradle without local NDK/Rust toolchains. Keep CMake/Rust invoked locally via Step 4 above (or guarded behind a Gradle property check that defaults to `false`).
2. **Do NOT remove or modify `signingConfigs` (`release` and `debugConfig`) in `app/build.gradle.kts`.**
3. **Never commit secrets or machine-local paths:**
   `.gitignore` already excludes `local.properties`, `.env`, `debug.keystore`, `.cxx`, and `build/`. Keep them untracked.
4. **Deciding whether to commit `app/src/main/jniLibs/**/*.so`:**
   - **Recommended when pulling back to AI Studio:** Once you compile & test `arm64-v8a` / `armeabi-v7a` / `x86_64` `.so` binaries locally, committing `app/src/main/jniLibs/` lets AI Studio package your prebuilt `.so` libraries directly inside future cloud APKs!
   - *(Keep `.so` commits stripped/release so repo size stays small; each `.so` here is typically < 500 KB.)*

---

## 6. Next Engineering Milestones (True Out-of-Process AOSP Guest Execution)

Once Phase 1 & Phase 2 are verified on your physical phone, the next stage of **KestrelVM** is replacing the host-rendered AOSP shell with true out-of-process guest Linux/Android execution:

1. **Rust Syscall & Seccomp Supervisor (`native/kestrel_sandbox_rust/src/lib.rs`):**
   - Fork child `init` process inside `filesDir/kestrel_vms/<id>/rootfs`.
   - Attach `nix::sys::ptrace` + `SECCOMP_RET_TRACE` / `PR_SET_NO_NEW_PRIVS` to rewrite guest `openat`, `faccessat`, `execve`, and `bind`/`connect` paths without requiring host root.
   - Multiplex guest `/dev/binder` `ioctl(BINDER_WRITE_READ)` parcels in userspace so guest `servicemanager` and `system_server` stay isolated from the host OS.
2. **C++ Surface & Audio Hal Bridge (`native/kestrel_hal_cpp/kestrel_virtual_hal.cpp`):**
   - Expose `nativeAttachSurface(vmId, Surface)` -> `ANativeWindow_fromSurface()` so the guest `hwcomposer`/`gralloc` shared-memory framebuffer blits directly into the Kotlin `SurfaceView` / Picture-in-Picture window at hardware refresh rate.
   - Add `AAudio` low-latency ring buffer + virtual V4L2/Camera2 frame producer over Unix domain sockets.
3. **Guest AOSP Rootfs Provisioning:**
   - Download or sideload stripped, virtualized AOSP `system` rootfs images (`Android 8.1`, `11`, `13`, `14`) with `ro.boot.flash.locked=0` and `/system/xbin/su` into `filesDir/kestrel_vms/` on demand rather than bundling multi-hundred-MB ROMs inside the base APK.

---

## 7. Troubleshooting Quick Reference

| Issue | Fix |
| :--- | :--- |
| `Execution failed for task ':app:validateSigningDebug' ... debug.keystore not found` | Run the `keytool -genkeypair` command from **Section 2A** at the project root. |
| `SDK location not found` | Export `ANDROID_HOME` or add `sdk.dir=/path/to/Android/Sdk` to an untracked `local.properties`. |
| `cargo-ndk: error: Could not find NDK` | Export `ANDROID_NDK_HOME` pointing to `$ANDROID_HOME/ndk/<version>`. |
| `UnsatisfiedLinkError` or fallback banner still shown in **Hardware** tab | Verify `adb shell getprop ro.product.cpu.abi` (usually `arm64-v8a`) matches a folder under `app/src/main/jniLibs/<abi>/` containing both `.so` files. |
| `Vol+ + Vol- + Power` toggles screen instead of exiting fullscreen | Stock unrooted firmware reserves `KEYCODE_POWER` for `PhoneWindowManager`; tap the top-right **Viewport** pill or press System Back. |
