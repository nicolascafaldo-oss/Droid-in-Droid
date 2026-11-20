package com.example.data.repository

import android.net.Uri
import com.example.data.local.GuestInstalledAppEntity
import com.example.data.local.SandboxFileStorage
import com.example.data.local.VmDao
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmRunState
import com.example.data.local.VmSandboxFileEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class VmRepository(
    private val vmDao: VmDao,
    private val fileStorage: SandboxFileStorage
) {
    val allInstances: Flow<List<VmInstanceEntity>> = vmDao.observeAllInstances()
    val allFiles: Flow<List<VmSandboxFileEntity>> = vmDao.observeAllFiles()
    val allGuestApps: Flow<List<GuestInstalledAppEntity>> = vmDao.observeAllGuestApps()

    fun getVmRootDir(vmId: String): File = fileStorage.getVmRootDir(vmId)

    suspend fun ensureSeeded(hostCores: Int) {
        if (vmDao.getInstanceCount() > 0) return

        val defaults = listOf(
            VmInstanceEntity(
                id = "vm-aosp14-dev",
                name = "Android 14 (AOSP)",
                guestOsVersion = "Android 14 (API 34)",
                apiLevel = 34,
                abi = "arm64-v8a",
                widthPx = 1080,
                heightPx = 2340,
                dpi = 420,
                refreshRateHz = 120,
                allocatedCores = hostCores.coerceAtLeast(2),
                allocatedRamMb = 4096,
                eglBridgeEnabled = true,
                sensorPassthroughEnabled = true,
                audioBridgeEnabled = true,
                binderProxyEnabled = true,
                seccompMode = "SECCOMP_BPF + PTRACE",
                runState = VmRunState.STOPPED.name,
                accentTag = "CYAN",
                bootloaderUnlocked = true,
                rootEnabled = true
            ),
            VmInstanceEntity(
                id = "vm-aosp10-game",
                name = "Android 10 (AOSP)",
                guestOsVersion = "Android 10 (API 29)",
                apiLevel = 29,
                abi = "arm64-v8a / x86_64",
                widthPx = 720,
                heightPx = 1560,
                dpi = 320,
                refreshRateHz = 90,
                allocatedCores = hostCores.coerceAtLeast(2),
                allocatedRamMb = 3072,
                eglBridgeEnabled = true,
                sensorPassthroughEnabled = true,
                audioBridgeEnabled = true,
                binderProxyEnabled = true,
                seccompMode = "SECCOMP_BPF + PTRACE",
                runState = VmRunState.STOPPED.name,
                accentTag = "EMERALD",
                bootloaderUnlocked = true,
                rootEnabled = true
            ),
            VmInstanceEntity(
                id = "vm-nougat7-legacy",
                name = "Android 7.1.2 (AOSP)",
                guestOsVersion = "Android 7.1.2 (API 25)",
                apiLevel = 25,
                abi = "armeabi-v7a",
                widthPx = 1080,
                heightPx = 1920,
                dpi = 480,
                refreshRateHz = 60,
                allocatedCores = hostCores.coerceAtLeast(2),
                allocatedRamMb = 2048,
                eglBridgeEnabled = true,
                sensorPassthroughEnabled = false,
                audioBridgeEnabled = true,
                binderProxyEnabled = true,
                seccompMode = "PTRACE_SYSCALL",
                runState = VmRunState.STOPPED.name,
                accentTag = "AMBER",
                bootloaderUnlocked = true,
                rootEnabled = true
            )
        )
        vmDao.insertInstances(defaults)
        defaults.forEach { vm ->
            val seededFiles = fileStorage.ensureSandboxProvisioned(vm)
            seededFiles.forEach { vmDao.insertFile(it) }
            seedSystemGuestApps(vm)
        }
    }

    private suspend fun seedSystemGuestApps(vm: VmInstanceEntity) {
        if (vmDao.getGuestAppCount(vm.id) > 0) return
        val systemApps = listOf(
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.android.settings",
                appName = "Settings",
                versionName = vm.apiLevel.toString(),
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/priv-app/Settings/Settings.apk",
                apkHostPath = "${vm.id}/system/priv-app/Settings",
                permissionsSummary = "WRITE_SETTINGS, DEVICE_CONFIG",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.android.documentsui",
                appName = "Files",
                versionName = vm.apiLevel.toString(),
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/priv-app/DocumentsUI/DocumentsUI.apk",
                apkHostPath = "${vm.id}/system/priv-app/DocumentsUI",
                permissionsSummary = "SANDBOX_STORAGE",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.kestrel.term",
                appName = "Terminal",
                versionName = "2.4",
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/app/KestrelTerm/KestrelTerm.apk",
                apkHostPath = "${vm.id}/system/app/KestrelTerm",
                permissionsSummary = "PTRACE_INSPECT, BINDER_TRACE",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.android.calculator2",
                appName = "Calculator",
                versionName = "1.0",
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/app/Calculator/Calculator.apk",
                apkHostPath = "${vm.id}/system/app/Calculator",
                permissionsSummary = "NONE",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.kestrel.notes",
                appName = "Notes",
                versionName = "1.2",
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/app/KestrelNotes/KestrelNotes.apk",
                apkHostPath = "${vm.id}/system/app/KestrelNotes",
                permissionsSummary = "SANDBOX_DOCUMENTS",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.kestrel.egl",
                appName = "EGL GPU",
                versionName = "3.0",
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/app/EglBench/EglBench.apk",
                apkHostPath = "${vm.id}/system/app/EglBench",
                permissionsSummary = "GLES20_BRIDGE, HWC_COMPOSITOR",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.kestrel.dpilab",
                appName = "DPI Lab",
                versionName = "1.5",
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/app/DpiLab/DpiLab.apk",
                apkHostPath = "${vm.id}/system/app/DpiLab",
                permissionsSummary = "DISPLAY_SPOOF",
                isSystemApp = true,
                pinnedToHome = true
            ),
            GuestInstalledAppEntity(
                vmId = vm.id,
                packageName = "com.kestrel.imu",
                appName = "IMU Probe",
                versionName = "1.1",
                targetSdk = vm.apiLevel,
                apkVirtualPath = "/system/app/ImuProbe/ImuProbe.apk",
                apkHostPath = "${vm.id}/system/app/ImuProbe",
                permissionsSummary = "SENSOR_HAL_BRIDGE",
                isSystemApp = true,
                pinnedToHome = true
            )
        )
        vmDao.insertGuestApps(systemApps)
    }

    suspend fun createVmInstance(
        name: String,
        guestOsVersion: String,
        apiLevel: Int,
        abi: String,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
        refreshRateHz: Int,
        allocatedCores: Int,
        allocatedRamMb: Int,
        seccompMode: String,
        accentTag: String
    ): VmInstanceEntity {
        val newVm = VmInstanceEntity(
            id = "vm-${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Custom Guest VM" },
            guestOsVersion = guestOsVersion,
            apiLevel = apiLevel,
            abi = abi,
            widthPx = widthPx,
            heightPx = heightPx,
            dpi = dpi,
            refreshRateHz = refreshRateHz,
            allocatedCores = allocatedCores,
            allocatedRamMb = allocatedRamMb,
            eglBridgeEnabled = true,
            sensorPassthroughEnabled = true,
            audioBridgeEnabled = true,
            binderProxyEnabled = true,
            seccompMode = seccompMode,
            runState = VmRunState.STOPPED.name,
            accentTag = accentTag
        )
        vmDao.insertInstance(newVm)
        val seeded = fileStorage.ensureSandboxProvisioned(newVm)
        seeded.forEach { vmDao.insertFile(it) }
        seedSystemGuestApps(newVm)
        return newVm
    }

    suspend fun updateRunState(vmId: String, state: VmRunState) {
        vmDao.updateRunState(vmId, state.name)
    }

    suspend fun updateDisplayProfile(
        vmId: String,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
        refreshRateHz: Int
    ) {
        vmDao.updateDisplayProfile(vmId, widthPx, heightPx, dpi, refreshRateHz)
        val vm = vmDao.getInstanceById(vmId) ?: return
        val buildProp = fileStorage.resolveHostFile("$vmId/system/build.prop")
        if (buildProp.exists()) {
            buildProp.writeText(
                """
                # KestrelVM Virtual Guest Build Properties
                ro.build.version.release=${vm.guestOsVersion}
                ro.build.version.sdk=${vm.apiLevel}
                ro.product.cpu.abi=${vm.abi}
                ro.sf.lcd_density=$dpi
                persist.kestrel.display.width=$widthPx
                persist.kestrel.display.height=$heightPx
                persist.kestrel.display.refresh_hz=$refreshRateHz
                ro.boot.flash.locked=${if (vm.bootloaderUnlocked) 0 else 1}
                ro.boot.verifiedbootstate=${if (vm.bootloaderUnlocked) "orange" else "green"}
                ro.oem_unlock_supported=1
                ro.debuggable=${if (vm.rootEnabled) 1 else 0}
                ro.secure=${if (vm.rootEnabled) 0 else 1}
                persist.kestrel.root=${if (vm.rootEnabled) 1 else 0}
                ro.hardware.egl=kestrel_egl_bridge
                ro.hardware.gralloc=kestrel_gralloc
                ro.hardware.hwcomposer=kestrel_hwc
                """.trimIndent()
            )
        }
    }

    suspend fun updateInstance(instance: VmInstanceEntity) {
        vmDao.updateInstance(instance)
    }

    suspend fun deleteVmInstance(vmId: String) {
        vmDao.deleteGuestAppsForVm(vmId)
        vmDao.deleteFilesForVm(vmId)
        vmDao.deleteInstanceById(vmId)
        fileStorage.deleteVmSandboxFolder(vmId)
    }

    suspend fun importFile(vmId: String, uri: Uri, forceCategory: String? = null): VmSandboxFileEntity {
        val imported = fileStorage.importUriIntoSandbox(vmId, uri, forceCategory)
        val entity = VmSandboxFileEntity(
            vmId = vmId,
            fileName = imported.fileName,
            virtualPath = imported.virtualPath,
            hostRelativePath = imported.hostRelativePath,
            mimeType = imported.mimeType,
            sizeBytes = imported.sizeBytes,
            category = imported.category,
            apkPackageName = imported.apkPackageName,
            apkVersionName = imported.apkVersionName,
            apkTargetSdk = imported.apkTargetSdk
        )
        val rowId = vmDao.insertFile(entity)
        return entity.copy(id = rowId)
    }

    suspend fun installApkInVm(vmId: String, file: VmSandboxFileEntity): GuestInstalledAppEntity {
        val info = fileStorage.stageApkIntoVirtualDataApp(vmId, file)
        val existing = vmDao.findGuestApp(vmId, info.packageName)
        val entity = GuestInstalledAppEntity(
            id = existing?.id ?: 0L,
            vmId = vmId,
            packageName = info.packageName,
            appName = info.appName,
            versionName = info.versionName,
            targetSdk = info.targetSdk,
            apkVirtualPath = info.apkVirtualPath,
            apkHostPath = info.apkHostPath,
            iconHostPath = info.iconHostPath,
            permissionsSummary = info.permissionsSummary,
            activitiesCount = info.activitiesCount,
            isSystemApp = false,
            pinnedToHome = true
        )
        val id = vmDao.insertGuestApp(entity)
        return entity.copy(id = id)
    }

    suspend fun uninstallGuestApp(app: GuestInstalledAppEntity) {
        if (app.isSystemApp) return
        app.iconHostPath?.let { fileStorage.deleteHostFile(it) }
        fileStorage.deleteHostFile(app.apkHostPath)
        vmDao.deleteGuestAppById(app.id)
    }

    suspend fun saveGuestNote(vmId: String, title: String, body: String): VmSandboxFileEntity {
        val entity = fileStorage.saveGuestNote(vmId, title, body)
        val rowId = vmDao.insertFile(entity)
        return entity.copy(id = rowId)
    }

    suspend fun exportFile(file: VmSandboxFileEntity, destinationUri: Uri): Long =
        fileStorage.exportFileFromSandbox(file.hostRelativePath, destinationUri)

    suspend fun deleteFile(file: VmSandboxFileEntity) {
        fileStorage.deleteHostFile(file.hostRelativePath)
        vmDao.deleteFileById(file.id)
    }

    suspend fun readFilePreview(file: VmSandboxFileEntity): String =
        fileStorage.readTextPreview(file.hostRelativePath)

    fun resolveHostFile(hostRelativePath: String): File =
        fileStorage.resolveHostFile(hostRelativePath)
}
