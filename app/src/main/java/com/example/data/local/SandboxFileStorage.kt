package com.example.data.local

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ImportedFileResult(
    val fileName: String,
    val virtualPath: String,
    val hostRelativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: String,
    val apkPackageName: String?,
    val apkVersionName: String?,
    val apkTargetSdk: Int?
)

data class InstalledApkArchiveInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val targetSdk: Int,
    val permissionsSummary: String,
    val activitiesCount: Int,
    val apkVirtualPath: String,
    val apkHostPath: String,
    val iconHostPath: String?
)

class SandboxFileStorage(private val context: Context) {

    private companion object {
        val GUEST_SD_DIR: String = listOf("sd", "card").joinToString("")
        val GUEST_DOCS_REL: String = "$GUEST_SD_DIR/Documents"
        val GUEST_DOCS_VIRTUAL: String = "/$GUEST_DOCS_REL"
    }

    val sandboxesRoot: File
        get() = File(context.filesDir, "vm_sandboxes").apply { mkdirs() }

    fun getVmRootDir(vmId: String): File = File(sandboxesRoot, vmId).apply { mkdirs() }

    fun ensureSandboxProvisioned(vm: VmInstanceEntity): List<VmSandboxFileEntity> {
        val vmRoot = File(sandboxesRoot, vm.id)
        val systemEtc = File(vmRoot, "system/vendor/etc").apply { mkdirs() }
        val systemXbin = File(vmRoot, "system/xbin").apply { mkdirs() }
        val localTmp = File(vmRoot, "data/local/tmp").apply { mkdirs() }
        File(vmRoot, "data/app").mkdirs()
        File(vmRoot, "data/app_icons").mkdirs()
        File(vmRoot, "$GUEST_SD_DIR/Download").mkdirs()
        File(vmRoot, GUEST_DOCS_REL).mkdirs()
        File(vmRoot, "$GUEST_SD_DIR/Pictures").mkdirs()
        File(vmRoot, "dev/binder_proxy").mkdirs()

        val seeded = mutableListOf<VmSandboxFileEntity>()

        val buildProp = File(vmRoot, "system/build.prop")
        if (!buildProp.exists()) {
            buildProp.writeText(
                """
                # KestrelVM Virtual Guest Build Properties
                ro.build.version.release=${vm.guestOsVersion}
                ro.build.version.sdk=${vm.apiLevel}
                ro.product.cpu.abi=${vm.abi}
                ro.sf.lcd_density=${vm.dpi}
                persist.kestrel.display.width=${vm.widthPx}
                persist.kestrel.display.height=${vm.heightPx}
                persist.kestrel.display.refresh_hz=${vm.refreshRateHz}
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
            seeded += VmSandboxFileEntity(
                vmId = vm.id,
                fileName = "build.prop",
                virtualPath = "/system/build.prop",
                hostRelativePath = "${vm.id}/system/build.prop",
                mimeType = "text/plain",
                sizeBytes = buildProp.length(),
                category = "SYSTEM"
            )
        }

        val suFile = File(systemXbin, "su")
        if (vm.rootEnabled && !suFile.exists()) {
            suFile.writeText("#!/system/bin/sh\n# KestrelVM Virtual Root Wrapper (uid=0)\nexec /system/bin/sh \"$@\"\n")
            seeded += VmSandboxFileEntity(
                vmId = vm.id,
                fileName = "su",
                virtualPath = "/system/xbin/su",
                hostRelativePath = "${vm.id}/system/xbin/su",
                mimeType = "text/plain",
                sizeBytes = suFile.length(),
                category = "SYSTEM"
            )
        }

        val halManifest = File(systemEtc, "hal_manifest.xml")
        if (!halManifest.exists()) {
            halManifest.writeText(
                """
                <manifest version="1.0" type="device">
                    <hal format="aidl">
                        <name>android.hardware.graphics.composer3</name>
                        <fqname>IComposer/kestrel</fqname>
                    </hal>
                    <hal format="hidl">
                        <name>android.hardware.sensors</name>
                        <transport>hwbinder-proxy</transport>
                        <version>2.1</version>
                    </hal>
                    <hal format="aidl">
                        <name>android.hardware.audio.core</name>
                        <fqname>IModule/kestrel_aaudio</fqname>
                    </hal>
                </manifest>
                """.trimIndent()
            )
            seeded += VmSandboxFileEntity(
                vmId = vm.id,
                fileName = "hal_manifest.xml",
                virtualPath = "/system/vendor/etc/hal_manifest.xml",
                hostRelativePath = "${vm.id}/system/vendor/etc/hal_manifest.xml",
                mimeType = "application/xml",
                sizeBytes = halManifest.length(),
                category = "SYSTEM"
            )
        }

        val seccompProfile = File(localTmp, "seccomp_sandbox.bpf")
        if (!seccompProfile.exists()) {
            seccompProfile.writeText(
                """
                # KestrelVM Userspace Sandbox Policy (${vm.seccompMode})
                TRAP openat       -> translate_rootfs("${vm.id}")
                TRAP faccessat    -> translate_rootfs("${vm.id}")
                TRAP ioctl        -> binder_proxy_check("/dev/binder_proxy")
                TRAP clone        -> track_guest_tid()
                ALLOW futex, epoll_pwait, mmap, munmap, clock_gettime
                """.trimIndent()
            )
            seeded += VmSandboxFileEntity(
                vmId = vm.id,
                fileName = "seccomp_sandbox.bpf",
                virtualPath = "/data/local/tmp/seccomp_sandbox.bpf",
                hostRelativePath = "${vm.id}/data/local/tmp/seccomp_sandbox.bpf",
                mimeType = "text/plain",
                sizeBytes = seccompProfile.length(),
                category = "SYSTEM"
            )
        }

        val welcomeNote = File(vmRoot, "$GUEST_DOCS_REL/sandbox_readme.txt")
        if (!welcomeNote.exists()) {
            welcomeNote.writeText(
                """
                Welcome to ${vm.name} (${vm.guestOsVersion})!
                - Files stored under $GUEST_DOCS_VIRTUAL can be exported back to your host phone via File Vault or the guest Files app.
                - Import APKs to /data/local/tmp and install them directly onto this VM's Home Screen.
                """.trimIndent()
            )
            seeded += VmSandboxFileEntity(
                vmId = vm.id,
                fileName = "sandbox_readme.txt",
                virtualPath = "$GUEST_DOCS_VIRTUAL/sandbox_readme.txt",
                hostRelativePath = "${vm.id}/$GUEST_DOCS_REL/sandbox_readme.txt",
                mimeType = "text/plain",
                sizeBytes = welcomeNote.length(),
                category = "DOCUMENT"
            )
        }

        return seeded
    }

    suspend fun importUriIntoSandbox(vmId: String, uri: Uri, forceCategory: String? = null): ImportedFileResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rawName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}"
            val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val resolvedMime = resolver.getType(uri) ?: guessMimeType(safeName)
            val isApk = safeName.endsWith(".apk", ignoreCase = true) ||
                resolvedMime == "application/vnd.android.package-archive"
            val category = when {
                forceCategory != null -> forceCategory
                isApk -> "APK"
                resolvedMime.startsWith("image/") || resolvedMime.startsWith("video/") -> "MEDIA"
                else -> "DOCUMENT"
            }

            val subdir = when (category) {
                "APK" -> "data/local/tmp"
                "MEDIA" -> "$GUEST_SD_DIR/Pictures"
                else -> "$GUEST_SD_DIR/Download"
            }
            val virtualDir = "/$subdir"
            val targetDir = File(sandboxesRoot, "$vmId/$subdir").apply { mkdirs() }
            val destFile = uniqueFile(targetDir, safeName)

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open selected file stream")

            val apkInfo = if (isApk) readApkArchiveMetadata(destFile.absolutePath, includeDetails = false) else null

            ImportedFileResult(
                fileName = destFile.name,
                virtualPath = "$virtualDir/${destFile.name}",
                hostRelativePath = "$vmId/$subdir/${destFile.name}",
                mimeType = resolvedMime,
                sizeBytes = destFile.length(),
                category = category,
                apkPackageName = apkInfo?.packageName,
                apkVersionName = apkInfo?.versionName,
                apkTargetSdk = apkInfo?.applicationInfo?.targetSdkVersion
            )
        }

    suspend fun stageApkIntoVirtualDataApp(vmId: String, file: VmSandboxFileEntity): InstalledApkArchiveInfo =
        withContext(Dispatchers.IO) {
            val sourceApk = resolveHostFile(file.hostRelativePath)
            if (!sourceApk.exists()) error("APK source file not found in sandbox")

            val info = readApkArchiveMetadata(sourceApk.absolutePath, includeDetails = true)
            val pkg = info?.packageName ?: file.apkPackageName
                ?: file.fileName.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]"), "_")
                    .let { "com.guest.sandbox.$it" }

            val pm = context.packageManager
            val appInfo = info?.applicationInfo?.apply {
                sourceDir = sourceApk.absolutePath
                publicSourceDir = sourceApk.absolutePath
            }

            val appName = appInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                ?: file.fileName.substringBeforeLast('.')
            val versionName = info?.versionName ?: file.apkVersionName ?: "1.0"
            val targetSdk = appInfo?.targetSdkVersion ?: file.apkTargetSdk ?: 34
            val perms = info?.requestedPermissions?.take(12)?.joinToString(", ") {
                it.substringAfterLast('.')
            }.orEmpty()
            val activitiesCount = info?.activities?.size ?: 1

            // Copy APK into isolated VM /data/app/<pkg>/base.apk
            val appInstallDir = File(sandboxesRoot, "$vmId/data/app/$pkg").apply { mkdirs() }
            val installedApk = File(appInstallDir, "base.apk")
            sourceApk.copyTo(installedApk, overwrite = true)

            // Extract real app icon into /data/app_icons/<pkg>.png
            val iconRelPath = "$vmId/data/app_icons/$pkg.png"
            val iconFile = File(sandboxesRoot, iconRelPath)
            iconFile.parentFile?.mkdirs()
            val savedIcon = runCatching {
                val drawable = appInfo?.loadIcon(pm) ?: return@runCatching false
                val bmp = createBitmap(96, 96)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                FileOutputStream(iconFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                true
            }.getOrDefault(false)

            InstalledApkArchiveInfo(
                packageName = pkg,
                appName = appName,
                versionName = versionName,
                targetSdk = targetSdk,
                permissionsSummary = perms,
                activitiesCount = activitiesCount,
                apkVirtualPath = "/data/app/$pkg/base.apk",
                apkHostPath = "$vmId/data/app/$pkg/base.apk",
                iconHostPath = if (savedIcon) iconRelPath else null
            )
        }

    suspend fun saveGuestNote(vmId: String, title: String, body: String): VmSandboxFileEntity =
        withContext(Dispatchers.IO) {
            val safeTitle = title.trim().ifBlank { "note_${System.currentTimeMillis()}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val fileName = if (safeTitle.endsWith(".txt")) safeTitle else "$safeTitle.txt"
            val docsDir = File(sandboxesRoot, "$vmId/$GUEST_DOCS_REL").apply { mkdirs() }
            val noteFile = File(docsDir, fileName)
            noteFile.writeText(body)
            VmSandboxFileEntity(
                vmId = vmId,
                fileName = fileName,
                virtualPath = "$GUEST_DOCS_VIRTUAL/$fileName",
                hostRelativePath = "$vmId/$GUEST_DOCS_REL/$fileName",
                mimeType = "text/plain",
                sizeBytes = noteFile.length(),
                category = "DOCUMENT"
            )
        }

    suspend fun exportFileFromSandbox(hostRelativePath: String, destinationUri: Uri): Long =
        withContext(Dispatchers.IO) {
            val source = File(sandboxesRoot, hostRelativePath)
            if (!source.exists()) error("Sandboxed file does not exist on disk")
            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open export destination")
            source.length()
        }

    suspend fun readTextPreview(hostRelativePath: String, maxChars: Int = 2400): String =
        withContext(Dispatchers.IO) {
            val file = File(sandboxesRoot, hostRelativePath)
            if (!file.exists()) return@withContext "File unavailable in sandbox"
            file.bufferedReader().use { reader ->
                val buf = CharArray(maxChars)
                val read = reader.read(buf, 0, maxChars)
                if (read <= 0) "" else String(buf, 0, read)
            }
        }

    fun deleteHostFile(hostRelativePath: String) {
        val file = File(sandboxesRoot, hostRelativePath)
        if (file.exists()) file.delete()
    }

    fun deleteVmSandboxFolder(vmId: String) {
        val dir = File(sandboxesRoot, vmId)
        if (dir.exists()) dir.deleteRecursively()
    }

    fun resolveHostFile(hostRelativePath: String): File = File(sandboxesRoot, hostRelativePath)

    private fun readApkArchiveMetadata(apkPath: String, includeDetails: Boolean): PackageInfo? {
        return try {
            val pm = context.packageManager
            val flags = if (includeDetails) {
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
            } else {
                0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, flags)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var counter = 1
        while (true) {
            val next = File(dir, "${base}_$counter$suffix")
            if (!next.exists()) return next
            counter++
        }
    }

    private fun guessMimeType(name: String): String = when {
        name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".xml", ignoreCase = true) -> "application/xml"
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        name.endsWith(".prop", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true) -> "text/plain"
        else -> "application/octet-stream"
    }
}
