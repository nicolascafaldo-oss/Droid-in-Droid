# KestrelVM — Error Handling & Code Cleanup Log

This document records the codebase-wide error/warning audit and fixes applied across the **Rust sandbox (`native/kestrel_sandbox_rust/`)**, **C++17 Virtual HAL (`native/kestrel_hal_cpp/`)**, **Kotlin/Compose host app (`app/`)**, and **Gradle configuration**, so the local build agent has full context before recompiling the native `.so` libraries.

---

## 1. Rust Sandbox Engine (`native/kestrel_sandbox_rust/`)

### A. Restored Phase E Modules & JNI Exports (`src/lib.rs`)
- **Why it broke:** A partial edit to `src/lib.rs` had removed `mod binder_proxy;`, `mod seccomp;`, `mod supervisor;`, and all six `Java_com_example_engine_NativeEngineBridge_*` JNI functions while leaving unused `std::collections::HashMap` / `std::sync::{Mutex, OnceLock}` imports behind. That would have produced unused-import warnings/errors under `-D warnings` and built a `libkestrel_sandbox.so` missing all JNI symbols expected by `NativeEngineBridge.kt`.
- **How it was fixed:** Restored all Phase E modules and `#[no_mangle] pub extern "system" fn Java_com_example_engine_NativeEngineBridge_*` entry points (`nativeGetBridgeVersion`, `nativeBootSandbox`, `nativeStopSandbox`, `nativeTranslatePath`, `nativeBinderTransact`, `nativeGetSandboxTelemetry`) and retained pure-Rust `translate_guest_path` unit tests inside `mod tests`.

### B. Dead-Code Warning on `SupervisorShared.path_rewrites` (`src/supervisor.rs`, `src/lib.rs`)
- **Why it warned:** `SupervisorShared.path_rewrites` (`AtomicU64`) was incremented in `nativeTranslatePath` (`fetch_add`), but `nativeGetSandboxTelemetry` only read `entry.path_translations`, causing `rustc` to emit a `field path_rewrites is never read` warning.
- **How it was fixed:** `nativeGetSandboxTelemetry` now loads `sup.shared.path_rewrites.load(Ordering::Relaxed)` and reports `entry.path_translations.max(sup_rewrites)` in the JSON telemetry payload.

### C. Crate Version Mismatch (`Cargo.toml`)
- **Why it mattered:** `Cargo.toml` declared `version = "0.1.0"` while `nativeGetBridgeVersion` reported `kestrel_sandbox-rs/0.2.0`.
- **How it was fixed:** Bumped `version = "0.2.0"` in `native/kestrel_sandbox_rust/Cargo.toml` to match the Phase E engine ABI.

### D. Fork Child & Seccomp Variadic C-ABI Types, Fail-Closed Seccomp, and Portable `errno` (`src/supervisor.rs`, `src/seccomp.rs`)
- **Why it was unsafe / non-portable:**
  - Inside the `fork()` child branch (async-signal-safe zone before `execve`) and in `seccomp::install_prebuilt_seccomp`, `libc::prctl` and `libc::ptrace` received untyped `0` integer literals (`i32` by default) for variadic arguments.
  - `seccomp::install_prebuilt_seccomp(&mut prebuilt_filter)` discarded its `Result` with `let _ = ...` in the child process, which would allow a guest ELF to execute without syscall filtering if `PR_SET_SECCOMP` failed.
  - `seccomp.rs` called `libc::__errno()`, which only exists on Android Bionic and fails to compile when running host `cargo test` on Linux glibc.
- **How it was fixed:**
  - Passed explicit C-ABI types (`libc::SIGKILL as libc::c_ulong`, `0 as libc::c_ulong` for `prctl`, and `0 as libc::pid_t`, `std::ptr::null_mut::<c_void>()` for `ptrace`) across both `supervisor.rs` and `seccomp.rs` so variadic register promotion is unambiguous across `aarch64`, `arm` (`armv7`), and `x86_64`.
  - Replaced `libc::__errno()` with zero-allocation `std::io::Error::last_os_error().raw_os_error()` so `cargo test` runs on Linux hosts as well as Android targets.
  - Enforced fail-closed child termination: if `install_prebuilt_seccomp` returns `Err(_)`, the child immediately calls `libc::_exit(121)` instead of continuing to `execve`.

---

## 2. C++17 Virtual HAL (`native/kestrel_hal_cpp/kestrel_virtual_hal.cpp`)

### A. Unintended Map Insertions & Per-Tick `std::vector` Deep Copies (`nativeGetHalTelemetry`, `nativeBlitGuestFrame`)
- **Why it was buggy/slow:**
  - `nativeGetHalTelemetry` accessed `g_displays[id]`, `g_surfaces[id]`, and `g_audio[id]` via `operator[]`. Querying telemetry for any unknown or stopped `vmId` silently inserted default structs into all three global maps.
  - Worse, `const auto audio = kestrel::hal::g_audio[id];` deep-copied `std::vector<int16_t> ring_scratch` on every 2.4-second telemetry poll.
  - Similarly, `nativeBlitGuestFrame` read `g_sensors[id]` with `operator[]`.
- **How it was fixed:** Switched all read-only lookups in `nativeGetHalTelemetry` and `nativeBlitGuestFrame` to `.find(id)` with stack-local defaults and extracted only scalar fields (`width`, `height`, `frames_presented`, `last_blit_us`, `active`, `sample_rate`, `frames_written`) so no map mutation or vector copy occurs during telemetry reads.

