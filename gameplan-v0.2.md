# KestrelVM v0.2 — Full Virtual Android Device & Native APK Execution Gameplan

This document is the engineering and local-build blueprint for taking **KestrelVM** from its current verified **v0.2.0 JNI / Telemetry / Rootfs Staging foundation** to a **fully functional sideloaded virtual Android phone** capable of pulling, installing, and running unmodified third-party `.apk` applications inside isolated, root-enabled guest containers.

> **Important Scope & Distribution Notice**
>
> Building a true Android-on-Android container that pulls host APKs and executes third-party `.apk` / `.dex` / `.so` binaries at runtime is intended **strictly for local sideloaded builds (`assembleDebug` / personal APK)** on your own hardware.
> - **Google Play Store Policy:** Dynamic Code Loading (executing external `.apk`, `.dex`, `.jar`, or `.so` files), `REQUEST_INSTALL_PACKAGES`, and `QUERY_ALL_PACKAGES` are restricted/prohibited on Google Play and in Play-compliant cloud builds.
> - **Android OS Security (`targetSdk 36`):** Android 10+ marks app data directories (`filesDir`, i.e., `app_data_file`) as **`noexec`** under SELinux. Running real guest ELF binaries requires specific local packaging strategies detailed in **Phase 1** below.
> - Keep AI Studio cloud-compatibility rules intact if you continue round-tripping Kotlin/docs through Git (see **Section 8**).

---

## 0. Verified v0.2.0 Baseline (Already Working in Repo)

| Component | Path | Verified Status |
| :--- | :--- | :--- |
| **Rust Sandbox Engine** | `native/kestrel_sandbox_rust/` | Built to `0.2.0` across `arm64-v8a`, `armeabi-v7a`, `x86_64`. Exports all **6** JNI functions (`nativeGetBridgeVersion`, `nativeBootSandbox`, `nativeStopSandbox`, `nativeTranslatePath`, `nativeBinderTransact`, `nativeGetSandboxTelemetry`). Multi-arch `seccomp-bpf`, `fork+ptrace` scaffold, and `BinderRouter` working. |
| **C++17 Virtual HAL** | `native/kestrel_hal_cpp/` | Built to `0.2.0` across all 3 ABIs with `-Wl,-z,max-page-size=16384`. Exports all **10** JNI functions (`ANativeWindow_fromSurface`, `ANativeWindow_lock`/`unlockAndPost`, dynamic `libaaudio.so` stream + ring buffer fallback). |
| **Rootfs Provisioner** | `app/src/main/java/com/example/data/rootfs/RootfsProvisioner.kt` | ZipSlip/TarSlip-safe extraction (`.zip`, `.tar`, `.tar.gz`, `.img`), SHA-256 verification, and `init.rc`/`build.prop`/`su` seeding. |
| **Host Hypervisor UI** | `app/src/main/java/com/example/` | VM chooser, PiP & fullscreen modes, two-way SAF file vault, real EGL/IMU/CPU/RAM telemetry, and APK metadata staging (`base.apk` + icon extraction into `data/app/<pkg>/`). |

### Why Imported APKs Currently Show an Info Window (`GuestInstalledApkSandboxView`)
Currently, `SandboxFileStorage.stageApkIntoVirtualDataApp()` parses the `.apk` using `PackageManager.getPackageArchiveInfo`, extracts its icon, and copies `base.apk` to `filesDir/vm_sandboxes/<vmId>/data/app/<pkg>/base.apk`. When you tap the app icon on the guest Home Screen, `GuestInstalledApkSandboxView` displays that metadata because **no guest Android OS runtime (`init` -> `servicemanager` -> `zygote64` -> `system_server` -> `surfaceflinger` -> `app_process`) is executing inside the container yet**.

Completing Phases **1 through 7** below replaces that info card with a live guest Android userspace rendering into the `ANativeWindow` surface.

---

## 1. Local Toolchain & 16 KB ELF Alignment Prerequisites

Use the working workstation toolchain from `results.md`:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"
export PATH="$HOME/.cargo/bin:$PATH"
```

### Fix 16 KB Page Alignment for Rust `libkestrel_sandbox.so`
`CMakeLists.txt` already passes `-Wl,-z,max-page-size=16384` for `libkestrel_hal.so`. Add the matching linker argument for Rust so Android 15/16 (`targetSdk 35/36`) stops flagging `LOAD segment not aligned`.

Create `native/kestrel_sandbox_rust/.cargo/config.toml`:
```toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.x86_64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

