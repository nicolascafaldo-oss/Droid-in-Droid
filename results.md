# Results — `gameplan.md` execution log

**Status: Phases A–D complete and pushed to `origin/main`.** Phase E (gameplan §6, out-of-process
guest execution) deliberately deferred.

Read this before touching the build. Most of the setup lives *outside* the repo, so a fresh clone
will not work without it.

---

## 1. Environment (machine-local — NOT in the repo)

| Tool | Path | Notes |
| :-- | :-- | :-- |
| Android SDK | `~/Android/Sdk` | platform 36 **+ 36.1**, build-tools 36.0.0, platform-tools |
| NDK | `~/Android/Sdk/ndk/27.2.12479018` | r27c |
| CMake | `~/Android/Sdk/cmake/3.22.1` | |
| JDK 21 | `~/.local/share/toolchains/jdk-21` | Temurin 21.0.13.1 |
| Rust | `~/.cargo/bin` | rustc 1.100.0, cargo-ndk 4.1.2, 3 Android targets |

Wired in via two **untracked** files (kept out of git per gameplan §5 rule 3):

- `local.properties` → `sdk.dir=/home/anon/Android/Sdk`
- `~/.gradle/gradle.properties` → `org.gradle.java.home=/home/anon/.local/share/toolchains/jdk-21`

Shell setup needed for native builds:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"
export PATH="$HOME/.cargo/bin:$PATH"
```

---

## 2. What was done, and why

### Phase A — toolchain bootstrap

| Done | Why it was necessary |
| :-- | :-- |
| Generated `debug.keystore` at repo root | `app/build.gradle.kts` signs `debug` with it; absent → `:app:validateSigningDebug` fails. Already gitignored. |
| **Bootstrapped the Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | **Repo bug.** Only `gradle-wrapper.properties` existed, so *every* `./gradlew` command in `gameplan.md` would have died with "No such file or directory". Regenerated with `gradle wrapper --gradle-version 9.3.1`. |
| Installed Android SDK via cmdline-tools | Nothing was installed; `ANDROID_HOME` unset. AGP auto-downloaded platform **36.1** for `minorApiLevel = 1` — no manual action needed. |
| Installed Rust + cargo-ndk + 3 Android targets | Not present at all. |
| Created `.env` from `.env.example` | Secrets plugin expects it (falls back to the example anyway). |

**JDK blocker:** the system OpenJDK 25 is a **JRE with no `javac`** — Gradle failed with
`Toolchain installation '/usr/lib/jvm/java-25-openjdk' does not provide the required capabilities: [JAVA_COMPILER]`.
No passwordless sudo, so `dnf` was out. Fixed by installing Temurin 21 and pointing Gradle at it
from `~/.gradle/gradle.properties` (outside the repo, so nothing machine-local leaked into git).

**`/tmp` is tmpfs (RAM-backed)** and clears on reboot. The SDK was initially installed there, which
would have silently broken `local.properties` after a restart — moved to `~/Android/Sdk`, the
gameplan's own Linux convention.

### Phase B — Kotlin verification

`./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**; `assembleDebug`; `installDebug`; app boots
Choose/Load VM → immersive guest launcher.

**Use `assembleDebug`, never `assembleRelease` locally** — `my-upload-key.jks` does not exist and
`KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` are unset, so release signing will fail.

### Phase C — native builds

Built straight into `app/src/main/jniLibs/` (no `build.gradle.kts` edits, per §5 rule 1):

```bash
# Rust — MUST be run from inside the crate directory
cd native/kestrel_sandbox_rust
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 24 \
  -o "$PWD/../../app/src/main/jniLibs" build --release
```

> `cargo-ndk` still runs `cargo metadata` from the *current* directory and ignores
> `--manifest-path` for that step — passing `--manifest-path` from the repo root fails with
> "could not find Cargo.toml". Hence the `cd`.

```bash
# C++ HAL — run from repo root
CMAKE_BIN="$(ls -d "$ANDROID_HOME"/cmake/*/bin/cmake | sort -V | tail -n 1)"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
for ABI in arm64-v8a armeabi-v7a x86_64; do
  BUILD_DIR=".cxx/kestrel_hal/$ABI"
  "$CMAKE_BIN" -S native/kestrel_hal_cpp -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
  "$CMAKE_BIN" --build "$BUILD_DIR" --config Release
  cp "$BUILD_DIR/libkestrel_hal.so" "app/src/main/jniLibs/$ABI/"
done
```

AGP could not strip the `.so`s ("Unable to strip the following libraries") because `ndkVersion`
is not set — stripped manually instead: **4.13 MB → 2.0 MB** with `llvm-strip --strip-all`, all
7 JNI symbols verified intact afterwards.

**JNI symbol audit (do this after any native change):** all 4 Rust + 3 C++ exports must match the
`external fun` declarations in `NativeEngineBridge.kt`.

