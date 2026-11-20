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
            languages = "Rust (nix, libc, ndk)",
            artifactName = "libkestrel_sandbox.so",
            summary = "Unprivileged userspace container engine intercepting guest Linux syscalls via seccomp-bpf + ptrace and isolating Binder IPC.",
            responsibilities = listOf(
                "seccomp-bpf filter traps openat/faccessat/statx for rootfs path translation",
                "Userspace Binder proxy isolates guest servicemanager from host /dev/binder",
                "PID/UID/GID namespace emulation without requiring root or CLONE_NEWPID",
                "Memory-safe parcel register decoding across guest/host boundary"
            ),
            interfaceSnippet = """
                // Rust (libkestrel_sandbox.so) JNI Entry Contract
                #[no_mangle]
                pub extern "system" fn Java_com_example_engine_NativeBridge_bootContainer(
                    mut env: JNIEnv,
                    _cls: JClass,
                    vm_id: JString,
                    rootfs_dir: JString,
                    seccomp_mode: jint,
                ) -> jint {
                    // 1. Install PR_SET_NO_NEW_PRIVS + seccomp-bpf syscall trap
                    // 2. Spawn guest /init under ptrace path-translation supervisor
                    // 3. Bind unix domain socket for userspace /dev/binder_proxy
                    0
                }
            """.trimIndent()
        ),
        CleanRoomLayerSpec(
            layerNumber = 2,
            title = "Guest ROM & Virtual HAL Layer",
            languages = "C++17 & AOSP Java",
            artifactName = "hwcomposer.kestrel.so / gralloc.kestrel.so",
            summary = "Virtualizes AOSP HIDL/AIDL HALs so guest SurfaceFlinger, SensorService, and AudioFlinger stream buffers directly to the host bridge.",
            responsibilities = listOf(
                "hwcomposer + gralloc allocate AHardwareBuffer / ANativeWindow frames",
                "Patched WindowManagerService honors dynamic spoofed WxH, DPI, and Hz",
                "sensors.kestrel.so forwards host IMU packets over lockless ring buffer",
                "Strips host-only daemons (vold, netd, RIL) inside guest init.rc"
            ),
            interfaceSnippet = """
                // C++ Virtual HAL (hwcomposer.kestrel.so)
                class KestrelComposerClient : public BnComposerClient {
                public:
                    ndk::ScopedAStatus setSpoofedDisplayConfig(
                        int64_t displayId, int32_t widthPx, int32_t heightPx,
                        int32_t dpi, int32_t refreshHz) override;
                    ndk::ScopedAStatus presentDisplay(
                        int64_t displayId,
                        ndk::ScopedFileDescriptor* outFence) override;
                };
            """.trimIndent()
        ),
        CleanRoomLayerSpec(
            layerNumber = 3,
            title = "Host Hypervisor & Hardware Bridge",
            languages = "Kotlin + Jetpack Compose",
            artifactName = "KestrelVM Host APK",
            summary = "Runs in-process display compositing, System Picture-in-Picture floating windows, SAF two-way file vault, and live sensor passthrough.",
            responsibilities = listOf(
                "System PiP (PictureInPictureParams) + in-app draggable mini viewport",
                "Zero-permission SAF & Photo Picker two-way file transfer into VM rootfs",
                "Real SensorManager IMU, CPU core, RAM, and Choreographer frame bridge",
                "Room-backed multi-instance lifecycle, snapshots, and DPI testbed"
            ),
            interfaceSnippet = """
                // Kotlin Host JNI & Hardware Bridge Contract
                object HostHardwareBridge {
                    fun pushDisplayProfile(vmId: String, width: Int, height: Int, dpi: Int, hz: Int)
                    fun pushSensorFrame(vmId: String, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float)
                    fun syncSandboxPath(vmId: String, virtualPath: String, hostFile: File)
                }
            """.trimIndent()
        )
    )
}