Verify 16 KB alignment (`Align 0x4000`) locally after rebuilding:
```bash
READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$READELF" -l app/src/main/jniLibs/arm64-v8a/libkestrel_sandbox.so | grep -A1 LOAD
"$READELF" -l app/src/main/jniLibs/arm64-v8a/libkestrel_hal.so     | grep -A1 LOAD
```

---

## 2. Phase 1 — Solve Android 10+ `noexec` (`app_data_file`) for Guest ELF Execution

### The OS Blocker
On Android 10+ (`targetSdk >= 29`), SELinux assigns `filesDir` (`vm_sandboxes/<vmId>/`) the context `u:object_r:app_data_file:s0`, which **denies `execute` / `execute_no_trans`**. Calling `execve()` on any downloaded guest binary inside `filesDir` fails with `EACCES` (Permission denied), even when `chmod 0755` is set.

### Local Sideload Fix (Bundle Bootstrap Launcher in `jniLibs` + Legacy Extraction)
Only files packaged in `app/src/main/jniLibs/<abi>/` with `extractNativeLibs="true"` are extracted by Android's `PackageManager` at APK install time into `applicationInfo.nativeLibraryDir` with executable SELinux labels (`apk_data_file` / `system_file`).

1. **Create a standalone static/bionic C or Rust bootstrap executable** named `libkestrel_init.so` (and optional `libkestrel_proot.so`) packaged inside `app/src/main/jniLibs/<abi>/`:
   - `Android` only extracts files under `jniLibs/<abi>/` matching the naming convention `lib*.so`.
   - When `supervisor.rs` calls `fork()`, the child runs `execve("<nativeLibraryDir>/libkestrel_init.so", [rootfs_dir, vm_id, ...])` instead of trying to `execve` a binary stored inside `filesDir`.
2. **Enable native lib extraction in the local sideload build** (`app/src/main/AndroidManifest.xml` & `app/build.gradle.kts` — *local sideload branch only*):
   - In `<application ...>` set `android:extractNativeLibs="true"`.
   - In `app/build.gradle.kts` under `android { packaging { jniLibs { useLegacyPackaging = true } } }`.
3. **Pass `context.applicationInfo.nativeLibraryDir` from Kotlin to Rust**:
   - Extend `NativeEngineBridge.bootSandbox(vmId, rootfsDir, nativeLibDir, seccompMode)` (or pass via environment/config file) so `supervisor.rs` locates `libkestrel_init.so` reliably on all devices.
4. **In-guest dynamic ELF loading (for guest binaries inside `vm_sandboxes/<vmId>/`):**
   - `libkestrel_init.so` (running under `seccomp-bpf` + `ptrace`) maps guest ELF segments (`PT_LOAD` + `PT_INTERP` `/system/bin/linker64`) via `mmap(..., PROT_READ | PROT_EXEC)` using an in-memory/PRoot userspace loader OR executes host-packaged helper binaries from `nativeLibraryDir`, transferring control to the guest entrypoint without triggering SELinux `app_data_file:execute` denials on downloaded files.

### Phase 1 Exit Check
- Boot `vm-aosp14-dev`, open **Hardware** tab -> **Seccomp-BPF Supervisor** card shows `State: PTRACE_PID_<pid>` (not `ARMED_AWAITING_INIT`), `Guest ELF Init: Present`, and incrementing `Syscall Traps`.

---

## 3. Phase 2 — Full `ptrace` Syscall Rewrite Engine (`seccomp.rs` + `supervisor.rs`)

Right now `supervisor.rs` traces `SECCOMP_RET_TRACE` events and logs `nr` and `arg0`, then resumes the child unmodified. To make an unmodified guest binary believe it is running on its own rooted `/system` and `/data` partitions:

1. **Extend `SyscallRegs` (`supervisor.rs`) for full 6-argument read/write across `aarch64`, `arm`, and `x86_64`:**
   - Capture/set `nr`, `args[0..6]`, `sp`, and return value (`x0` / `r0` / `rax`) via `PTRACE_GETREGSET(NT_PRSTATUS)` and `PTRACE_SETREGSET(NT_PRSTATUS)` (plus `NT_ARM_SYSTEM_CALL` on `aarch64` when cancelling or altering syscall numbers).
2. **Implement safe guest memory R/W helpers (`ptrace_mem.rs`):**
   - `read_guest_cstring(pid, addr)` using `process_vm_readv` (fallback to `PTRACE_PEEKDATA`).
   - `write_guest_scratch_cstring(pid, sp, new_path)` allocating temporary space in a safe scratch page mapped inside the guest (preferred over raw below-`sp` writes on multi-threaded processes).
