package com.example.engine

data class DisplayProfilePreset(
    val id: String,
    val label: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    val refreshRateHz: Int,
    val description: String
)

data class SyscallLogEntry(
    val id: Long,
    val vmId: String,
    val vmName: String,
    val timestamp: String,
    val layer: String,
    val syscallOrIpc: String,
    val detail: String,
    val latencyUs: Int
)

data class CleanRoomLayerSpec(
    val layerNumber: Int,
    val title: String,
    val languages: String,
    val artifactName: String,
    val summary: String,
    val responsibilities: List<String>,
    val interfaceSnippet: String
)

data class SandboxTelemetry(
    val vmId: String = "",
    val running: Boolean = false,
    val arch: String = "arm64-v8a",
    val mode: String = "SECCOMP_BPF + PTRACE",
    val supervisorState: String = "IDLE",
    val childPid: Int = -1,
    val guestInitFound: Boolean = false,
    val bpfInstructions: Int = 0,
    val syscallTraps: Long = 0L,
    val pathTranslations: Long = 0L,
    val binderNode: String = "/dev/binder_proxy",
    val binderTransactions: Long = 0L,
    val blockedHostEscapes: Long = 0L,
    val registeredServices: Int = 8,
    val lastTrace: String = "",
    val nativeSupervisorActive: Boolean = false
)

data class BinderTransactionResult(
    val status: Int,
    val handle: Int,
    val isolatedNode: String,
    val response: String,
    val nativeBacked: Boolean
)

data class HalTelemetry(
    val surfaceAttached: Boolean = false,
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    val framesPresented: Long = 0L,
    val lastBlitUs: Long = 0L,
    val audioActive: Boolean = false,
    val aaudioHardware: Boolean = false,
    val audioSampleRate: Int = 48000,
    val audioFramesWritten: Long = 0L,
    val displayWidth: Int = 1080,
    val displayHeight: Int = 2340,
    val displayHz: Int = 120,
    val nativeHalActive: Boolean = false
)

data class RootfsManifest(
    val vmId: String = "",
    val status: String = "UNPROVISIONED",
    val sourceType: String = "BOOTSTRAP_BASE",
    val guestOsVersion: String = "",
    val apiLevel: Int = 34,
    val abi: String = "arm64-v8a",
    val sha256: String = "",
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val elfInitPresent: Boolean = false,
    val bootloaderUnlocked: Boolean = true,
    val rootSuInstalled: Boolean = true,
    val verifiedAtEpochMs: Long = 0L
)

data class RootfsProvisionProgress(
    val vmId: String,
    val phase: String,
    val progress: Float,
    val detail: String
)

object HypervisorCatalog {
    val displayPresets = listOf(
        DisplayProfilePreset(
            id = "pixel_8_pro",
            label = "Pixel 8 Pro",
            widthPx = 1344,
            heightPx = 2992,
            dpi = 480,
            refreshRateHz = 120,
            description = "High-DPI LTPO flagship profile"
        ),
        DisplayProfilePreset(
            id = "galaxy_s24",
            label = "Galaxy S24",
            widthPx = 1080,
            heightPx = 2340,
            dpi = 420,
            refreshRateHz = 120,
            description = "Balanced 1080p+ 120Hz mobile profile"
        ),
        DisplayProfilePreset(
            id = "compact_720p",
            label = "Compact 720p",
            widthPx = 720,
            heightPx = 1280,
            dpi = 320,
            refreshRateHz = 60,
            description = "Low-overhead gaming & legacy scaling"
        ),
        DisplayProfilePreset(
            id = "dense_560",
            label = "Ultra-Dense 560",
            widthPx = 1440,
            heightPx = 3200,
            dpi = 560,
            refreshRateHz = 144,
            description = "QHD+ high-refresh stress profile"
        ),
        DisplayProfilePreset(
            id = "tablet_12l",
            label = "Tablet 12L",
            widthPx = 1600,
            heightPx = 2560,
            dpi = 320,
            refreshRateHz = 90,
            description = "Large-screen sw600dp responsive check"
        )
    )

