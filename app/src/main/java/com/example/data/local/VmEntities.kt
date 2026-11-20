package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class VmRunState(val label: String) {
    STOPPED("STOPPED"),
    BOOTING("BOOTING"),
    RUNNING("RUNNING"),
    PAUSED("PAUSED")
}

@Entity(tableName = "vm_instances")
data class VmInstanceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val guestOsVersion: String,
    val apiLevel: Int,
    val abi: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    val refreshRateHz: Int,
    val allocatedCores: Int,
    val allocatedRamMb: Int,
    val eglBridgeEnabled: Boolean,
    val sensorPassthroughEnabled: Boolean,
    val audioBridgeEnabled: Boolean,
    val binderProxyEnabled: Boolean,
    val seccompMode: String,
    val runState: String,
    val accentTag: String,
    val bootloaderUnlocked: Boolean = true,
    val rootEnabled: Boolean = true,
    val uptimeSeconds: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "vm_sandbox_files")
data class VmSandboxFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val vmId: String,
    val fileName: String,
    val virtualPath: String,
    val hostRelativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: String,
    val apkPackageName: String? = null,
    val apkVersionName: String? = null,
    val apkTargetSdk: Int? = null,
    val importedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "guest_installed_apps")
data class GuestInstalledAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val vmId: String,
    val packageName: String,
    val appName: String,
    val versionName: String,
    val targetSdk: Int,
    val apkVirtualPath: String,
    val apkHostPath: String,
    val iconHostPath: String? = null,
    val permissionsSummary: String = "",
    val activitiesCount: Int = 1,
    val isSystemApp: Boolean = false,
    val pinnedToHome: Boolean = true,
    val installedAt: Long = System.currentTimeMillis()
)