3. **Implement Syscall Entry Path Rewriting (`translate_guest_path`):**
   - On `openat(dirfd, pathname, flags, mode)`, `faccessat`, `newfstatat` / `statx`, `readlinkat`, `mkdirat`, `unlinkat`, `renameat2`, `execve` / `execveat`:
     - If `dirfd == AT_FDCWD` or path starts with `/`: canonicalize against `vm_sandboxes/<vmId>/`, write translated host path to guest scratch memory, and update the pointer register before continuing.
     - If path targets `/dev/binder`, `/dev/hwbinder`, or `/dev/vndbinder`: rewrite to the VM's isolated Unix socket / proxy descriptor managed in Phase 3.
     - Intercept `execve` / `execveat` on `/system/bin/*` or `/data/local/tmp/*` to route through the Phase 1 userspace ELF loader (`libkestrel_init.so` / `linker64`) and handle `#!` scripts.
4. **Implement Syscall Exit Spoofing (Virtual Root & Device Identity):**
   - Set `PTRACE_O_TRACESYSGOOD` cleanly on exit for intercepted syscalls (`getuid`, `geteuid`, `getgid`, `getegid`, `getresuid`, `getresgid`, `uname`, `statx`/`fstatat64`, `capget`).
   - When `rootEnabled == true` for the VM: force UID/GID syscalls to return `0` (`uid=0(root)`) and rewrite `st_uid`/`st_gid` to `0` so `su`, `id`, `Toybox`, and guest apps see authentic root ownership inside the jail without touching host kernel credentials.
   - Support `clone` / `fork` / `vfork` (`PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK`) so multi-threaded ART/Java guest apps remain attached under the supervisor loop (replace single-child `waitpid(pid)` with a tracked `HashMap<Pid, ProcessState>` across the VM process tree).

### Phase 2 Exit Check
- Running a static guest `/system/bin/sh -c "id && ls /system/build.prop"` inside the supervisor prints `uid=0(root)` and reads `vm_sandboxes/<vmId>/system/build.prop` without seeing host `/data/user/0/...` paths.

---

## 4. Phase 3 — Live Userspace Binder Daemon & `servicemanager` (`binder_proxy.rs`)

Currently `BinderRouter` only handles synchronous strings from Kotlin JNI. Real Android apps communicate continuously via `/dev/binder` `ioctl(BINDER_WRITE_READ)`.

1. **Create per-VM Unix Domain Socket Server (`binder_daemon.rs`):**
   - On `nativeBootSandbox(vmId, ...)`, bind an `AF_UNIX` `SOCK_SEQPACKET` (or `SOCK_STREAM`) socket at `<rootfs>/dev/binder_proxy.sock` in a background Tokio/std thread.
2. **Implement Guest `libbinder_kestrel.so` Shim (or kernel Binder protocol emulator):**
   - Intercept guest `open("/dev/binder")` in `supervisor.rs` -> return a connected fd to `binder_proxy.sock` (or track binder fds in `ProcessState` and intercept `ioctl(fd, BINDER_WRITE_READ, &bwr)`).
   - Decode/route `BC_TRANSACTION` / `BC_REPLY` packets in `binder_proxy.rs` for:
     - Handle `0` (`servicemanager`): `CHECK_SERVICE`, `ADD_SERVICE`, `LIST_SERVICES`
     - `SurfaceFlinger` / `ISurfaceComposer`: allocate shared `memfd`/`ashmem` graphic buffers and pass buffer descriptors to **Phase 5 (`libkestrel_hal.so`)**
     - `package` (`IPackageManager`): return installed packages from the VM's isolated `/data/system/packages.xml` + `/data/app/<pkg>/base.apk`
     - `activity` (`IActivityManager`): manage guest `Activity` stack, `ContentProvider` queries, and `startActivity` intents within the VM
     - `sensorservice` / `audio`: read live IMU frames and write PCM buffers to `libkestrel_hal.so`

---

## 5. Phase 4 — Bootable Stripped AOSP Guest Rootfs (`RootfsProvisioner.kt` + Local Image Builder)

Real APKs require ART, Bionic, and core AOSP framework JARs inside `vm_sandboxes/<vmId>/`.

### A. Rootfs Extraction Fixes in `RootfsProvisioner.kt`
Update `extractTarStreamSafe` before extracting real AOSP rootfs `.tar.gz` archives:
1. **POSIX Permissions (offset `100..108`):** Parse the octal mode field and apply `outFile.setExecutable((mode and 0b001_001_001) != 0, false)` so binaries and scripts retain `+x`.
2. **Symlinks (Tar `typeFlag == '2'`):** Read link target (`header[157..257]`), verify the resolved symlink destination stays strictly inside `rootCanonical`, and create via `android.system.Os.symlink(target, outFile.absolutePath)` (AOSP `/system/bin/sh -> toybox`, `/system/lib64/...` rely heavily on symlinks).
3. **Hardlinks (Tar `typeFlag == '1'`):** Resolve target inside `rootCanonical` and copy/link safely.

