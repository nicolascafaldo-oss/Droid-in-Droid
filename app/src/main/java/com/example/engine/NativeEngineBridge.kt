package com.example.engine

import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI bridge to the Rust syscall/seccomp/Binder sandbox engine (`libkestrel_sandbox.so`)
 * and C++ AOSP virtual HAL bridge (`libkestrel_hal.so`).
 *
 * Every v0.2 native entry point guards against `UnsatisfiedLinkError` so the host APK
 * remains compatible both in JVM/SDK-only environments and while an older v0.1 prebuilt
 * `.so` is temporarily packaged prior to a local native rebuild.
 */
object NativeEngineBridge {

    val isRustSandboxLoaded: Boolean
    val isCppHalLoaded: Boolean

    private data class FallbackSandboxState(
        val vmId: String,
        val rootfsDir: String,
        val seccompMode: Int,
        var running: Boolean,
        var pathTranslations: Long = 0L,
        var binderTx: Long = 0L,
        var blockedEscapes: Long = 0L,
        val services: MutableMap<String, Int> = linkedMapOf(
            "SurfaceFlinger" to 1,
            "activity" to 2,
            "audio" to 3,
            "display" to 4,
            "input" to 5,
            "package" to 6,
            "sensorservice" to 7,
            "window" to 8
        )
    )

    private data class FallbackHalState(
        var surfaceAttached: Boolean = false,
        var framesPresented: Long = 0L,
        var audioActive: Boolean = false,
        var audioSampleRate: Int = 48000,
        var audioFramesWritten: Long = 0L,
        var width: Int = 1080,
        var height: Int = 2340,
        var hz: Int = 120
    )

    private val fallbackSandboxes = ConcurrentHashMap<String, FallbackSandboxState>()
    private val fallbackHals = ConcurrentHashMap<String, FallbackHalState>()
    private val fallbackHandleSeq = AtomicLong(16L)

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

    fun seccompModeToCode(modeLabel: String): Int = when {
        modeLabel.contains("PTRACE_SYSCALL", ignoreCase = true) -> 0
        modeLabel.contains("USER_LAND_NS", ignoreCase = true) -> 2
        else -> 1
    }

    /**
     * Boots a sandbox VM.
     *
     * @param nativeLibDir the host's `applicationInfo.nativeLibraryDir`, from which the native
     * side locates `libkestrel_init.so`; empty string means "unknown, let the native side fall
     * back to the rootfs search".
     */
    fun bootSandbox(
        vmId: String,
        rootfsDir: String,
        seccompMode: Int,
        nativeLibDir: String = "",
    ): Int {
        fallbackSandboxes[vmId] = FallbackSandboxState(
            vmId = vmId,
            rootfsDir = rootfsDir,
            seccompMode = seccompMode,
            running = true
        )
        return if (isRustSandboxLoaded) {
            try {
                nativeBootSandbox(vmId, rootfsDir, nativeLibDir, seccompMode)
            } catch (_: UnsatisfiedLinkError) {
                0
            }
        } else {
            0
        }
    }

    fun stopSandbox(vmId: String): Int {
        fallbackSandboxes[vmId]?.running = false
        stopAudioStream(vmId)
        detachSurface(vmId)
        return if (isRustSandboxLoaded) {
            try {
                nativeStopSandbox(vmId)
            } catch (_: UnsatisfiedLinkError) {
                0
            }
        } else {
            0
        }
    }

    fun translateGuestPath(vmId: String, guestPath: String): String {
        fallbackSandboxes[vmId]?.let { it.pathTranslations += 1 }
        if (isRustSandboxLoaded) {
            try {
                return nativeTranslatePath(vmId, guestPath)
            } catch (_: UnsatisfiedLinkError) {
                // Fallback below
            }
        }
        val root = (fallbackSandboxes[vmId]?.rootfsDir ?: "sandbox_rootfs/$vmId").trimEnd('/')
        val trimmed = guestPath.trim()
        if (trimmed == "/dev/binder" || trimmed == "/dev/hwbinder" || trimmed == "/dev/vndbinder") {
            return "$root/dev/binder_proxy"
        }
        val sanitized = trimmed.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
        return if (sanitized.isEmpty()) root else "$root/$sanitized"
    }

