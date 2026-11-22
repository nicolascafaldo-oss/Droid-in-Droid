# KestrelVM v0.2 — Full Virtual Android Device & Native APK Execution Gameplan

This document is the engineering and local-build blueprint for taking **KestrelVM** from its current verified **v0.2.0 JNI / Telemetry / Rootfs Staging foundation** to a **fully functional sideloaded virtual Android phone** capable of pulling, installing, and running unmodified third-party `.apk` applications inside isolated, root-enabled guest containers.

> **Important Scope & Distribution Notice**
>
> Building a true Android-on-Android container that pulls host APKs and executes third-party `.apk` / `.dex` / `.so` binaries at runtime is intended **strictly for local sideloaded builds (`assembleDebug` / personal APK)** on your own hardware.
> - **Google Play Store Policy:** Dynamic Code Loading (executing external `.apk`, `.dex`, `.jar`, or `.so` files), `REQUEST_INSTALL_PACKAGES`, and `QUERY_ALL_PACKAGES` are restricted/prohibited on Google Play and in Play-compliant cloud builds.
> - **Android OS Security (`targetSdk 36`):** Android 10+ marks app data directories (`filesDir`, i.e., `app_data_file`) as **`noexec`** under SELinux. Running real guest ELF binaries requires specific local packaging strategies detailed in **Phase 1** below.
> - **AI Studio is retired from this project permanently** — KestrelVM's scope (a fully rooted Android VM for users) is prohibited by Google Play Store policy, so no Play-compliant cloud build can exist. **Distribution is an open-source `.apk` on GitHub, sideloaded by the user.** The old AI Studio cloud rules have been reduced to 3 local hygiene rules (see **Section 8**).

> **Revision 3 note (current)**
>
> **Phase 1 (§2) is COMPLETE and verified on device (2026-11-21).** Sections 0, 1, 2, 8, 9 are updated with real results. New section **§2.6** carries the full completion record, test output, and the **D1–D11 deviations log**. Sections 3–7 remain unchanged roadmap. **Read §2.6 then §9 first** if picking this work up cold.
>
> **Revision 2 note (superseded)**
>
> Folded in a full codebase survey performed on 2026-11-21. Sections **0**, **1**, **2**, **8** were corrected against what the code actually did; Sections 3–7 unchanged roadmap. Added **§2.5 (containment caveat)** and **§9 (open decisions)**.

---

## 0. Verified v0.2.0 Baseline (Already Working in Repo)

| Component | Path | Verified Status |
| :--- | :--- | :--- |
| **Rust Sandbox Engine** | `native/kestrel_sandbox_rust/` | Built to `0.2.0` across `arm64-v8a`, `armeabi-v7a`, `x86_64`. Exports all **6** JNI functions (`nativeGetBridgeVersion`, `nativeBootSandbox`, `nativeStopSandbox`, `nativeTranslatePath`, `nativeBinderTransact`, `nativeGetSandboxTelemetry`). Multi-arch `seccomp-bpf`, `fork+ptrace` scaffold, and `BinderRouter` working. |
| **C++17 Virtual HAL** | `native/kestrel_hal_cpp/` | Built to `0.2.0` across all 3 ABIs with `-Wl,-z,max-page-size=16384`. Exports all **10** JNI functions (`ANativeWindow_fromSurface`, `ANativeWindow_lock`/`unlockAndPost`, dynamic `libaaudio.so` stream + ring buffer fallback). |
| **Rootfs Provisioner** | `app/src/main/java/com/example/data/rootfs/RootfsProvisioner.kt` | ZipSlip/TarSlip-safe extraction (`.zip`, `.tar`, `.tar.gz`, `.img`), SHA-256 verification, and `init.rc`/`build.prop`/`su` seeding. |
| **Host Hypervisor UI** | `app/src/main/java/com/example/` | VM chooser, PiP & fullscreen modes, two-way SAF file vault, real EGL/IMU/CPU/RAM telemetry, and APK metadata staging (`base.apk` + icon extraction into `data/app/<pkg>/`). |
| **Phase 1 Bootstrap Launcher** | `native/kestrel_hal_cpp/kestrel_init.c` | **NEW, verified on device 2026-11-21.** 161-line C launcher emitted as `libkestrel_init.so` (an `ET_DYN`/PIE *executable*, not a JNI lib). Re-establishes `PR_SET_PDEATHSIG`, validates argv (`rootfs`, `vm_id`), refuses `..`, `realpath`+`chdir`, logs a 12-path rootfs survey to logcat tag `KestrelInit` via `__android_log_print`, closes fds 3–1023, then `pause()`s forever. **Safer variant A: no guest-authored ELF is ever executed (§2.4 step 5 deliberately NOT implemented).** |

### 0.1 Survey Corrections (verified against source, 2026-11-21)

These are places where the original roadmap's assumptions were wrong or incomplete. Trust this table over prose elsewhere in this doc.

| Assumption | Reality in code | Consequence |
| :--- | :--- | :--- |
| Supervisor rewrites syscalls | **No.** `supervisor.rs:316-333` only *reads* `nr`/`arg0`, logs a trace line, and calls `ptrace::cont()`. No `PTRACE_SETREGSET`, no path rewriting. | Phase 2 is genuinely unimplemented, as written. |
| Supervisor tracks a process tree | **No.** Single `waitpid(pid, __WALL)` against one child (`supervisor.rs:315`). No `HashMap<Pid, ProcessState>`. | Phase 2 clone/fork tracking is genuinely unimplemented. |
| `libkestrel_init.so` bootstrap exists | **No.** No such file anywhere in the repo. | Phase 1 starts from zero. |
| `extractNativeLibs="true"` set | **No.** `AndroidManifest.xml` declares only `INTERNET`. | Phase 1 must add it. |
| `useLegacyPackaging` set | **No.** `app/build.gradle.kts` has **no `packaging {}` block at all**. | Phase 1 must add it — this is the actual switch. |
| `bootSandbox` takes `nativeLibraryDir` | **No.** Signature is `bootSandbox(vmId, rootfsDir, seccompMode)` (`NativeEngineBridge.kt:93`, `lib.rs:63`). | Phase 1 must extend it. |
| Tar extraction handles symlinks/hardlinks/POSIX modes | **No.** `extractTarStreamSafe` (`RootfsProvisioner.kt:235`) branches only on typeFlag `'5'` (dir) and `'0'`/NUL (file). **`'2'` (symlink) and `'1'` (hardlink) fall into the `else` and are silently skipped.** No mode field is parsed, so `setExecutable` is never called and `+x` is never applied. | **Blocks Phase 4.** AOSP rootfs is symlink-dense (`/system/bin/sh -> toybox`, `lib64` links) and binary-mode-dependent. Promote these fixes to a Phase 4 *prerequisite* (see §7). |
| APK staging unpacks native libs / launcher activity | **No.** `stageApkIntoVirtualDataApp` (`SandboxFileStorage.kt:239`) copies `base.apk` and extracts an icon. No `lib/<abi>/*.so` unpack, no MAIN/LAUNCHER resolution, no `packages.xml` registration. | Phase 6 scope confirmed as written. |
| Rust `libkestrel_sandbox.so` needs a 16 KB alignment fix (§1) | **Already `0x4000`** on `arm64-v8a` and `x86_64` (NDK r27 defaults to it). `armeabi-v7a` is `0x1000`, which is **correct** — 16 KB `LOAD` alignment only applies to 64-bit ABIs. | The original §1 "fix" is a **no-op**. Adding `.cargo/config.toml` is harmless belt-and-braces but is *not* repairing a defect. Do not report it as a fix. |
| `cargo-ndk -p 24` works | **No** on cargo-ndk ≥ 4.0. `-p` is now cargo's `--package` flag; the platform flag is `-P`/`--platform`. `-p 24` panics with `unknown package: 24`. | Use `-P 24` everywhere (see §1 and §8). Already fixed in `gameplan.md` §4. |