    val osTemplates = listOf(
        Triple("Android 14 (API 34)", 34, "arm64-v8a"),
        Triple("Android 12L (API 32)", 32, "arm64-v8a"),
        Triple("Android 10 (API 29)", 29, "arm64-v8a / x86_64"),
        Triple("Android 7.1.2 (API 25)", 25, "armeabi-v7a")
    )

    val cleanRoomLayers = listOf(
        CleanRoomLayerSpec(
            layerNumber = 1,
            title = "Syscall & Binder Sandbox Engine",
            languages = "Rust (nix, libc, android_logger)",
            artifactName = "libkestrel_sandbox.so",
            summary = "Unprivileged userspace container engine intercepting guest Linux syscalls via seccomp-bpf + ptrace and isolating Binder IPC.",
            responsibilities = listOf(
                "seccomp-bpf filter checks AUDIT_ARCH & traps openat/faccessat/execve/ioctl",
                "ptrace supervisor inspects NT_PRSTATUS registers across arm64, armv7 & x86_64",
                "Userspace Binder proxy isolates guest servicemanager from host /dev/binder",
                "Canonical path jail blocks '..' directory escapes and host Binder access"
            ),
            interfaceSnippet = """
                // Rust (libkestrel_sandbox.so) JNI Entry Contract
                #[no_mangle]
                pub extern "system" fn Java_com_example_engine_NativeEngineBridge_nativeBootSandbox(
                    mut env: JNIEnv,
                    _cls: JClass,
                    vm_id: JString,
                    rootfs_dir: JString,
                    seccomp_mode: jint,
                ) -> jint
            """.trimIndent()
        ),
        CleanRoomLayerSpec(
            layerNumber = 2,
            title = "Guest ROM & Virtual HAL Layer",
            languages = "C++17 & AOSP Java",
            artifactName = "libkestrel_hal.so (ANativeWindow + AAudio)",
            summary = "Virtualizes AOSP HIDL/AIDL HALs so guest SurfaceFlinger, SensorService, and AudioFlinger stream buffers directly to the host bridge.",
            responsibilities = listOf(
                "ANativeWindow_fromSurface locks RGBA_8888 buffers directly onto Kotlin SurfaceView",
                "AAudio low-latency stream dynamically loaded via libaaudio.so on Android 8+",
                "Dynamic spoofed display profiles push WxH, DPI, and Hz into native HWC state",
                "Sensor HAL ingests host accelerometer & gyroscope IMU frames under mutex lock"
            ),
            interfaceSnippet = """
                // C++ Virtual HAL (libkestrel_hal.so)
                JNIEXPORT jint JNICALL
                Java_com_example_engine_NativeEngineBridge_nativeAttachSurface(
                    JNIEnv* env, jclass, jstring vm_id, jobject surface);
                JNIEXPORT jint JNICALL
                Java_com_example_engine_NativeEngineBridge_nativeBlitGuestFrame(
                    JNIEnv* env, jclass, jstring vm_id, jint frame_index, jint accent_rgb);
            """.trimIndent()
        ),
        CleanRoomLayerSpec(
            layerNumber = 3,
            title = "Host Hypervisor & Rootfs Provisioner",
            languages = "Kotlin + Jetpack Compose",
            artifactName = "KestrelVM Host APK",
            summary = "Manages on-demand AOSP rootfs extraction/verification, System Picture-in-Picture, SAF two-way file vault, and live sensor passthrough.",
            responsibilities = listOf(
                "ZipSlip/TarSlip-safe extraction (.zip, .tar, .tar.gz) & SHA-256 verification",
                "System PiP (PictureInPictureParams) + in-app draggable mini viewport",
                "Zero-permission SAF & Photo Picker two-way file transfer into VM rootfs",
                "Room-backed multi-instance lifecycle, snapshots, and DPI testbed"
            ),
            interfaceSnippet = """
                // Kotlin Host JNI & Rootfs Provisioning Contract
                object NativeEngineBridge {
                    fun attachSurface(vmId: String, surface: Surface): Int
                    fun transactBinder(vmId: String, service: String, code: Int, payload: String)
                }
            """.trimIndent()
        )
    )
}