### B. Build a Minimal Bootable AOSP Userspace Archive (`aosp14-arm64-rootfs.tar.gz`) Locally
On your Linux workstation, extract or build from an AOSP `sdk_phone64_arm64-userdebug` / LineageOS / GSI `system.img`:
- **Minimum required layout inside the `.tar.gz`:**
  - `/system/bin/{linker64, sh, toybox, app_process64, servicemanager, surfaceflinger, am, pm}`
  - `/system/lib64/` (`libc.so`, `libm.so`, `libdl.so`, `liblog.so`, `libcutils.so`, `libutils.so`, `libbinder.so`, `libandroid_runtime.so`, `libart.so`, `libhwui.so`, `libEGL.so`, `libGLESv2.so`, plus `hw/hwcomposer.kestrel.so`, `hw/gralloc.kestrel.so`)
  - `/system/framework/` (`boot*.art`, `boot*.oat`, `framework.jar`, `core-oj.jar`, `core-libart.jar`, `services.jar`, `ext.jar`)
  - `/system/etc/` (`ld.config.txt`, `linker.config.json`, `public.libraries.txt`, `fonts.xml`, `/system/fonts/Roboto-Regular.ttf`)
  - `/system/xbin/su`
- Import via **Hardware tab -> Guest AOSP Rootfs Provisioning -> Import Archive** (or host over local HTTPS and pull via **HTTPS URL** with SHA-256 verification).

---

## 6. Phase 5 — Zero-Copy Framebuffer Compositor & Input Injection (`kestrel_virtual_hal.cpp`)

Currently `GuestEglSurfaceApp` draws an animated test wave into `ANativeWindow` to verify the C++ surface lock/post pipeline. To render real guest app UIs:

1. **Shared Framebuffer Transport (`memfd` / `ashmem` / `mmap`):**
   - Create an `RGBA_8888` shared buffer at `<rootfs>/dev/fb0` (sized to `vm.widthPx x vm.heightPx`, or scaled viewport resolution) or pass an `ashmem` fd over Unix socket `SCM_RIGHTS` between guest `hwcomposer.kestrel.so` and host `libkestrel_hal.so`.
   - Update `nativeBlitGuestFrame` in `native/kestrel_hal_cpp/kestrel_virtual_hal.cpp` to:
     - Wait/poll dirty-frame atomic counter or eventfd.
     - `ANativeWindow_lock(window, &buffer, nullptr)`
     - Copy/scale guest RGBA_8888 scanlines respecting `buffer.stride`
     - `ANativeWindow_unlockAndPost(window)`
2. **Touch & Key Event Forwarding (Host `SurfaceView` -> Guest Input):**
   - Add two JNI functions to `libkestrel_hal.so` and `NativeEngineBridge.kt`:
     - `nativeInjectTouchEvent(vmId: String, action: Int, xPx: Float, yPx: Float, pointerId: Int)`
     - `nativeInjectKeyEvent(vmId: String, keyCode: Int, action: Int)`
   - Attach `Modifier.pointerInteropFilter` on the guest `SurfaceView` in `VirtualGuestDisplay` to map viewport coordinates into spoofed `vm.widthPx x vm.heightPx` coordinates and forward `ACTION_DOWN` / `ACTION_MOVE` / `ACTION_UP` into the guest `/dev/input/event0` socket consumed by the guest `InputDispatcher`.
3. **Always-On Surface Mode in `ViewportScreen` & `FullscreenGuestVmContainer`:**
   - Replace the static Compose mock view when a live AOSP rootfs is booted (`rootfsManifest.elfInitPresent == true`) with the live `SurfaceView` backed by `ANativeWindow`, keeping the floating top-right **Viewport** exit pill and system `Back` handling intact.

---

## 7. Phase 6 — Host APK Puller, Real `pm install`, and Launching APKs Into the VM

### 1. Local Sideload Manifest Changes (Host Machine Only — Do Not Push to Play/Cloud if Publishing)
In `app/src/main/AndroidManifest.xml` (on your local sideload branch/flavor):
- Declare `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` **only** if you want KestrelVM to list and pull APKs already installed on your physical phone (`PackageManager.getInstalledApplications(0)` -> read `appInfo.publicSourceDir` and `appInfo.splitPublicSourceDirs`).
- *(Zero-permission alternative that works everywhere without `QUERY_ALL_PACKAGES`: use the existing **Import APK / File** Document Picker in the **Files** tab, or `adb push` into `/sdcard/Download`, and pull APKs exported via local tools.)*