### 0.2 Deviations found during Phase 1 implementation (D1–D11) — trust these

Discovered while implementing §2. Several are places the roadmap was simply wrong. Full detail in **§2.6**; this is the index.

| ID | Deviation | Status |
| :-- | :--- | :--- |
| D1 | `native/kestrel_hal_cpp/CMakeLists.txt` declared `project(... LANGUAGES CXX)` — **C was not enabled**, so a `.c` file could not have compiled. Added `C`. | ✅ fixed |
| D2 | §2.3 #8 said "build `find_guest_init()`" — it **already existed** (`supervisor.rs:117`). Only needed the `nativeLibraryDir` candidate prepended. | ✅ fixed |
| D3 | `execve` argv was `[prog, NULL]`; `rootfs`/`vm_id` `CString`s had to be built **before `libc::fork()`** to outlive the child. | ✅ fixed |
| D4 | `find_guest_init` searched **only the rootfs**, so a fresh VM returned `None` → state stuck at `ARMED_AWAITING_INIT` and §2.2 **could never pass**. Preferring `<nativeLibDir>/libkestrel_init.so` is what turns the exit check green. | ✅ fixed |
| D5 | **§2.4 never accounted for the seccomp filter.** `seccomp.rs` default action is `SECCOMP_RET_KILL_PROCESS` and `ALLOW_FAST` held only 9 syscalls — `prctl`, `chdir`, `pause`, `exit_group`, and `realpath`'s internals were all unlisted, so the launcher would have been **SIGSYS-killed at/before `main`**. Required a new per-arch `LAUNCHER_ALLOW` set. | ✅ fixed |
| D6 | **`pause` does not exist as a syscall on arm64** (asm-generic defines none), and bionic's `pause()` lowers to `sigsuspend64` on *every* ABI. The required-syscall list must specify `rt_sigsuspend`, not `pause`. | ✅ fixed |
| D7 | nativeloader logs **2 of 3** `.so` files loading `ok` (`libkestrel_sandbox`, `libkestrel_hal`). `libkestrel_init.so` **has no nativeloader line at all** — it is an executable, never `System.loadLibrary`'d (it is `execve`'d by the supervisor). Its proof-of-life is §8 checklist item 2 instead: extracted `-rwxr-xr-x` under `<apkdir>/lib/<abi>/`. | ✅ §8 item 1 reworded to match |
| D8 | Under **variant A**, `Syscall Traps` climbs during the survey then **freezes** — the launcher `pause()`s and stops making syscalls. It does not continuously climb until real guest userspace exists. **Static ≠ broken.** `0` would be broken. | ⚠️ expected behavior |
| D9 | The provisioned rootfs is a **stub**. Survey showed `system/build.prop`, `dev`, `data`, `system/framework` **present**; `init`, `system/bin/sh`, `system/bin/toybox`, `system/lib64`, `system/bin/app_process64`, `system/bin/servicemanager` **absent**. Nothing guest-side can run until Phase 4 gets a real image (§9.2). | ⛔ blocks Phase 4+ |
| D10 | Pre-existing bug: `NativeEngineBridge.kt:287` sets `syscallTraps = state?.pathTranslations` (copy/paste) in the **Kotlin fallback** telemetry branch. Harmless while the native path is active. | 🔲 backlog |
| D11 | UI label **`Path Translations`** reads the **JNI probe counter** (`lib.rs:160`, driven by the `Probe Binder IPC & Path Jail` button) — **not** supervisor syscall rewriting. The ptrace loop never increments it. Easy to mistake for Phase 2 working when it is not. | 🔲 backlog |

### Why Imported APKs Currently Show an Info Window (`GuestInstalledApkSandboxView`)
Currently, `SandboxFileStorage.stageApkIntoVirtualDataApp()` parses the `.apk` using `PackageManager.getPackageArchiveInfo`, extracts its icon, and copies `base.apk` to `filesDir/vm_sandboxes/<vmId>/data/app/<pkg>/base.apk`. When you tap the app icon on the guest Home Screen, `GuestInstalledApkSandboxView` (defined `app/src/main/java/com/example/ui/components/GuestDeviceApps.kt:944`, invoked from `VirtualGuestDisplay.kt:370`) displays that metadata because **no guest Android OS runtime (`init` -> `servicemanager` -> `zygote64` -> `system_server` -> `surfaceflinger` -> `app_process`) is executing inside the container yet**.

Completing Phases **1 through 7** below replaces that info card with a live guest Android userspace rendering into the `ANativeWindow` surface.

---

## 1. Local Toolchain & 16 KB ELF Alignment Prerequisites

Use the working workstation toolchain:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"            # now also in untracked local.properties
export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"   # 27.2.12479018
export PATH="$HOME/.cargo/bin:$PATH"
```

> **Toolchain facts verified on the build machine**
> - `local.properties` now exists (`sdk.dir=/home/anon/Android/Sdk`) and is gitignored, so `ANDROID_HOME` export is **optional** for Gradle. Still needed for the raw CMake/cargo-ndk shell steps below.
> - NDK **r27 (`27.2.12479018`)**, CMake **3.22.1**, cargo-ndk **4.1.2**, Rust stable.
> - `gradlew` requires the executable bit (`chmod +x gradlew`). AI Studio round-trips normalize file modes to `100644` and strip it; local-only development means it now sticks.
> - Use **`assembleDebug` / `installDebug`, never `assembleRelease`** — `my-upload-key.jks` does not exist and `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` are unset, so release signing fails.
> - `debug.keystore` is generated at repo root per `gameplan.md` §2A (alias `androiddebugkey`, password `android`). It is gitignored.

### 1.1 16 KB Page Alignment — current status (no action required)

`CMakeLists.txt` already passes `-Wl,-z,max-page-size=16384` for `libkestrel_hal.so`. The Rust side is **already `0x4000`** without any extra flags.

Optional belt-and-braces (harmless, not a fix): create `native/kestrel_sandbox_rust/.cargo/config.toml`:

```toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.x86_64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

Verify (`Align 0x4000` on 64-bit; `0x1000` on `armeabi-v7a` is expected and correct):

```bash
READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for f in app/src/main/jniLibs/*/libkestrel_sandbox.so app/src/main/jniLibs/*/libkestrel_hal.so; do
  echo -n "$f: "; "$READELF" -lW "$f" | awk '/LOAD/{printf "%s ", $NF}'; echo
done
```

---