```bash
NM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
"$NM" --dynamic --defined-only app/src/main/jniLibs/arm64-v8a/libkestrel_sandbox.so | grep Java_
"$NM" --dynamic --defined-only app/src/main/jniLibs/arm64-v8a/libkestrel_hal.so    | grep Java_
```

### Phase D — git / AI Studio round trip

Three commits, pushed `cc6d159..ca2f47c`:

| Commit | Contents |
| :-- | :-- |
| `394bb39` `build: bootstrap Gradle wrapper` | `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` |
| `a4290b3` `feat(native): add prebuilt … (3 ABIs)` | 6 × `.so` + `Cargo.lock` |
| `ca2f47c` `chore: ignore Rust target/ build directory` | `.gitignore` |

**`.gitignore` fix:** `cargo-ndk` wrote **204 MB / 704 files** under
`native/kestrel_sandbox_rust/target/` and there was no `target/` rule — a plain `git add -A` would
have committed the whole build tree.

Gameplan §5 re-verified after pushing:

| Rule | Result |
| :-- | :-- |
| §5.1 no `externalNativeBuild` | ✅ 0 occurrences |
| §5.2 `signingConfigs` untouched | ✅ all 4 markers present |
| §5.3 no secrets / machine paths tracked | ✅ 0 (`local.properties`, `.env`, `debug.keystore`, `.cxx`, `build/` all ignored) |
| §5.4 `.so` committed for cloud packaging | ✅ 6 files |

---

## 3. Verification evidence (physical device)

Device: **Samsung Galaxy A35 5G** (`SM-S356V`), Android 16 (API 36), `arm64-v8a`.

Gameplan §4 success string reproduced **exactly** on the Hardware tab:

```
JNI Bridge: kestrel_sandbox-rs/0.1.0 (seccomp-bpf + binder-proxy) | kestrel_hal-cpp17/0.1.0 (hwcomposer + gralloc + sensors)
```

Zero fallback banners. Corroborating logcat:

```
nativeloader: Load .../lib/arm64-v8a/libkestrel_sandbox.so ...: ok
nativeloader: Load .../lib/arm64-v8a/libkestrel_hal.so ...: ok
KestrelVirtualHAL: HWC profile updated for vm-aosp14-dev: 1080x2340 @ 420dpi (120Hz)
```

The last line is our C++ executing on-device — only reachable when `isCppHalLoaded == true`, so it
proves the Kotlin → JNI → C++ round-trip end to end. Live telemetry also confirmed: `Mali-G68`,
per-core frequencies, RAM, and IMU accelerometer/lux readings.

**UI-automation notes** (for anyone driving the phone via `adb`):

- The phone has a **pattern lock** — a human must unlock it.
- The guest's nav buttons overlap the app's bottom tab row; tap the Hardware tab at **(907, 2191)**
  (its clickable parent is `[735,2124][1080,2259]`, above the guest buttons at y≥2197).
- The device sleeps quickly. `adb shell settings put global stay_on_while_plugged_in 3` prevents it.
- A debug-build "Android App Compatibility / 16 KB" dialog reappears each launch; **OK** is at
  **(243, 2076)**.
- `/proc/<pid>/maps` is unreadable to `shell`; use `adb shell run-as com.example cat /proc/<pid>/maps`.
  Prefer logcat `nativeloader` lines — they are unambiguous.

---

## 4. Known issues / follow-ups

1. **16 KB page alignment (real, unfixed).** The device flagged:
   `lib/arm64-v8a/libkestrel_hal.so : LOAD segment not aligned`. Harmless here (4 KB pages) but
   required for Google Play (targetSdk 35+) and future 16 KB devices.
   *Fix:* add `-Wl,-z,max-page-size=16384` to the CMake link flags and the matching Rust
   `-C link-arg`. **Left alone deliberately** — it needs `app/build.gradle.kts` edits, which
   gameplan §5 forbids doing carelessly. Do it as its own reviewed change.
   Note `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` are *also* flagged, so
   this is partly an AGP/dependency issue, not only ours.
2. **Untested from §3:** two-way file transfer, and the `Vol+ + Vol- + Power` chord (§7 says stock
   ROMs intercept `KEYCODE_POWER`; needs an external keyboard).
3. **§6 milestones** (seccomp/ptrace supervisor, `ANativeWindow` surface + AAudio HAL, rootfs
   provisioning) not started.
4. **Cosmetic:** `cargo` warns that `nix` and `android_logger` are unused deps in
   `native/kestrel_sandbox_rust/Cargo.toml`. Expected — §6 will use them. Do not remove.

---

## 5. One-shot rebuild

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"
export PATH="$HOME/.cargo/bin:$PATH"

./gradlew :app:testDebugUnitTest          # host tests
./gradlew assembleDebug                   # APK (never assembleRelease locally)
./gradlew installDebug                    # needs an unlocked, USB-debugging-authorized phone
```