    fun transactBinder(
        vmId: String,
        serviceName: String,
        code: Int,
        payload: String = ""
    ): BinderTransactionResult {
        if (isRustSandboxLoaded) {
            try {
                val raw = nativeBinderTransact(vmId, serviceName, code, payload)
                val obj = JSONObject(raw)
                return BinderTransactionResult(
                    status = obj.optInt("status", 0),
                    handle = obj.optInt("handle", 0),
                    isolatedNode = obj.optString("node", "/dev/binder_proxy/$vmId"),
                    response = obj.optString("response", raw),
                    nativeBacked = true
                )
            } catch (_: UnsatisfiedLinkError) {
                // Prebuilt v0.1 .so lacks nativeBinderTransact -> use clean fallback below
            } catch (_: Throwable) {
                // Malformed JSON fallback below
            }
        }

        val state = fallbackSandboxes.getOrPut(vmId) {
            FallbackSandboxState(vmId, "sandbox_rootfs/$vmId", 1, true)
        }
        val trimmed = serviceName.trim()
        val isolatedNode = "/dev/binder_proxy/$vmId"
        if ((trimmed.startsWith("/dev/binder") && !trimmed.contains("binder_proxy")) ||
            trimmed.startsWith("/dev/hwbinder") ||
            trimmed.startsWith("/dev/vndbinder")
        ) {
            state.blockedEscapes += 1
            return BinderTransactionResult(
                status = -1,
                handle = 0,
                isolatedNode = isolatedNode,
                response = "EACCES: blocked direct host binder node '$trimmed' -> use isolated '$isolatedNode'",
                nativeBacked = false
            )
        }

        state.binderTx += 1
        return when (code) {
            1 -> BinderTransactionResult(
                0,
                0,
                isolatedNode,
                "PONG vm=$vmId node=$isolatedNode services=${state.services.size} tx=${state.binderTx}",
                false
            )
            2 -> {
                val h = state.services[trimmed]
                if (h != null) BinderTransactionResult(0, h, isolatedNode, "FOUND service=$trimmed handle=#$h proxy=$isolatedNode", false)
                else BinderTransactionResult(1, 0, isolatedNode, "NAME_NOT_FOUND service=$trimmed", false)
            }
            3 -> {
                val h = state.services.getOrPut(trimmed) { fallbackHandleSeq.getAndIncrement().toInt() }
                BinderTransactionResult(0, h, isolatedNode, "REGISTERED service=$trimmed handle=#$h proxy=$isolatedNode", false)
            }
            4 -> BinderTransactionResult(
                0,
                0,
                isolatedNode,
                state.services.entries.joinToString(", ") { "${it.key}(#${it.value})" },
                false
            )
            else -> BinderTransactionResult(
                0,
                state.services[trimmed] ?: 0,
                isolatedNode,
                "BC_TRANSACTION code=$code target=$trimmed bytes=${payload.length} proxy=$isolatedNode",
                false
            )
        }
    }