## 2. Phase 1 — Solve Android 10+ `noexec` (`app_data_file`) for Guest ELF Execution

### The OS Blocker
On Android 10+ (`targetSdk >= 29`), SELinux assigns `filesDir` (`vm_sandboxes/<vmId>/`) the context `u:object_r:app_data_file:s0`, which **denies `execute` / `execute_no_trans`**. Calling `execve()` on any downloaded guest binary inside `filesDir` fails with `EACCES` (Permission denied), even when `chmod 0755` is set.

Only files packaged in `app/src/main/jniLibs/<abi>/` with `extractNativeLibs="true"` are extracted by `PackageManager` at install time into `applicationInfo.nativeLibraryDir` with executable SELinux labels (`apk_data_file` / `system_file`). That directory is where our bootstrap launcher must live.

### 2.1 Decisions already made (do not re-litigate)

| Decision | Choice | Rationale |
| :--- | :--- | :--- |
| Launcher language | **C, built via the existing `native/kestrel_hal_cpp/CMakeLists.txt`** | Reuses the NDK CMake loop we already run per-ABI; gets the 16 KB linker flag for free; no new build plumbing. A Rust crate would fight cargo-ndk (which targets cdylibs) to produce a real `ET_DYN` executable. |
| Loader depth | **Static ELFs only — no in-memory dynamic linker** | User's explicit safety call: *"whichever is safer from a technical standpoint. we don't want anything escaping the VM."* A userspace dynamic loader (PT_INTERP, relocation, symbol resolution against bionic) would execute guest-controlled link-time data in-process — precisely the escape surface we don't want. Dynamic guest ELFs are deferred to Phase 2+. |

### 2.2 Exit Check (from the roadmap — this is the Definition of Done)

Open **KestrelVM → Hardware tab → Seccomp-BPF Supervisor** card and confirm:
- `State: PTRACE_PID_<pid>` — **not** `ARMED_AWAITING_INIT`
- `Guest ELF Init: Present` (rendered from `sb.guestInitFound`, `EngineScreen.kt:252`)
- `Syscall Traps` counter **incrementing**

### 2.3 Implementation — the 8-file change set

> **✅ IMPLEMENTED & VERIFIED ON DEVICE 2026-11-21.** See **§2.6** for the full record.
> Actual scope was **15 files**, not 8: the table below under-counted `kestrel_init.c`'s CMake language enablement (D1), the seccomp `LAUNCHER_ALLOW` work (D5), and the three rebuilt/new `.so` artifacts. The two Robolectric call sites were **not** edited — the `nativeLibDir: String = ""` default kept them compiling, exactly as the table hoped.

| # | File | Change |
| :-- | :--- | :--- |
| 1 | `native/kestrel_hal_cpp/kestrel_init.c` | **New file.** The bootstrap launcher (design in §2.4). |
| 2 | `native/kestrel_hal_cpp/CMakeLists.txt` | Add `add_executable(kestrel_init kestrel_init.c)`; `set_target_properties(... OUTPUT_NAME "kestrel_init" PREFIX "lib" SUFFIX ".so")` so it emits **`libkestrel_init.so`**; add `-pie`/`-fPIE`, `-Wl,-z,max-page-size=16384`, link `${log-lib}`. |
| 3 | `app/src/main/AndroidManifest.xml` | Add `android:extractNativeLibs="true"` to `<application>`. |
| 4 | `app/build.gradle.kts` | Add inside `android { }`: `packaging { jniLibs { useLegacyPackaging = true } }`. **This is the actual switch** — no `packaging` block exists today. |
| 5 | `app/src/main/java/com/example/engine/NativeEngineBridge.kt` | `fun bootSandbox(vmId, rootfsDir, seccompMode, nativeLibDir: String = "")` → calls `nativeBootSandbox(vmId, rootfsDir, nativeLibDir, seccompMode)`. The **default value keeps the two Robolectric call sites compiling** (`ExampleRobolectricTest.kt:122`, `:198`). Update `private external fun nativeBootSandbox(...)` to the 4-arg form. |
| 6 | `app/src/main/java/com/example/ui/HypervisorViewModel.kt:484` | Class is `AndroidViewModel(application)` (`:126`), so pass `getApplication<Application>().applicationInfo.nativeLibraryDir` as the new arg. |
| 7 | `native/kestrel_sandbox_rust/src/lib.rs:63` | Parse the new `JString` param (return `-3` on parse failure), pass through to `spawn_supervisor`. |
| 8 | `native/kestrel_sandbox_rust/src/supervisor.rs:210` | `spawn_supervisor(vm_id, rootfs_dir, native_lib_dir, seccomp_mode)`; `find_guest_init()` prefers `<nativeLibDir>/libkestrel_init.so`, then falls back to `<rootfs>/system/bin/kestrel_init`, then `<rootfs>/init`; build `execve` argv as `[prog, rootfs, vm_id, NULL]` (requires `CString`s for rootfs and vm_id to outlive the child — construct them before the thread spawns). |

> **Backward-compat note:** changing the JNI signature means an *old* `.so` paired with *new* Kotlin throws `UnsatisfiedLinkError`, which `NativeEngineBridge` already catches and degrades to `0`. The fallback path stays intact — rebuild both `.so` files in the same pass (§8) and it's moot.

**Also:** add `libkestrel_init.so` to the copy step in the §8 build sequence (currently only `libkestrel_hal.so` is copied).

### 2.4 `kestrel_init.c` — launcher design

Responsibilities, in order:

