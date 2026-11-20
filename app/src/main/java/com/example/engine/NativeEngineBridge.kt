package com.example.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * JNI bridge to the Rust syscall/Binder sandbox engine (`libkestrel_sandbox.so`)
 * and C++ AOSP virtual HAL bridge (`libkestrel_hal.so`).
 *
 * When compiled with an external NDK + cargo-ndk toolchain, native shared libraries
 * handle seccomp/ptrace path translation and virtual HAL IPC. In SDK-only builds,
 * calls fall back cleanly to the Kotlin in-process sandbox runtime.
 */
object NativeEngineBridge {

    val isRustSandboxLoaded: Boolean
    val isCppHalLoaded: Boolean

    private val fallbackRoots = ConcurrentHashMap<String, String>()

    init {
        isRustSandboxLoaded = try {
            System.loadLibrary("kestrel_sandbox")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        isCppHalLoaded = try {
            System.loadLibrary("kestrel_hal")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun getRuntimeBridgeSummary(): String {
        val rustLabel = if (isRustSandboxLoaded) {
            runCatching { nativeGetBridgeVersion() }.getOrDefault("libkestrel_sandbox.so (active)")
        } else {
            "Kotlin Userspace Fallback (Rust JNI ready)"
        }
        val halLabel = if (isCppHalLoaded) {
            runCatching { nativeGetHalVersion() }.getOrDefault("libkestrel_hal.so (active)")
        } else {
            "Kotlin HAL Bridge + GLES20 Surface (C++ JNI ready)"
        }
        return "$rustLabel | $halLabel"
    }

    fun bootSandbox(vmId: String, rootfsDir: String, seccompMode: Int): Int {
        fallbackRoots[vmId] = rootfsDir
        return if (isRustSandboxLoaded) {
            nativeBootSandbox(vmId, rootfsDir, seccompMode)
        } else {
            0
        }
    }

    fun stopSandbox(vmId: String): Int {
        return if (isRustSandboxLoaded) {
            nativeStopSandbox(vmId)
        } else {
            0
        }
    }

    fun translateGuestPath(vmId: String, guestPath: String): String {
        if (isRustSandboxLoaded) {
            return nativeTranslatePath(vmId, guestPath)
        }
        val root = fallbackRoots[vmId] ?: "sandbox_rootfs/$vmId"
        val sanitized = guestPath.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
        return if (sanitized.isEmpty()) root else "$root/$sanitized"
    }

    fun pushDisplayProfile(vmId: String, widthPx: Int, heightPx: Int, dpi: Int, refreshHz: Int) {
        if (isCppHalLoaded) {
            nativePushDisplayProfile(vmId, widthPx, heightPx, dpi, refreshHz)
        }
    }

    fun pushSensorSample(
        vmId: String,
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float
    ) {
        if (isCppHalLoaded) {
            nativePushSensorSample(vmId, ax, ay, az, gx, gy, gz)
        }
    }

    @JvmStatic
    private external fun nativeGetBridgeVersion(): String

    @JvmStatic
    private external fun nativeBootSandbox(vmId: String, rootfsDir: String, seccompMode: Int): Int

    @JvmStatic
    private external fun nativeStopSandbox(vmId: String): Int

    @JvmStatic
    private external fun nativeTranslatePath(vmId: String, guestPath: String): String

    @JvmStatic
    private external fun nativeGetHalVersion(): String

    @JvmStatic
    private external fun nativePushDisplayProfile(
        vmId: String,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
        refreshHz: Int
    )

    @JvmStatic
    private external fun nativePushSensorSample(
        vmId: String,
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float
    )
}
