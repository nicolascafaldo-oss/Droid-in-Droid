# KestrelVM — Local Build, Native Toolchain & Physical Device Gameplan

This guide walks you through running **KestrelVM** on your physical Android phone, compiling the native **Rust (`libkestrel_sandbox.so`)** and **C++17 (`libkestrel_hal.so`)** engines on your local machine, and safely syncing changes back through Git to AI Studio without breaking cloud builds.

---

## 0. Current Architecture Snapshot

| Layer | Path | Current State |
| :--- | :--- | :--- |
| **Host App (Kotlin / Compose)** | `app/src/main/java/com/example/` | **Ready.** Launch-to-Choose/Load VM flow, full-screen OEM launcher boot, PiP floating mode, two-way APK/file import-export, live hardware telemetry, and **on-demand AOSP rootfs provisioner**. |
| **Rust Sandbox Engine** | `native/kestrel_sandbox_rust/` | **Source ready (`kestrel_sandbox` v0.2.0).** Multi-arch `seccomp-bpf` filter (`aarch64`/`arm`/`x86_64`), `ptrace` supervisor, `android_logger`, and isolated userspace Binder IPC router. Recompile locally with `cargo-ndk` to activate v0.2 symbols (old v0.1 `.so` falls back safely). |
| **C++17 Virtual HAL** | `native/kestrel_hal_cpp/` | **Source ready (`kestrel_hal` v0.2.0).** 16 KB page-aligned `ANativeWindow_fromSurface` framebuffer bridge, `GLESv2`/`EGL`, and dynamically loaded `libaaudio.so` low-latency ring buffer (compiles cleanly on NDK API 24+). |

---

## 1. Local Machine Prerequisites

Install the following tools on your workstation (Linux, macOS, or Windows/WSL2):

1. **Java Development Kit (JDK) 17 or 21 (Full JDK with `javac`)**
   - Required by Android Gradle Plugin `9.1.1` and Gradle `9.3.1` (`sourceCompatibility` / `targetCompatibility` is Java 11).
   - Ensure a full JDK (e.g., Eclipse Temurin 21) is selected rather than a JRE-only install (`javac -version` must succeed). You can pin it outside the repo in `~/.gradle/gradle.properties` via `org.gradle.java.home=/path/to/jdk-21`.
   - Verify: `java -version && javac -version`
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
  - Tap the **Viewport** pill in the top-right corner of the fullscreen VM (or tap Back) to return to the compact `42.dp` hypervisor bar with the top **Viewport / Files / Hardware** tabs (clear of both the virtual and physical nav bars).
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
Run `cargo ndk` **from inside `native/kestrel_sandbox_rust`** (note: `cargo-ndk` ignores `--manifest-path` when invoking `cargo metadata`), outputting into `app/src/main/jniLibs` (already `p_align=0x4000` / 16 KB aligned on 64-bit targets; `native/kestrel_sandbox_rust/target/` is gitignored):
```bash
(cd native/kestrel_sandbox_rust && cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -p 24 \
  -o ../../app/src/main/jniLibs \
  build --release)
```
Expected outputs:
- `app/src/main/jniLibs/arm64-v8a/libkestrel_sandbox.so`
- `app/src/main/jniLibs/armeabi-v7a/libkestrel_sandbox.so`
- `app/src/main/jniLibs/x86_64/libkestrel_sandbox.so`

### Step 3: Build the C++17 Virtual HAL (`libkestrel_hal.so`)
Build each ABI with the NDK CMake toolchain (no edits to `app/build.gradle.kts` needed; `CMakeLists.txt` includes `-Wl,-z,max-page-size=16384` for 16 KB ELF `LOAD` segment alignment):
```bash
CMAKE_BIN="$(ls -d "$ANDROID_HOME"/cmake/*/bin/cmake | sort -V | tail -n 1)"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

for ABI in arm64-v8a armeabi-v7a x86_64; do
  BUILD_DIR=".cxx/kestrel_hal/$ABI"
  "$CMAKE_BIN" -S native/kestrel_hal_cpp -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
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
- **After native build (Success):** `kestrel_sandbox-rs/0.2.0 (seccomp-bpf+ptrace+binder/aarch64) | kestrel_hal-cpp17/0.2.0 (ANativeWindow+GLES20+AAudio)`

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

## 6. Phase E Engine Features (Implemented — Rebuild `.so` Locally to Activate Native Paths)

All three Phase E engineering epics are now integrated into the repository (`NativeEngineBridge` automatically guards every new v0.2 JNI call with `UnsatisfiedLinkError` fallback until you run the two commands in **Section 4**):

1. **Rust Syscall, Seccomp-BPF & Binder Supervisor (`native/kestrel_sandbox_rust/src/`):**
   - `seccomp.rs`: Multi-arch classic BPF filter (`AUDIT_ARCH` validation + `SECCOMP_RET_TRACE` for `openat`, `faccessat`, `execve`, `readlinkat`, `mkdirat`, `unlinkat`, `renameat*`, `ioctl`, `connect`, `bind`).
   - `supervisor.rs`: `fork` + `ptrace` + `PR_SET_SECCOMP` launcher & `PTRACE_GETREGSET(NT_PRSTATUS)` cross-arch register inspector whenever an ELF guest `/init` is present on disk, plus canonical `/dev/binder` path isolation.
   - `binder_proxy.rs`: Per-VM isolated `servicemanager` router (`/dev/binder_proxy/<vmId>`) that blocks direct host `/dev/binder` escapes.
2. **C++ Surface & AAudio HAL Bridge (`native/kestrel_hal_cpp/kestrel_virtual_hal.cpp`):**
   - `nativeAttachSurface` / `nativeBlitGuestFrame` (`ANativeWindow_fromSurface` -> RGBA_8888 lock/unlockAndPost into the guest **EGL GPU** `SurfaceView`).
   - Runtime-loaded `libaaudio.so` low-latency PCM stream (`nativeStartAudioStream` / `nativePlayAudioPulse`) compatible with `-DANDROID_PLATFORM=android-24`.
3. **Guest AOSP Rootfs Provisioning (`app/src/main/java/com/example/data/rootfs/RootfsProvisioner.kt`):**
   - Import `.zip`, `.tar`, `.tar.gz`, or `.img` rootfs bundles via SAF Document Picker or HTTPS URL (with optional SHA-256 verification and ZipSlip/TarSlip traversal guards) directly from the **Hardware** tab.

---

## 7. Troubleshooting Quick Reference

| Issue | Fix |
| :--- | :--- |
| `Execution failed for task ':app:validateSigningDebug' ... debug.keystore not found` | Run the `keytool -genkeypair` command from **Section 2A** at the project root. |
| `SDK location not found` | Export `ANDROID_HOME` or add `sdk.dir=/path/to/Android/Sdk` to an untracked `local.properties`. |
| `cargo-ndk: error: Could not find NDK` | Export `ANDROID_NDK_HOME` pointing to `$ANDROID_HOME/ndk/<version>`. |
| `UnsatisfiedLinkError` or fallback banner still shown in **Hardware** tab | Verify `adb shell getprop ro.product.cpu.abi` (usually `arm64-v8a`) matches a folder under `app/src/main/jniLibs/<abi>/` containing both `.so` files. |
| `Vol+ + Vol- + Power` toggles screen instead of exiting fullscreen | Stock unrooted firmware reserves `KEYCODE_POWER` for `PhoneWindowManager`; tap the top-right **Viewport** pill or press System Back. |