### B. Inconsistent Error State on AAudio Stream Failure (`nativeStartAudioStream`)
- **Why it was inconsistent:** When `openStream` failed, `audio.active` was set to `true` and the function returned `-3`, whereas `createStreamBuilder` (`-2`) and `requestStart` (`-4`) left `audio.active = false` despite Kotlin `NativeEngineBridge` treating software ring-buffer fallback as active whenever hardware AAudio is unavailable.
- **How it was fixed:** Whenever `createStreamBuilder`, `openStream`, or `requestStart` fails at runtime (e.g., busy audio device or unsupported sample rate), the HAL logs a `LOGW(...)` reason, closes any partially opened stream, sets `audio.active = true` (software ring-buffer mode), and consistently returns `1`.

### C. Silent `ANativeWindow_lock` Buffer Validation Failures (`nativeBlitGuestFrame`)
- **Why it was opaque:** If `ANativeWindow_lock` returned `0` with invalid `buffer.bits`, dimensions, or stride, it unlocked and returned `-4` without any Logcat diagnostic.
- **How it was fixed:** Added `LOGW("Invalid locked ANativeWindow_Buffer ...")` before calling `ANativeWindow_unlockAndPost` (required by NDK to balance a `0` return from `ANativeWindow_lock`) and returning `-4`.

---

## 3. Kotlin / Jetpack Compose & Gradle (`app/`, `settings.gradle.kts`)

### A. Deprecated Java `URL(String)` Constructor (`RootfsProvisioner.kt`)
- **Why it flagged:** Calling `URL(trimmedUrl)` triggers deprecation warnings in Android Studio when inspecting against JDK 20+ APIs and throws raw `MalformedURLException` directly from `java.net.URL`.
- **How it was fixed:** Replaced `URL(trimmedUrl)` with `URI(trimmedUrl).toURL()`, normalized nullable `parsed.host.orEmpty()`, and mapped parse failures to explicit `IllegalArgumentException("Invalid rootfs URL: ...")`. Added a Robolectric test (`rootfs provisioner rejects insecure http and malformed urls`) verifying both insecure `http://` and malformed URI rejections.

### B. Broken Terminal Prompt Highlighting (`GuestDeviceApps.kt`)
- **Why it was wrong:** `GuestTerminalApp` checked `line.startsWith("kestrel_sh@")` to apply secondary accent color, whereas `promptPrefix` was updated to `"root@${vm.id}:~#"` / `"shell@${vm.id}:~$"`, leaving entered commands unhighlighted.
- **How it was fixed:** Updated the check to `line.startsWith("root@") || line.startsWith("shell@")`.

### C. Unused Parameter Mismatch in App Drawer (`VirtualGuestDisplay.kt`)
- **Why it needed cleanup:** `GuestAppDrawerScreen` accepted an unused `onUninstallApp` callback even though package uninstallation lives in Settings › Apps and `GuestInstalledApkSandboxView`.
- **How it was fixed:** Removed `onUninstallApp` from both `GuestAppDrawerScreen` and its caller inside `VirtualGuestDisplay`.

### D. Double Bottom Window Inset on Wide Screens (`MainActivity.kt`)
- **Why it mattered:** `KestrelHypervisorShell` wraps content in `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`, whose `innerPadding` already consumes bottom `navigationBars` insets. Applying an extra `.windowInsetsPadding(WindowInsets.navigationBars)` on `NavigationRail` doubled bottom spacing on tablet/landscape layouts.
- **How it was fixed:** Removed the redundant `windowInsetsPadding(WindowInsets.navigationBars)` modifier from `NavigationRail`.

### E. Gradle Project Name Mismatch (`settings.gradle.kts`)
- **Why it mattered:** `rootProject.name` was still `"My Application"` instead of matching `KestrelVM` (`strings.xml` and `metadata.json`).
- **How it was fixed:** Updated `rootProject.name = "KestrelVM"` in `settings.gradle.kts`.

---

## 4. Expected Local / Lint Notices (Intentional or Resolved on Local `.so` Rebuild)

1. **v0.1 `.so` vs. v0.2 JNI Symbols & 16 KB ELF Page Alignment (`app/src/main/jniLibs/`):**
   - The cloud environment has no NDK or Rust toolchain, so `jniLibs/` still holds v0.1 binaries until rebuilt locally.
   - Running the local `cargo-ndk` + NDK CMake commands in `gameplan.md` §4 compiles these updated Rust & C++ sources, enables all v0.2 JNI symbols (`UnsatisfiedLinkError` fallbacks clear), and applies `-Wl,-z,max-page-size=16384` to `libkestrel_hal.so`.
   - **Important when running `llvm-nm` check locally:** expect **6** `Java_*` exports from `libkestrel_sandbox.so` and **10** `Java_*` exports from `libkestrel_hal.so` (up from 4 + 3 in v0.1).
2. **`processDebugGoogleServicesFile` console notice (`app/build.gradle.kts`):**
   - Kept `google-services` plugin configured with `MissingGoogleServicesStrategy.WARN` + `googleServices.missing.passthrough=true` per template compatibility; non-fatal because Firebase libraries are commented out.
3. **Android Lint (`38` non-fatal warnings in `lint-results-debug.txt`):**
   - **33** `GradleDependency` / `AndroidGradlePluginVersion` / `NewerVersionAvailable` notices for pinned catalog versions in `gradle/libs.versions.toml` (kept pinned for tested AGP 9.1.1 / Compose BOM compatibility).
   - **5** `IconLauncherShape` warnings on legacy pre-API-26 square PNGs in `mipmap-{mdpi..xxxhdpi}/ic_launcher.png` (API 26+ devices use adaptive icons in `mipmap-anydpi-v26/`).
4. **Template `applicationId` & `signingConfigs` (`app/build.gradle.kts`):**
   - Left `applicationId = "com.example"` and `signingConfigs` (`release` / `debugConfig`) untouched so existing ADB launch commands (`com.example/.MainActivity`) and cloud signing remain intact.