    fun getSandboxTelemetry(vmId: String): SandboxTelemetry {
        if (isRustSandboxLoaded) {
            try {
                val raw = nativeGetSandboxTelemetry(vmId)
                val obj = JSONObject(raw)
                return SandboxTelemetry(
                    vmId = obj.optString("vmId", vmId),
                    running = obj.optBoolean("running", false),
                    arch = obj.optString("arch", "arm64"),
                    mode = obj.optString("mode", "SECCOMP_BPF + PTRACE"),
                    supervisorState = obj.optString("supervisorState", "IDLE"),
                    childPid = obj.optInt("childPid", -1),
                    guestInitFound = obj.optBoolean("guestInitFound", false),
                    bpfInstructions = obj.optInt("bpfInstructions", 0),
                    syscallTraps = obj.optLong("syscallTraps", 0L),
                    pathTranslations = obj.optLong("pathTranslations", 0L),
                    binderNode = obj.optString("binderNode", "/dev/binder_proxy/$vmId"),
                    binderTransactions = obj.optLong("binderTransactions", 0L),
                    blockedHostEscapes = obj.optLong("blockedHostEscapes", 0L),
                    registeredServices = obj.optInt("registeredServices", 8),
                    lastTrace = obj.optString("lastTrace", ""),
                    nativeSupervisorActive = true
                )
            } catch (_: UnsatisfiedLinkError) {
                // Prebuilt v0.1 .so lacks nativeGetSandboxTelemetry
            } catch (_: Throwable) {
                // Ignore JSON parse fallback
            }
        }

        val state = fallbackSandboxes[vmId]
        val rootfs = state?.rootfsDir
        val elfInit = rootfs != null && (File(rootfs, "init").exists() || File(rootfs, "system/bin/kestrel_init").exists())
        return SandboxTelemetry(
            vmId = vmId,
            running = state?.running == true,
            arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            mode = when (state?.seccompMode) {
                0 -> "PTRACE_SYSCALL"
                2 -> "USER_LAND_NS"
                else -> "SECCOMP_BPF + PTRACE"
            },
            supervisorState = when {
                state == null -> "IDLE"
                !state.running -> "STOPPED"
                isRustSandboxLoaded -> "SO_v0.1_LOADED (Rebuild .so for v0.2 ptrace)"
                else -> "KOTLIN_USERSPACE_FALLBACK"
            },
            childPid = -1,
            guestInitFound = elfInit,
            bpfInstructions = if (state?.seccompMode == 2) 29 else 26,
            syscallTraps = state?.pathTranslations ?: 0L,
            pathTranslations = state?.pathTranslations ?: 0L,
            binderNode = "/dev/binder_proxy/$vmId",
            binderTransactions = state?.binderTx ?: 0L,
            blockedHostEscapes = state?.blockedEscapes ?: 0L,
            registeredServices = state?.services?.size ?: 8,
            lastTrace = "Waiting for local v0.2 cargo-ndk / CMake .so rebuild",
            nativeSupervisorActive = false
        )
    }