1. **Re-establish `PR_SET_PDEATHSIG(SIGKILL)`.** The supervisor sets it in the child *before* `execve` (`supervisor.rs:262`), but the kernel **clears it across `execve`**. Without re-setting it here, the launcher outlives a dead parent.
2. **Validate argv** (`rootfs`, `vm_id`), canonicalize the rootfs path, refuse `..` traversal, `chdir()` into it.
3. **Survey the rootfs** — `openat`/`access` a known list (`init`, `system/build.prop`, `system/bin/sh`, `dev`, `data`, …), logging hits to logcat under tag `KestrelInit`. Each `openat` is in the seccomp `TRACE_CORE` list, so **this alone drives the `Syscall Traps` counter** and satisfies the exit check even when no guest ELF is present.
4. **Close inherited fds ≥ 3** before handing control to guest code — the forked child inherits the host app's Room db, SAF handles, etc. This prevents a guest from writing to host fds. Keep 0/1/2.
5. **If a static guest `/init`-style ELF exists:** read it into memory, validate (magic, class, machine, `ET_EXEC`/`ET_DYN`, **reject `PT_INTERP`** — dynamic is out of scope), map `PT_LOAD` segments into **anonymous** memory (never file-backed — that's the `noexec` bypass), copy, build a stack with `argc`/`argv`/`envp`/`auxv` (`AT_PHDR`, `AT_ENTRY`, `AT_PAGESZ`, `AT_RANDOM`), **enforce W^X** (`PROT_WRITE` stripped from any page that is also `PROT_EXEC`), then jump to the entry point.
6. **If no guest ELF:** `pause()` forever so the traced process stays alive and the supervisor reads `PTRACE_PID_<pid>` rather than `EXITED_*`.

Distinct exit codes for diagnosis: `64` usage, `65` bad rootfs, `66` invalid ELF, `67` dynamic ELF rejected, `68` mmap failure, `69` stack failure. (Supervisor-side: `120` TRACEME fail, `121` seccomp fail, `127` execve fail.)

### 2.5 ⚠️ Containment Caveat — READ BEFORE IMPLEMENTING

**Phase 1 makes guest execution *possible*; it does NOT make the filesystem boundary *strong*.**

The seccomp filter (`seccomp.rs`) returns `SECCOMP_RET_TRACE` for `openat`, `faccessat`, `readlinkat`, `mkdirat`, `unlinkat`, `renameat*`, `execve`, `ioctl`, `connect`, `bind` — but the supervisor handler (`supervisor.rs:316-333`) only **logs and resumes**. It never reads the pathname argument and never rewrites it via `PTRACE_SETREGSET`.

**Therefore a guest binary running under Phase 1 can still open host paths directly** — `/data/data/com.example/...`, `/sdcard/...`, anything the app UID can read. The sandbox root is a *convention*, not an enforced boundary, until Phase 2 lands.

Consequences to respect:
- **Do not describe Phase 1 as a security milestone** in commits, docs, or UI copy.
- The W^X enforcement, fd-closing, and static-only loader in §2.4 reduce *in-process* risk but do nothing about filesystem reach.
- **Phase 2 (§3) is what actually closes the escape surface.** Treat it as required, not optional.
- If the user asks for a safer intermediate milestone, the option exists to run Phase 1 *without* the ELF jump (launcher surveys + `pause()`s only), so nothing guest-authored executes until Phase 2 exists. This is a legitimate scoping choice, not a regression.

---

## 2.6 Phase 1 Completion Record (verified 2026-11-21)

**Scope chosen: variant A (safer)** — launcher surveys + `pause()`s only. The static ELF loader/jump in §2.4 step 5 was **deliberately not implemented**. Enabling it later is a separate, explicitly-approved change.

### Device & build under test

| | |
| :--- | :--- |
| Device | SM-S356V (`a35x`), `arm64-v8a`, Android API 36 |
| VM | `vm-aosp14-dev` |
| NDK / tooling | NDK r27 `27.2.12479018`, CMake 3.22.1, cargo-ndk 4.1.2, Rust stable |
| BPF | 63 instructions (`aarch64`) |

### Exit check (§2.2) — all green

| Check | Observed | |
| :--- | :--- | :--- |
| Supervisor state | `PTRACE_PID_26921` (not `ARMED_AWAITING_INIT`) | ✅ |
| Guest ELF Init | `Present` (native log `init_elf=true`) | ✅ |
| Syscall Traps | `200` (> 0) | ✅ |
| Path Translations | `40` — **probe-button counter, see D11** | ⚠️ not rewrites |
| `logcat -s KestrelInit` | 13 lines: 12 survey probes + `survey complete ... -> pause()` | ✅ |
| Launcher alive | `ps`: `26921 26674 libkestrel_init.so` | ✅ |
| §8 item 2 — extraction | `.../lib/arm64/libkestrel_init.so` is `-rwxr-xr-x`; `dumpsys` reports `extractNativeLibs=true` | ✅ |
| §8 item 1 — nativeloader | `libkestrel_sandbox.so` **ok**, `libkestrel_hal.so` **ok** (launcher has no such line — **D7**) | ✅ |
| §8 item 5 — errors | no `UnsatisfiedLinkError`, no `AndroidRuntime:E` | ✅ |

Survey log excerpt:
```
I KestrelSandbox: Sandbox booted vm=vm-aosp14-dev mode=SECCOMP_BPF + PTRACE bpf_insns=63 init_elf=true
I KestrelInit: survey root=.../vm_sandboxes/vm-aosp14-dev path=system/build.prop present=1
I KestrelInit: survey ... path=dev present=1 | data present=1 | system/framework present=1
I KestrelInit: survey complete vm=vm-aosp14-dev -> pause()
```

### Automated tests

| Suite | Result |
| :--- | :--- |
| `cargo test --workspace --all-targets` | **10 passed, 0 failed** (baseline 8 → +2 new) |
| `./gradlew :app:testDebugUnitTest` | **12 passed, 0 failed** |
| `cargo build` (non-test) | **0 warnings** |

New tests added:
- `seccomp::tests::launcher_allow_set_covers_required_syscalls` — pins the ALLOW set per arch so a future edit can't silently drop a startup-fatal syscall.
- `seccomp::tests::trace_core_names_stay_index_aligned` — catches index-shift bugs in `syscall_name_for_nr`, which maps **by position** in `TRACE_CORE`.

### Static verification (all 3 ABIs)

| ABI | sandbox `Java_` | hal `Java_` | init `Java_` | init Type | init LOAD align |
| :-- | :-: | :-: | :-: | :-: | :--- |
| arm64-v8a | 6 ✅ | 10 ✅ | 0 ✅ | `DYN` ✅ | `0x4000` ×3 |
| armeabi-v7a | 6 ✅ | 10 ✅ | 0 ✅ | `DYN` ✅ | `0x4000` ×3 |
| x86_64 | 6 ✅ | 10 ✅ | 0 ✅ | `DYN` ✅ | `0x4000` ×3 |

`armeabi-v7a` sandbox stays `0x1000` (correct — 16 KB LOAD alignment is 64-bit only). `libkestrel_init.so` came out `0x4000` on v7a too because the `max-page-size=16384` link option applies unconditionally; **benign superset**, still loadable on 4 KB pages, left uniform deliberately.

### What was actually built

**Files changed (15):**
- `native/kestrel_hal_cpp/kestrel_init.c` — **new**, 161 lines
- `native/kestrel_hal_cpp/CMakeLists.txt` — added `C` language + `kestrel_init` target
- `native/kestrel_sandbox_rust/src/seccomp.rs` — 135 → 627 lines
- `native/kestrel_sandbox_rust/src/lib.rs` — 4-arg JNI
- `native/kestrel_sandbox_rust/src/supervisor.rs` — 4-arg `spawn_supervisor`, candidate order, pre-fork CStrings
- `app/src/main/AndroidManifest.xml` — `android:extractNativeLibs="true"`
- `app/build.gradle.kts` — `packaging { jniLibs { useLegacyPackaging = true } }`
- `app/src/main/java/com/example/engine/NativeEngineBridge.kt` — `bootSandbox(..., nativeLibDir: String = "")` + 4-arg `external fun`
- `app/src/main/java/com/example/ui/HypervisorViewModel.kt` — `nativeLibraryDir ?: ""` at `:485`
- `app/src/main/jniLibs/<abi>/libkestrel_{hal,sandbox}.so` — rebuilt + stripped (3 ABIs)
- `app/src/main/jniLibs/<abi>/libkestrel_init.so` — **new** (3 ABIs)

**JNI contract (must stay in lockstep across Kotlin and Rust):**
```kotlin
private external fun nativeBootSandbox(vmId: String, rootfsDir: String, nativeLibDir: String, seccompMode: Int): Int
// parse failures: -1 vm_id, -2 rootfs_dir, -3 native_lib_dir
```

**`find_guest_init(rootfs_dir, native_lib_dir)` candidate order:**
1. `<nativeLibDir>/libkestrel_init.so` *(empty `native_lib_dir` contributes **no** candidate — preserves 3-arg Robolectric behavior)*
2. `<rootfs>/system/bin/kestrel_init`
3. `<rootfs>/init`

**`kestrel_init.c` control flow:** validate argv (exit 64) → reject `..` / `realpath` / `chdir` (exit 65) → `prctl(PR_SET_PDEATHSIG, SIGKILL)` + `getppid()==1` bail (exit 65) → **unconditional 12-path survey via `access()`, logged through `__android_log_print(ANDROID_LOG_INFO, "KestrelInit", ...)` mirrored to stdout/stderr, all before the fd close** → close fds 3–1023 → `while(1) pause()`. Exit codes 66–69 reserved as a comment block for the future ELF loader.

**seccomp posture after D5** (default action is still `SECCOMP_RET_KILL_PROCESS`):
- New per-arch `LAUNCHER_ALLOW` — aarch64 32, arm 35, x86_64 32, generic 32 entries. Covers bionic libc startup (which runs *before* `main`), the launcher, and liblog.
- **Still deliberately denied:** `ptrace`, `clone`/`clone3`/`fork`/`vfork`, `mount`/`umount2`, `pivot_root`, `chroot`, `kexec_load`, `delete_module`, `init_module`, `bpf`, `perf_event_open`, `userfaultfd`, `seccomp`.
- `TRACE_CORE` 11 → **16**: appended `chdir`, `fchdir`, `getcwd`, `newfstatat`/`fstatat64`, `statx` — kept in TRACE (not ALLOW) precisely so **Phase 2 can rewrite/spoof them**.
- `TRACE_NS_EXTRA` unchanged.
- Arch modules renamed `arch_aarch64`/`arch_arm`/`arch_x86_64` with `#[cfg(any(test, target_arch = "..."))]` + `pub use ... as arch` re-export, so the test compiles all three tables in one run. Production paths (`arch::…`) unchanged.
- Every syscall number was **header-verified**: `clang --target=<abi> -E -include asm/unistd.h` against NDK r27. **No numbers were guessed.**

### Known consequences to carry forward
1. **§2.5 still fully applies.** Phase 1 did not close the filesystem boundary — nothing rewrites guest paths yet. Do not describe it as a security milestone.
2. **D9 blocks any real guest execution.** A real AOSP rootfs (§9.2) is a hard prerequisite for Phase 4 onward.
3. **D10 and D11 are unfixed backlog items**, both roughly one line each.
4. The launcher is **dynamically linked** against host bionic (no chroot), so the linker's `openat` storm is what produces most of the 200 traps. Harmless here, but another reminder the rootfs is a convention, not a boundary.

---

## 3. Phase 2 — Full `ptrace` Syscall Rewrite Engine (`seccomp.rs` + `supervisor.rs`)

Right now `supervisor.rs` traces `SECCOMP_RET_TRACE` events and logs `nr` and `arg0`, then resumes the child unmodified. To make an unmodified guest binary believe it is running on its own rooted `/system` and `/data` partitions:

1. **Extend `SyscallRegs` (`supervisor.rs`) for full 6-argument read/write across `aarch64`, `arm`, and `x86_64`:**
   - Capture/set `nr`, `args[0..6]`, `sp`, and return value (`x0` / `r0` / `rax`) via `PTRACE_GETREGSET(NT_PRSTATUS)` and `PTRACE_SETREGSET(NT_PRSTATUS)` (plus `NT_ARM_SYSTEM_CALL` on `aarch64` when cancelling or altering syscall numbers).
   - *Current state:* `SyscallRegs` has only `nr` + `arg0` (`supervisor.rs:22-25`), and only `read_syscall_regs` exists per-arch (`:128`, `:153`, `:178`). No setter at all.
2. **Implement safe guest memory R/W helpers (`ptrace_mem.rs`):**
   - `read_guest_cstring(pid, addr)` using `process_vm_readv` (fallback to `PTRACE_PEEKDATA`).
   - `write_guest_scratch_cstring(pid, sp, new_path)` allocating temporary space in a safe scratch page mapped inside the guest (preferred over raw below-`sp` writes on multi-threaded processes).
3. **Implement Syscall Entry Path Rewriting (`translate_guest_path`):**
   - The pure function already exists and is unit-tested (`supervisor.rs:85`, tests at `:397`) — it maps `/dev/binder*` → `<rootfs>/dev/binder_proxy` and strips `..`. What's missing is wiring it into the ptrace loop.
   - On `openat(dirfd, pathname, flags, mode)`, `faccessat`, `newfstatat` / `statx`, `readlinkat`, `mkdirat`, `unlinkat`, `renameat2`, `execve` / `execveat`:
     - If `dirfd == AT_FDCWD` or path starts with `/`: canonicalize against `vm_sandboxes/<vmId>/`, write translated host path to guest scratch memory, and update the pointer register before continuing.
     - If path targets `/dev/binder`, `/dev/hwbinder`, or `/dev/vndbinder`: rewrite to the VM's isolated Unix socket / proxy descriptor managed in Phase 3.
     - Intercept `execve` / `execveat` on `/system/bin/*` or `/data/local/tmp/*` to route through the Phase 1 bootstrap (`libkestrel_init.so`) and handle `#!` scripts.
4. **Implement Syscall Exit Spoofing (Virtual Root & Device Identity):**
   - Set `PTRACE_O_TRACESYSGOOD` cleanly on exit for intercepted syscalls (`getuid`, `geteuid`, `getgid`, `getegid`, `getresuid`, `getresgid`, `uname`, `statx`/`fstatat64`, `capget`).
   - When `rootEnabled == true` for the VM: force UID/GID syscalls to return `0` (`uid=0(root)`) and rewrite `st_uid`/`st_gid` to `0` so `su`, `id`, `Toybox`, and guest apps see authentic root ownership inside the jail without touching host kernel credentials.
   - Support `clone` / `fork` / `vfork` (`PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK`) so multi-threaded ART/Java guest apps remain attached under the supervisor loop (replace single-child `waitpid(pid)` with a tracked `HashMap<Pid, ProcessState>` across the VM process tree).
   - *Current state:* `setoptions` uses only `TRACESYSGOOD | TRACESECCOMP | EXITKILL` (`supervisor.rs:302-304`). No fork/clone options, no process map.

### Phase 2 Exit Check
- Running a static guest `/system/bin/sh -c "id && ls /system/build.prop"` inside the supervisor prints `uid=0(root)` and reads `vm_sandboxes/<vmId>/system/build.prop` without seeing host `/data/user/0/...` paths.
- **This is the phase that closes the §2.5 containment gap.**

---

## 4. Phase 3 — Live Userspace Binder Daemon & `servicemanager` (`binder_proxy.rs`)

Currently `BinderRouter` only handles synchronous strings from JNI. Real Android apps communicate continuously via `/dev/binder` `ioctl(BINDER_WRITE_READ)`.

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

### 5.0 ⚠️ PREREQUISITE — fix tar extraction FIRST (survey finding, §0.1)

`extractTarStreamSafe` (`RootfsProvisioner.kt:235`) **will silently corrupt any real AOSP rootfs** because it only handles typeFlags `'5'` and `'0'`. Symlinks and hardlinks are dropped, and no executable bit is ever applied. Do these **before** attempting any rootfs import:

1. **POSIX Permissions (header offset `100..108`, the octal mode field):** parse the octal mode and apply `outFile.setExecutable((mode and 0b001_001_001) != 0, false)` so binaries and scripts retain `+x`. *(Verified against the ustar layout: mode = bytes 100–107, uid = 108–115, size = 124–135, typeflag = 156, linkname = 157–256. The existing code reads name/prefix/size/typeflag but never reads the mode field — that is the gap.)*
2. **Symlinks (Tar `typeFlag == '2'`):** read link target (`header[157..257]`), verify the resolved symlink destination stays strictly inside `rootCanonical`, and create via `android.system.Os.symlink(target, outFile.absolutePath)`. AOSP relies on these heavily (`/system/bin/sh -> toybox`, `/system/lib64/...`).
3. **Hardlinks (Tar `typeFlag == '1'`):** resolve target inside `rootCanonical` and copy/link safely.
4. **Add a regression test** covering a tar with a symlink escaping the root (TarSlip), a symlink staying inside, and a `0755` file retaining `+x`.

### 5.1 A. Rootfs Extraction Fixes in `RootfsProvisioner.kt`
See §5.0. Additionally ensure `.img` import path applies the same permission rules after mounting/extracting.

### 5.2 B. Build a Minimal Bootable AOSP Userspace Archive (`aosp14-arm64-rootfs.tar.gz`) Locally
On your Linux workstation, extract or build from an AOSP `sdk_phone64_arm64-userdebug` / LineageOS / GSI `system.img`:
- **Minimum required layout inside the `.tar.gz`:**
  - `/system/bin/{linker64, sh, toybox, app_process64, servicemanager, surfaceflinger, am, pm}`
  - `/system/lib64/` (`libc.so`, `libm.so`, `libdl.so`, `liblog.so`, `libcutils.so`, `libutils.so`, `libbinder.so`, `libandroid_runtime.so`, `libart.so`, `libhwui.so`, `libEGL.so`, `libGLESv2.so`, plus `hw/hwcomposer.kestrel.so`, `hw/gralloc.kestrel.so`)
  - `/system/framework/` (`boot*.art`, `boot*.oat`, `framework.jar`, `core-oj.jar`, `core-libart.jar`, `services.jar`, `ext.jar`)
  - `/system/etc/` (`ld.config.txt`, `linker.config.json`, `public.libraries.txt`, `fonts.xml`, `/system/fonts/Roboto-Regular.ttf`)
  - `/system/xbin/su`
- Import via **Hardware tab -> Guest AOSP Rootfs Provisioning -> Import Archive** (or host over local HTTPS and pull via **HTTPS URL** with SHA-256 verification).

**Image sourcing — see §9.2 for the researched options.** Recommended: a vanilla, no-GAPPS, arm64 A-B GSI, `system.img` → `tar.gz`.

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

### 7.1 Local Sideload Manifest Changes (Host Machine Only — Do Not Push to Play/Cloud if Publishing)
In `app/src/main/AndroidManifest.xml` (on your local sideload branch/flavor):
- Declare `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` **only** if you want KestrelVM to list and pull APKs already installed on your physical phone (`PackageManager.getInstalledApplications(0)` -> read `appInfo.publicSourceDir` and `appInfo.splitPublicSourceDirs`).
- *(Zero-permission alternative that works everywhere without `QUERY_ALL_PACKAGES`: use the existing **Import APK / File** Document Picker in the **Files** tab, or `adb push` into `/sdcard/Download`, and pull APKs exported via local tools.)*

> Current manifest declares **only** `android.permission.INTERNET`. No `QUERY_ALL_PACKAGES`, no `REQUEST_INSTALL_PACKAGES`.

### 7.2 Upgrade APK Extraction (`SandboxFileStorage.kt`)
Update `stageApkIntoVirtualDataApp(vmId, file)` (currently `SandboxFileStorage.kt:239`):
1. Copy `base.apk` into `vm_sandboxes/<vmId>/data/app/<pkg>/base.apk` (already working).
2. **Unpack APK native `.so` libraries:** Open `base.apk` as a `ZipInputStream`, extract `lib/<vm.abi>/*.so` into `vm_sandboxes/<vmId>/data/app/<pkg>/lib/<abi>/` so the guest's `linker64` can resolve JNI libraries.
3. **Resolve Launchable `MAIN`/`LAUNCHER` Activity:** Parse `AndroidManifest.xml` inside `base.apk` (binary AXML parser or `PackageArchiveInfo` activities) to capture the exact launcher class (e.g., `com.example.app/.MainActivity`) and persist `launchActivity` in `GuestInstalledAppEntity` (bump `AppDatabase` version + add Room migration).
4. **Register package inside guest `/data/system/packages.list` & `/data/system/packages.xml`** so the guest `PackageManagerService` recognizes it identically to `pm install`.

### 7.3 Replace `GuestInstalledApkSandboxView` Info Card With Real Launch (`HypervisorViewModel.kt` & `VirtualGuestDisplay.kt`)
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

# 0. Sanity: gradlew must be executable
[ -x ./gradlew ] || chmod +x ./gradlew

# 1. Build Rust Sandbox + Bootstrap Init (.so) with 16 KB alignment
#    NOTE: -P 24, NOT -p 24 — cargo-ndk >= 4.0 repurposed -p as --package.
(cd native/kestrel_sandbox_rust && cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -P 24 \
  -o ../../app/src/main/jniLibs \
  build --release)

# 2. Build C++17 Virtual HAL (.so) AND kestrel_init bootstrap (.so)
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
  cp "$BUILD_DIR/libkestrel_hal.so"    "app/src/main/jniLibs/$ABI/libkestrel_hal.so"
  cp "$BUILD_DIR/libkestrel_init.so"   "app/src/main/jniLibs/$ABI/libkestrel_init.so"   # Phase 1
done

# 3. Strip (keeps repo small per gameplan.md §5 rule 4; exports survive)
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for f in app/src/main/jniLibs/*/*.so; do "$NDK_BIN/llvm-strip" --strip-unneeded "$f"; done

# 4. Verify JNI Exports & 16 KB PT_LOAD Alignment
#    Expected: sandbox = 6 Java_ exports, hal = 10 Java_ exports, kestrel_init = 0 (it's an executable)
NM="$NDK_BIN/llvm-nm"
READELF="$NDK_BIN/llvm-readelf"
for ABI in arm64-v8a armeabi-v7a x86_64; do
  echo "$ABI: sandbox=$("$NM" -D --defined-only app/src/main/jniLibs/$ABI/libkestrel_sandbox.so | grep -c ' Java_') \
hal=$("$NM" -D --defined-only app/src/main/jniLibs/$ABI/libkestrel_hal.so | grep -c ' Java_') \
init_type=$("$READELF" -hW app/src/main/jniLibs/$ABI/libkestrel_init.so | awk '/Type:/{print $2}')"
  "$READELF" -lW app/src/main/jniLibs/$ABI/libkestrel_hal.so | awk '/LOAD/{print "  hal align:", $NF}'
done

# 5. Run Rust & Android Tests, Then Install Debug APK on Physical Phone
(cd native/kestrel_sandbox_rust && cargo test --workspace --all-targets)
./gradlew :app:testDebugUnitTest
./gradlew installDebug
adb shell am start -n com.example/.MainActivity
adb logcat -s KestrelInit KestrelSandbox:* KestrelVirtualHAL:* AndroidRuntime:E
```

> **Install gotcha:** if the phone already has `com.example` signed with a different `debug.keystore`, `installDebug` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Fix: `adb uninstall com.example` (wipes app data, including any provisioned rootfs) then reinstall.

### Phase 1 verification checklist (on device) — ✅ PASSED 2026-11-21
1. `nativeloader` shows **two** `.so` files loading `ok` — `libkestrel_sandbox` and `libkestrel_hal`. **There is no nativeloader line for `libkestrel_init`** (it is an executable, never loaded) — see **D7**. ✅
2. Extraction: `libkestrel_init.so` must appear `-rwxr-xr-x` under `<apkdir>/lib/<abi>/` (note: the runtime dir is `lib/arm64`, **not** `lib/arm64-v8a`), and `dumpsys package com.example | grep extractNativeLibs` must read `true`. ✅
3. `adb logcat -s KestrelInit` shows the 12 survey lines + `survey complete ... -> pause()`. ✅
4. Hardware tab → Seccomp-BPF Supervisor card, **third text line down**: `Syscall Traps: N • Path Translations: N • Guest ELF Init: Present`. `State: PTRACE_PID_<pid>`, `Guest ELF Init: Present`, `Syscall Traps` **> 0** (it freezes after `pause()` — **D8**, static is fine, `0` is not). ✅
5. No `UnsatisfiedLinkError` / `AndroidRuntime:E`. ✅

### Build Hygiene Rules (local sideload only — AI Studio cloud rules retired 2026-11-21)

> **Status:** AI Studio is **retired from this project permanently, for policy reasons.** KestrelVM's scope — handing users a fully rooted Android virtual device — is **prohibited by Google Play Store policy**, so no Play-compliant or cloud-compliant build of this app can ever exist. There is therefore nothing to round-trip through AI Studio's Play-checked cloud builds. The last external sync was commit `ed7eb09` ("docs: update v0.2 gameplan for KestrelVM"); it stripped the `gradlew` executable bit on arrival and HEAD `0008776` restored it. That was the final round-trip.
>
> **Distribution is open-source `.apk` released on GitHub for sideloading** — legal, outside Play's ToS entirely, and the only intended channel.
>
> The original 5-rule "AI Studio Cloud Round-Trip Rules" block was audited on 2026-11-21. **Rules 2 and 4 were removed** as Play Store / cloud-parity boilerplate that does not apply to local sideload builds. Rule 4 was also **actively harmful**: its mitigation ("gate `extractNativeLibs`/`useLegacyPackaging` behind a Gradle property defaulting to `false`") would disable native-lib extraction and **break Phase 1 entirely** — `libkestrel_init.so` would never be extracted executable, so the launcher could not be `execve`'d and the whole §2.2 exit check would fail. The rule's own text conceded both flags are "required for Phase 1 to function."

1. **Never commit `local.properties`, `.env`, `debug.keystore`, `.cxx/`, `build/`, or `native/kestrel_sandbox_rust/target/`.** *(Genuine hygiene — all are covered in `.gitignore`; verified 2026-11-21 that none are tracked in git.)*
2. **Do not alter `signingConfigs` (`release` and `debugConfig`)** in `app/build.gradle.kts`. `debugConfig` is what signs `installDebug` — altering or removing it breaks local install/update, and the release keystore (`my-upload-key.jks`) does not exist, so `assembleRelease` fails by design.
3. **Keep `android:extractNativeLibs="true"` and `useLegacyPackaging = true` in place — do not gate them.** Both are load-bearing for Phase 1 (`§2.3` items 3 and 4).

**OBSOLETE — no longer needed:** re-`chmod +x gradlew` after external pulls was required **only** while AI Studio round-tripped and normalized file modes to `100644`. That happened 4 times across commits `757ea2f`, `33fb3f8`, `ed7eb09`, `0008776`, but AI Studio is now retired (see Status above), so no further mode normalization will occur. The bit is currently correct (`100755` in the index, `-rwxr-xr-x` on disk). Keep `[ -x ./gradlew ] || chmod +x ./gradlew` in the §8 build sequence as a harmless belt-and-braces line.

---

## 9. Open Decisions & Carried-Forward Context

*Read this section first if starting a new session.*

### 9.1 Current status snapshot
- **PHASE 1 (§2) IS COMPLETE.** Implemented and verified on device 2026-11-21 — full record, test output, and deviations in **§2.6**.
- Exit check green: `PTRACE_PID_26921`, `Guest ELF Init: Present`, `Syscall Traps: 200`, survey logged, `libkestrel_init.so` extracted executable.
- Tests: `cargo test` **10/10**, `testDebugUnitTest` **12/12**, `cargo build` 0 warnings. Static checks: 6/10/0 `Java_*` exports, `init` = `DYN`, 16 KB aligns.
- **v0.2.0 `.so` binaries rebuilt, stripped, installed** on SM-S356V (arm64).
- **Variant A chosen** — no guest-authored ELF executes (§2.4 step 5 not implemented).
- **15 files changed**; `gameplan-v0.2.md` additionally modified (this document).
- **NEXT WORK ITEM: Phase 2 (§3)** — ptrace syscall rewrite engine. This is what closes the §2.5 containment gap. Re-plan at the start; do not run 2→7 straight through (§9.3 #1).
- **Backlog (not done):** D10 (`NativeEngineBridge.kt:287` fallback counter copy/paste), D11 (misleading `Path Translations` label).
- **Still true:** use `assembleDebug`/`installDebug`, never `assembleRelease`; `cargo ndk` uses `-P 24`, not `-p 24`.

### 9.2 AOSP rootfs acquisition (Phase 4 input — researched, not yet downloaded)

| Source | Notes |
| :--- | :--- |
| **`download.lineageos.org/gsi`** (official) | Cleanest provenance. Page is JS-rendered — actual `.img` URLs need an API pull when we get there. |
| **GitHub `MisterZtr/LineageOS_gsi`** / **`Doze-off/lineage_treble`** | Actively maintained unofficial Treble GSIs with direct release-asset links, e.g. `LineageOS-22.2-*-VANILLA-EXT4-GSI.7z` (~800 MB–1.2 GB), Android 15 QPR2 / A16 variants. |
| **SourceForge `andyyan-gsi`** | Long-standing phh-based GSIs, clear naming: `arm64_bgN` = arm64 / A-B / GAPPS / No-superuser. |

**Recommendation:** a **vanilla, no-GAPPS, arm64 A-B GSI** (`arm64_bvN`, ~800 MB–1 GB) — smallest download, no Google bits to break, and the `bvN`/`bgN` naming maps onto the layout §5.2 needs. Extract `system.img` → `tar.gz` to match `RootfsProvisioner`'s archive flow rather than shipping the raw image.

### 9.3 Scope decisions already settled (do not re-ask the user)
1. **Phase ordering:** Phase 1 first, then re-plan at the exit check. Not a straight 1→7 run.
2. **Spec fidelity:** this doc is a **roadmap** — follow its phases and goals, but **adapt to what the real code does** (§0.1 is the source of truth) and flag every deviation.
3. **Phase 1 language:** C via the existing CMake project.
4. **Phase 1 loader depth:** static ELFs only; no in-memory dynamic linker (user's safety call).
5. **Rootfs:** user does not yet have an image; they asked for help obtaining one → §9.2.
6. **Distribution channel:** **open-source `.apk` on GitHub, sideload-only.** Google Play Store is **permanently out of scope** — the app's scope (granting users a fully rooted Android VM) is prohibited by Play policy, which also makes AI Studio round-tripping impossible and therefore retired. Do not re-propose Play-compliant flavors, cloud builds, or gating `extractNativeLibs`/`useLegacyPackaging`/permissions behind Gradle properties.

### 9.4 Resolved — Phase 1 scope
**Answer: variant A (safer)** — the launcher surveys and `pause()`s only; no guest-authored code executes until Phase 2 closes the filesystem boundary. Chosen and implemented 2026-11-21.

Variant B (the static ELF loader/jump in §2.4 step 5) remains **available but unimplemented**. Enabling it is a separate change that must be explicitly approved, and should only be considered after Phase 2 lands — §2.5 still describes the containment reality.

### 9.5 Where to start next
1. Read **§2.6** (what was built + deviations D1–D11), then **§3** (Phase 2 spec).
2. Phase 2 work items, mapped to current code reality: `SyscallRegs` still holds only `nr`+`arg0` with no setter; `translate_guest_path` is unit-tested but **not wired into the ptrace loop**; `setoptions` still lacks `TRACECLONE|TRACEFORK|TRACEVFORK`; the loop is a single `waitpid(pid, __WALL)` with **no `HashMap<Pid, ProcessState>`**; the supervisor still only logs and resumes (`supervisor.rs` PtraceEvent arm).
3. The new `TRACE_CORE` entries `chdir`, `fchdir`, `getcwd`, `newfstatat`/`fstatat64`, `statx` were **added specifically as Phase 2 hook points** — wire them first.
4. Phase 4 additionally needs D9 resolved (a real AOSP rootfs, §9.2) **and** the §5.0 tar-extraction fixes as a prerequisite.

---

### 9.6 Proposed commits — PLANNED, NOT YET EXECUTED

> **Do not re-derive this.** Working-tree state as of 2026-11-21 end-of-session: **13 modified + 4 untracked = 17 files**, all legitimate Phase 1 work. A pre-flight check confirmed **no item from the §8 never-commit list** (`local.properties`, `.env`, `debug.keystore`, `.cxx/`, `build/`, `native/kestrel_sandbox_rust/target/`) appears in `git status`. `gradlew` is `100755` in the index **and** executable on disk.
>
> **NOT YET COMMITTED.** Run these three in order.

**Commit 1 — `feat(phase1): add kestrel_init bootstrap launcher and wire nativeLibraryDir`**

| File | Note |
| :--- | :--- |
| `native/kestrel_hal_cpp/kestrel_init.c` | **new** (161 lines, variant A: survey + `pause()`, no guest ELF jump) |
| `native/kestrel_hal_cpp/CMakeLists.txt` | added `C` language + `kestrel_init` target (D1) |
| `native/kestrel_sandbox_rust/src/seccomp.rs` | `LAUNCHER_ALLOW` sets + `TRACE_CORE` 11→16 (D5, D6) |
| `native/kestrel_sandbox_rust/src/lib.rs` | 4-arg JNI, `-3` parse failure |
| `native/kestrel_sandbox_rust/src/supervisor.rs` | 4-arg `spawn_supervisor`, candidate order (D4), pre-fork CStrings (D3) |
| `app/src/main/AndroidManifest.xml` | `extractNativeLibs="true"` |
| `app/build.gradle.kts` | `packaging { jniLibs { useLegacyPackaging = true } }` |
| `app/src/main/java/com/example/engine/NativeEngineBridge.kt` | `bootSandbox(..., nativeLibDir: String = "")` |
| `app/src/main/java/com/example/ui/HypervisorViewModel.kt` | `nativeLibraryDir ?: ""` |
| `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libkestrel_sandbox.so` | rebuilt (3 files) |
| `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libkestrel_init.so` | **new** (3 files) |

**15 files.** Commit the rebuilt `.so`s **with** their source — splitting them leaves the tree inconsistent.

> **`libkestrel_hal.so` is deliberately absent:** byte-identical to `HEAD` on all 3 ABIs (verified with `git diff --quiet`). The HAL source never changed; it only appeared modified pre-strip. Do not add it.

**Commit 2 — `chore: ignore native CMake build output`**

| File | Note |
| :--- | :--- |
| `.gitignore` | adds `native/kestrel_hal_cpp/build/` (audit-found gap — root `/build` and `app/build` were already covered, a `build/` under `native/` was not) |

**Commit 3 — `docs(gameplan): record Phase 1 results, D1-D11 deviations, retire AI Studio cloud rules`**

| File | Note |
| :--- | :--- |
| `gameplan-v0.2.md` | Revision 3: §0 baseline row, **§0.2 D1–D11**, §2.3 marked done, **§2.6 completion record**, §8 checklist ✅ PASSED, §8 rules 5→3 + scope rationale, §9.1/§9.3/§9.4/§9.5 |

*(Alternative if a 2-commit history is preferred: fold Commit 2 into Commit 1. Keep Commit 3 separate — code and doc review differently.)*

**Post-commit sanity:** `git status --porcelain` should return **empty**. If anything remains, investigate before considering the session closed.

**Suggested body text for Commit 1:**
```
Phase 1: make the guest bootstrap launcher reachable and survivable.

The launcher could not run before this change: filesDir is noexec
(SECCOMP/SELinux app_data_file), so it is packaged as an executable
ET_DYN emitted from libkestrel_init.so and extracted into
applicationInfo.nativeLibraryDir via extractNativeLibs +
useLegacyPackaging. The supervisor then prefers that path as its first
guest-init candidate, which is what turns the exit check from
ARMED_AWAITING_INIT into PTRACE_PID_<pid>.

The seccomp filter's default action is KILL_PROCESS and the old
ALLOW_FAST set held 9 syscalls, so prctl/chdir/realpath/exit_group
would have SIGSYS'd the process before main. Added a header-verified
LAUNCHER_ALLOW set covering bionic libc startup, the launcher, and
liblog, while keeping ptrace/clone/mount/chroot/bpf and friends
denied. Filesystem-navigation syscalls (chdir, fchdir, getcwd,
fstatat, statx) went into TRACE rather than ALLOW specifically so
Phase 2 can rewrite them.

Safer variant A: the launcher surveys and pause()s only. No
guest-authored ELF executes until Phase 2 closes the containment gap
(gameplan-v0.2.md §2.5).
```