### 2. Upgrade APK Extraction (`SandboxFileStorage.kt`)
Update `stageApkIntoVirtualDataApp(vmId, file)`:
1. Copy `base.apk` into `vm_sandboxes/<vmId>/data/app/<pkg>/base.apk` (already working).
2. **Unpack APK native `.so` libraries:** Open `base.apk` as a `ZipInputStream`, extract `lib/<vm.abi>/*.so` into `vm_sandboxes/<vmId>/data/app/<pkg>/lib/<abi>/` so the guest's `linker64` can resolve JNI libraries.
3. **Resolve Launchable `MAIN`/`LAUNCHER` Activity:** Parse `AndroidManifest.xml` inside `base.apk` (binary AXML parser or `PackageArchiveInfo` activities) to capture the exact launcher class (e.g., `com.example.app/.MainActivity`) and persist `launchActivity` in `GuestInstalledAppEntity` (bump `AppDatabase` version + add Room migration).
4. **Register package inside guest `/data/system/packages.list` & `/data/system/packages.xml`** so the guest `PackageManagerService` recognizes it identically to `pm install`.

### 3. Replace `GuestInstalledApkSandboxView` Info Card With Real Launch (`HypervisorViewModel.kt` & `VirtualGuestDisplay.kt`)
When the user taps an installed APK icon on the Guest Home Screen or **Open** after installing:
1. Verify `rootfsManifest.elfInitPresent == true` and the VM supervisor is running (`childPid > 0`).
   - If no AOSP rootfs is provisioned yet, show a clear actionable prompt in `GuestInstalledApkSandboxView` linking directly to **Hardware -> Guest AOSP Rootfs Provisioning** (`Import Archive` / `HTTPS URL`) rather than silently showing a dead info card.
2. When the AOSP guest OS is running:
   - Dispatch `am start -n <packageName>/<launchActivity>` over the guest `binder_proxy` / supervisor control socket (`NativeEngineBridge.launchGuestActivity(vmId, pkg, activity)`).
   - Switch `VirtualGuestDisplay` onto the live `ANativeWindow` `SurfaceView` streaming the launched app's frames, forwarding touch/key/sensor/audio streams in real time.

---

## 8. Phase 7 — Local Build, Verification & AI Studio Round-Trip Guardrails

### One-Shot Local Rebuild & Install Sequence
Run from the repository root on your Linux workstation:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"
export PATH="$HOME/.cargo/bin:$PATH"

# 1. Build Rust Sandbox + Bootstrap Init (.so) with 16 KB alignment
(cd native/kestrel_sandbox_rust && cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -P 24 \
  -o ../../app/src/main/jniLibs \
  build --release)

# 2. Build C++17 Virtual HAL (.so) with 16 KB alignment
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

# 3. Verify JNI Exports & 16 KB PT_LOAD Alignment
NM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$NM" --dynamic --defined-only app/src/main/jniLibs/arm64-v8a/libkestrel_sandbox.so | grep Java_
"$NM" --dynamic --defined-only app/src/main/jniLibs/arm64-v8a/libkestrel_hal.so     | grep Java_
"$READELF" -l app/src/main/jniLibs/arm64-v8a/libkestrel_sandbox.so | grep -A1 LOAD
"$READELF" -l app/src/main/jniLibs/arm64-v8a/libkestrel_hal.so     | grep -A1 LOAD

# 4. Run Rust & Android Tests, Then Install Debug APK on Physical Phone
(cd native/kestrel_sandbox_rust && cargo test --workspace --all-targets)
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug installDebug
adb shell am start -n com.example/.MainActivity
adb logcat -s KestrelSandbox:* KestrelVirtualHAL:* AndroidRuntime:E
```

### AI Studio Cloud Round-Trip Rules (Keep Clean Before Pushing)
1. **Never commit `local.properties`, `.env`, `debug.keystore`, `.cxx/`, `build/`, or `native/kestrel_sandbox_rust/target/`.**
2. **Do not add unconditional `externalNativeBuild` blocks** in `app/build.gradle.kts` (prebuild `.so`s into `app/src/main/jniLibs/<abi>/` instead).
3. **Do not alter `signingConfigs` (`release` and `debugConfig`)** in `app/build.gradle.kts`.
4. **Keep sideload-only manifest permissions (`QUERY_ALL_PACKAGES` / `extractNativeLibs="true"`) on a separate local Gradle flavor or local branch** if you still intend to sync Kotlin changes back into AI Studio's Play-checked cloud builds.
