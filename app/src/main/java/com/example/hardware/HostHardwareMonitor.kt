package com.example.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.io.File

data class HostHardwareSnapshot(
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val hostAndroidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val cpuAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
    val allAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
    val coreFreqMhz: List<Int?> = emptyList(),
    val totalRamMb: Long = 0L,
    val availableRamMb: Long = 0L,
    val batteryLevelPct: Int = 100,
    val batteryTempC: Float = 29.0f,
    val isCharging: Boolean = false,
    val gpuRenderer: String = "Hardware EGL/GLES",
    val vulkanSupported: Boolean = false,
    val hardwareAccelerated: Boolean = true
)

data class HostSensorSample(
    val accelX: Float = 0f,
    val accelY: Float = 9.81f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val lux: Float = 180f,
    val hasAccelerometer: Boolean = false,
    val hasGyroscope: Boolean = false
)

class HostHardwareMonitor(private val context: Context) {

    private val cachedGpuRenderer: String by lazy { queryEglRendererName() }
    private val hasVulkan: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    }

    fun observeHardwareTelemetry(intervalMs: Long = 2000L): Flow<HostHardwareSnapshot> = flow {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        while (true) {
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)

            val totalRamMb = memInfo.totalMem / (1024L * 1024L)
            val availRamMb = memInfo.availMem / (1024L * 1024L)

            val coreCount = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until coreCount.coerceAtMost(8)).map { coreIdx ->
                readCpuFreqMhz(coreIdx)
            }

            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 290) ?: 290
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            emit(
                HostHardwareSnapshot(
                    cpuCores = coreCount,
                    coreFreqMhz = freqs,
                    totalRamMb = totalRamMb,
                    availableRamMb = availRamMb,
                    batteryLevelPct = pct,
                    batteryTempC = tempTenths / 10f,
                    isCharging = charging,
                    gpuRenderer = cachedGpuRenderer,
                    vulkanSupported = hasVulkan,
                    hardwareAccelerated = true
                )
            )
            delay(intervalMs)
        }
    }

    fun observeLiveSensors(): Flow<HostSensorSample> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val light = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        var current = HostSensorSample(
            hasAccelerometer = accel != null,
            hasGyroscope = gyro != null
        )
        trySend(current)

        if (sensorManager == null || (accel == null && gyro == null && light == null)) {
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            private var lastEmitMs = 0L

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        current = current.copy(
                            accelX = event.values.getOrElse(0) { 0f },
                            accelY = event.values.getOrElse(1) { 9.81f },
                            accelZ = event.values.getOrElse(2) { 0f }
                        )
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        current = current.copy(
                            gyroX = event.values.getOrElse(0) { 0f },
                            gyroY = event.values.getOrElse(1) { 0f },
                            gyroZ = event.values.getOrElse(2) { 0f }
                        )
                    }
                    Sensor.TYPE_LIGHT -> {
                        current = current.copy(lux = event.values.getOrElse(0) { 180f })
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastEmitMs >= 150L) {
                    lastEmitMs = now
                    trySend(current)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        accel?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        gyro?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        light?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    private fun readCpuFreqMhz(coreIndex: Int): Int? {
        return try {
            val file = File("/sys/devices/system/cpu/cpu$coreIndex/cpufreq/scaling_cur_freq")
            if (!file.exists() || !file.canRead()) return null
            val khz = file.readText().trim().toIntOrNull() ?: return null
            khz / 1000
        } catch (_: Exception) {
            null
        }
    }

    private fun queryEglRendererName(): String {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return "Hardware EGL/GLES20"
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "Hardware EGL/GLES20"

            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfig, 0)
            val config = configs[0] ?: run {
                EGL14.eglTerminate(display)
                return "Hardware EGL/GLES20"
            }
            val ctx = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            val surf = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(display, surf, surf, ctx)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Hardware EGL/GLES20"
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surf)
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
            renderer
        } catch (_: Throwable) {
            "Hardware EGL/GLES20"
        }
    }
}