    fun pushDisplayProfile(vmId: String, widthPx: Int, heightPx: Int, dpi: Int, refreshHz: Int) {
        val hal = fallbackHals.getOrPut(vmId) { FallbackHalState() }
        hal.width = widthPx
        hal.height = heightPx
        hal.hz = refreshHz
        if (isCppHalLoaded) {
            try {
                nativePushDisplayProfile(vmId, widthPx, heightPx, dpi, refreshHz)
            } catch (_: UnsatisfiedLinkError) {
            }
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
            try {
                nativePushSensorSample(vmId, ax, ay, az, gx, gy, gz)
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    fun attachSurface(vmId: String, surface: Surface): Int {
        fallbackHals.getOrPut(vmId) { FallbackHalState() }.surfaceAttached = true
        return if (isCppHalLoaded) {
            try {
                nativeAttachSurface(vmId, surface)
            } catch (_: UnsatisfiedLinkError) {
                1
            }
        } else {
            1
        }
    }

    fun detachSurface(vmId: String) {
        fallbackHals[vmId]?.surfaceAttached = false
        if (isCppHalLoaded) {
            try {
                nativeDetachSurface(vmId)
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    fun blitGuestFrame(vmId: String, frameIndex: Int, accentRgb: Int): Int {
        val hal = fallbackHals.getOrPut(vmId) { FallbackHalState() }
        if (isCppHalLoaded) {
            try {
                val rc = nativeBlitGuestFrame(vmId, frameIndex, accentRgb)
                if (rc == 0) {
                    hal.framesPresented += 1
                    return 0
                }
            } catch (_: UnsatisfiedLinkError) {
                // Fall back cleanly
            }
        }
        hal.framesPresented += 1
        return 1
    }

    fun startAudioStream(vmId: String, sampleRateHz: Int = 48000): Int {
        val hal = fallbackHals.getOrPut(vmId) { FallbackHalState() }
        hal.audioActive = true
        hal.audioSampleRate = sampleRateHz
        return if (isCppHalLoaded) {
            try {
                nativeStartAudioStream(vmId, sampleRateHz)
            } catch (_: UnsatisfiedLinkError) {
                1
            }
        } else {
            1
        }
    }

    fun playAudioPulse(vmId: String, freqHz: Int = 528, durationMs: Int = 90): Int {
        val hal = fallbackHals.getOrPut(vmId) { FallbackHalState() }
        if (!hal.audioActive) {
            startAudioStream(vmId, hal.audioSampleRate)
        }
        if (isCppHalLoaded) {
            try {
                val frames = nativePlayAudioPulse(vmId, freqHz, durationMs)
                if (frames > 0) {
                    hal.audioFramesWritten += frames.toLong()
                    return frames
                }
            } catch (_: UnsatisfiedLinkError) {
            }
        }
        val syntheticFrames = (hal.audioSampleRate * durationMs) / 1000
        hal.audioFramesWritten += syntheticFrames.toLong()
        return syntheticFrames
    }

    fun stopAudioStream(vmId: String) {
        fallbackHals[vmId]?.audioActive = false
        if (isCppHalLoaded) {
            try {
                nativeStopAudioStream(vmId)
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    fun getHalTelemetry(vmId: String): HalTelemetry {
        val fb = fallbackHals[vmId]
        if (isCppHalLoaded) {
            try {
                val raw = nativeGetHalTelemetry(vmId)
                val obj = JSONObject(raw)
                return HalTelemetry(
                    surfaceAttached = obj.optBoolean("surfaceAttached", false),
                    surfaceWidth = obj.optInt("surfaceWidth", 0),
                    surfaceHeight = obj.optInt("surfaceHeight", 0),
                    framesPresented = obj.optLong("framesPresented", 0L),
                    lastBlitUs = obj.optLong("lastBlitUs", 0L),
                    audioActive = obj.optBoolean("audioActive", false),
                    aaudioHardware = obj.optBoolean("aaudioHardware", false),
                    audioSampleRate = obj.optInt("audioSampleRate", 48000),
                    audioFramesWritten = obj.optLong("audioFramesWritten", 0L),
                    displayWidth = obj.optInt("displayWidth", fb?.width ?: 1080),
                    displayHeight = obj.optInt("displayHeight", fb?.height ?: 2340),
                    displayHz = obj.optInt("displayHz", fb?.hz ?: 120),
                    nativeHalActive = true
                )
            } catch (_: UnsatisfiedLinkError) {
            } catch (_: Throwable) {
            }
        }
        return HalTelemetry(
            surfaceAttached = fb?.surfaceAttached == true,
            surfaceWidth = if (fb?.surfaceAttached == true) fb.width else 0,
            surfaceHeight = if (fb?.surfaceAttached == true) fb.height else 0,
            framesPresented = fb?.framesPresented ?: 0L,
            lastBlitUs = 0L,
            audioActive = fb?.audioActive == true,
            aaudioHardware = false,
            audioSampleRate = fb?.audioSampleRate ?: 48000,
            audioFramesWritten = fb?.audioFramesWritten ?: 0L,
            displayWidth = fb?.width ?: 1080,
            displayHeight = fb?.height ?: 2340,
            displayHz = fb?.hz ?: 120,
            nativeHalActive = false
        )
    }

    @JvmStatic
    private external fun nativeGetBridgeVersion(): String

    @JvmStatic
    private external fun nativeBootSandbox(
        vmId: String,
        rootfsDir: String,
        nativeLibDir: String,
        seccompMode: Int,
    ): Int

    @JvmStatic
    private external fun nativeStopSandbox(vmId: String): Int

    @JvmStatic
    private external fun nativeTranslatePath(vmId: String, guestPath: String): String

    @JvmStatic
    private external fun nativeBinderTransact(
        vmId: String,
        serviceName: String,
        code: Int,
        payload: String
    ): String

    @JvmStatic
    private external fun nativeGetSandboxTelemetry(vmId: String): String

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

    @JvmStatic
    private external fun nativeAttachSurface(vmId: String, surface: Surface): Int

    @JvmStatic
    private external fun nativeDetachSurface(vmId: String)

    @JvmStatic
    private external fun nativeBlitGuestFrame(vmId: String, frameIndex: Int, accentRgb: Int): Int

    @JvmStatic
    private external fun nativeStartAudioStream(vmId: String, sampleRateHz: Int): Int

    @JvmStatic
    private external fun nativePlayAudioPulse(vmId: String, freqHz: Int, durationMs: Int): Int

    @JvmStatic
    private external fun nativeStopAudioStream(vmId: String)

    @JvmStatic
    private external fun nativeGetHalTelemetry(vmId: String): String
}
