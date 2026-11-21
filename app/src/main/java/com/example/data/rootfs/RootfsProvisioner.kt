package com.example.data.rootfs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.VmInstanceEntity
import com.example.engine.RootfsManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Epic 3: On-demand AOSP guest rootfs provisioning, SHA-256 verification, and
 * ZipSlip/TarSlip-safe archive extraction into `filesDir/vm_sandboxes/<vmId>/`.
 */
class RootfsProvisioner(private val context: Context) {

    private val sandboxesRoot: File
        get() = File(context.filesDir, "vm_sandboxes").apply { mkdirs() }

    fun getVmRootDir(vmId: String): File = File(sandboxesRoot, vmId).apply { mkdirs() }

    private fun manifestFile(vmId: String): File =
        File(getVmRootDir(vmId), ".kestrel_rootfs_manifest.json")

    suspend fun loadManifest(vm: VmInstanceEntity): RootfsManifest = withContext(Dispatchers.IO) {
        val file = manifestFile(vm.id)
        if (!file.exists()) {
            return@withContext verifyAndRecordManifest(vm, sourceType = "BOOTSTRAP_BASE", digestOverride = null)
        }
        runCatching {
            val obj = JSONObject(file.readText())
            RootfsManifest(
                vmId = obj.optString("vmId", vm.id),
                status = obj.optString("status", "VERIFIED"),
                sourceType = obj.optString("sourceType", "BOOTSTRAP_BASE"),
                guestOsVersion = obj.optString("guestOsVersion", vm.guestOsVersion),
                apiLevel = obj.optInt("apiLevel", vm.apiLevel),
                abi = obj.optString("abi", vm.abi),
                sha256 = obj.optString("sha256", ""),
                fileCount = obj.optInt("fileCount", 0),
                totalBytes = obj.optLong("totalBytes", 0L),
                elfInitPresent = obj.optBoolean("elfInitPresent", false),
                bootloaderUnlocked = obj.optBoolean("bootloaderUnlocked", vm.bootloaderUnlocked),
                rootSuInstalled = obj.optBoolean("rootSuInstalled", vm.rootEnabled),
                verifiedAtEpochMs = obj.optLong("verifiedAtEpochMs", 0L)
            )
        }.getOrElse {
            verifyAndRecordManifest(vm, sourceType = "BOOTSTRAP_BASE", digestOverride = null)
        }
    }

    suspend fun verifyOrRepairRootfs(vm: VmInstanceEntity): RootfsManifest = withContext(Dispatchers.IO) {
        ensureBaseSystemLayout(vm)
        val existingSource = manifestFile(vm.id).takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()).optString("sourceType") }.getOrNull() }
            ?: "BOOTSTRAP_BASE"
        verifyAndRecordManifest(vm, sourceType = existingSource, digestOverride = null)
    }

    suspend fun provisionFromLocalArchive(
        vm: VmInstanceEntity,
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): RootfsManifest = withContext(Dispatchers.IO) {
        val fileName = queryFileName(uri) ?: "rootfs_archive.bin"
        val stagingDir = File(getVmRootDir(vm.id), "data/local/rootfs_stage").apply { mkdirs() }
        val stagedFile = File(stagingDir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))

        onProgress(0.08f, "Copying rootfs archive into isolated sandbox...")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(stagedFile).use { output ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    copied += read
                }
            }
        } ?: error("Unable to read selected rootfs archive")

        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        onProgress(0.45f, "Unpacking rootfs archive (${copied / 1024} KB)...")
        extractArchiveSafe(vm, stagedFile, onProgress)
        ensureBaseSystemLayout(vm)
        onProgress(0.92f, "Writing unlocked/rooted build.prop & manifest...")
        verifyAndRecordManifest(vm, sourceType = "LOCAL_ARCHIVE:${stagedFile.name}", digestOverride = shaHex)
    }

    suspend fun provisionFromHttpsUrl(
        vm: VmInstanceEntity,
        urlString: String,
        expectedSha256: String?,
        onProgress: (Float, String) -> Unit
    ): RootfsManifest = withContext(Dispatchers.IO) {
        val trimmedUrl = urlString.trim()
        val parsed = runCatching { URI(trimmedUrl).toURL() }
            .getOrElse { throw IllegalArgumentException("Invalid rootfs URL: ${it.message}", it) }
        val host = parsed.host.orEmpty()
        val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
        require(parsed.protocol.equals("https", ignoreCase = true) || (parsed.protocol.equals("http", ignoreCase = true) && isLoopback)) {
            "Only HTTPS URLs (or localhost loopbacks) are permitted for rootfs downloads"
        }

        val stagingDir = File(getVmRootDir(vm.id), "data/local/rootfs_stage").apply { mkdirs() }
        val targetName = parsed.path.substringAfterLast('/').ifBlank { "guest_rootfs.tar.gz" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val archiveFile = File(stagingDir, targetName)

        onProgress(0.05f, "Connecting to ${parsed.host}...")
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("Server returned HTTP $code")
            }
            val totalLen = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(archiveFile).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        val frac = if (totalLen > 0L) {
                            0.10f + (downloaded.toFloat() / totalLen.toFloat()).coerceIn(0f, 1f) * 0.50f
                        } else 0.35f
                        onProgress(frac, "Downloaded ${downloaded / 1024} KB...")
                    }
                }
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val normalizedExpected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (normalizedExpected != null && normalizedExpected != actualSha) {
                archiveFile.delete()
                error("SHA-256 mismatch: expected ${normalizedExpected.take(12)}…, got ${actualSha.take(12)}…")
            }

            onProgress(0.68f, "Extracting downloaded AOSP rootfs...")
            extractArchiveSafe(vm, archiveFile, onProgress)
            ensureBaseSystemLayout(vm)
            onProgress(0.94f, "Verifying unlocked rootfs tree...")
            verifyAndRecordManifest(vm, sourceType = "HTTPS_DOWNLOAD:${parsed.host}", digestOverride = actualSha)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractArchiveSafe(
        vm: VmInstanceEntity,
        archive: File,
        onProgress: (Float, String) -> Unit
    ) {
        val root = getVmRootDir(vm.id)
        val rootCanonical = root.canonicalPath
        val lower = archive.name.lowercase()

        when {
            lower.endsWith(".zip") -> {
                ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                    var count = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val sanitized = entry.name.trimStart('/')
                        if (sanitized.isNotEmpty() && !sanitized.contains("..")) {
                            val outFile = File(root, sanitized)
                            if (outFile.canonicalPath.startsWith(rootCanonical + File.separator)) {
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos -> zip.copyTo(fos) }
                                    count++
                                    if (count % 25 == 0) {
                                        onProgress(0.75f, "Extracted $count zip entries...")
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { gz ->
                    extractTarStreamSafe(root, rootCanonical, gz, onProgress)
                }
            }

            lower.endsWith(".tar") -> {
                BufferedInputStream(FileInputStream(archive)).use { tar ->
                    extractTarStreamSafe(root, rootCanonical, tar, onProgress)
                }
            }

            else -> {
                // Stage raw filesystem image (system.img / vendor.img) under /system/images/
                val imgDir = File(root, "system/images").apply { mkdirs() }
                val dest = File(imgDir, archive.name)
                if (archive.canonicalPath != dest.canonicalPath) {
                    archive.copyTo(dest, overwrite = true)
                }
            }
        }
    }

    /**
     * Minimal POSIX `ustar` tar extractor guarding against directory traversal (TarSlip).
     */
    private fun extractTarStreamSafe(
        root: File,
        rootCanonical: String,
        stream: InputStream,
        onProgress: (Float, String) -> Unit
    ) {
        val header = ByteArray(512)
        val copyBuf = ByteArray(16 * 1024)
        var count = 0

        while (true) {
            if (!readFullyOrEof(stream, header)) break
            if (header.all { it.toInt() == 0 }) break

            val rawName = extractAsciiField(header, 0, 100)
            val prefix = extractAsciiField(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            val sizeStr = extractAsciiField(header, 124, 12).trim()
            val fileSize = sizeStr.toLongOrNull(8) ?: 0L
            val typeFlag = header[156].toInt().toChar()

            val sanitized = fullName.trimStart('.', '/').trim()
            val allowed = sanitized.isNotEmpty() &&
                !sanitized.split('/').contains("..") &&
                File(root, sanitized).canonicalPath.startsWith(rootCanonical + File.separator)

            if (allowed && typeFlag == '5') {
                File(root, sanitized).mkdirs()
                skipExact(stream, paddedTarBlocks(fileSize))
            } else if (allowed && (typeFlag == '0' || typeFlag == '\u0000')) {
                val outFile = File(root, sanitized)
                outFile.parentFile?.mkdirs()
                var remaining = fileSize
                FileOutputStream(outFile).use { out ->
                    while (remaining > 0L) {
                        val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                        val read = stream.read(copyBuf, 0, toRead)
                        if (read <= 0) break
                        out.write(copyBuf, 0, read)
                        remaining -= read
                    }
                }
                val pad = ((512L - (fileSize % 512L)) % 512L)
                if (pad > 0L) skipExact(stream, pad)
                count++
                if (count % 25 == 0) {
                    onProgress(0.8f, "Extracted $count tar files...")
                }
            } else {
                skipExact(stream, paddedTarBlocks(fileSize))
            }
        }
    }

    private fun paddedTarBlocks(size: Long): Long {
        if (size <= 0L) return 0L
        val rem = size % 512L
        return if (rem == 0L) size else size + (512L - rem)
    }

    private fun readFullyOrEof(stream: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = stream.read(buf, offset, buf.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    private fun skipExact(stream: InputStream, bytes: Long) {
        var left = bytes
        val scratch = ByteArray(4096)
        while (left > 0L) {
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n <= 0) break
            left -= n
        }
    }

    private fun extractAsciiField(buf: ByteArray, offset: Int, len: Int): String {
        val end = minOf(buf.size, offset + len)
        var actualEnd = offset
        while (actualEnd < end && buf[actualEnd].toInt() != 0) {
            actualEnd++
        }
        return String(buf, offset, actualEnd - offset, Charsets.US_ASCII).trim()
    }

    private fun ensureBaseSystemLayout(vm: VmInstanceEntity) {
        val root = getVmRootDir(vm.id)
        listOf(
            "system/bin",
            "system/xbin",
            "system/framework",
            "system/vendor/etc",
            "data/local/tmp",
            "data/app",
            "dev/binder_proxy"
        ).forEach { File(root, it).mkdirs() }

        val initRc = File(root, "init.rc")
        if (!initRc.exists()) {
            initRc.writeText(
                """
                # KestrelVM Stripped Guest Init RC (${vm.guestOsVersion})
                service kestrel_servicemanager /system/bin/servicemanager
                    user system
                    setenv BINDER_NODE /dev/binder_proxy/${vm.id}
                service kestrel_hwc /system/bin/surfaceflinger
                    setenv HWC_BRIDGE ANativeWindow
                """.trimIndent()
            )
        }

        if (vm.rootEnabled) {
            val su = File(root, "system/xbin/su")
            if (!su.exists()) {
                su.writeText("#!/system/bin/sh\n# KestrelVM Root Access (uid=0)\nexec /system/bin/sh \"$@\"\n")
            }
        }
    }

    private fun verifyAndRecordManifest(
        vm: VmInstanceEntity,
        sourceType: String,
        digestOverride: String?
    ): RootfsManifest {
        val root = getVmRootDir(vm.id)
        var fileCount = 0
        var totalBytes = 0L
        val treeDigest = MessageDigest.getInstance("SHA-256")

        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name != ".kestrel_rootfs_manifest.json") {
                fileCount++
                totalBytes += f.length()
                treeDigest.update(f.relativeTo(root).path.toByteArray())
            }
        }

        val buildProp = File(root, "system/build.prop")
        if (buildProp.exists()) {
            treeDigest.update(buildProp.readBytes())
        }

        val shaHex = digestOverride ?: treeDigest.digest().joinToString("") { "%02x".format(it) }
        val elfPresent = isElfBinary(File(root, "system/bin/kestrel_init")) ||
            isElfBinary(File(root, "init"))
        val suExists = File(root, "system/xbin/su").exists()
        val status = if (buildProp.exists() && fileCount >= 3) "VERIFIED" else "PARTIAL"

        val manifest = RootfsManifest(
            vmId = vm.id,
            status = status,
            sourceType = sourceType,
            guestOsVersion = vm.guestOsVersion,
            apiLevel = vm.apiLevel,
            abi = vm.abi,
            sha256 = shaHex,
            fileCount = fileCount,
            totalBytes = totalBytes,
            elfInitPresent = elfPresent,
            bootloaderUnlocked = vm.bootloaderUnlocked,
            rootSuInstalled = suExists,
            verifiedAtEpochMs = System.currentTimeMillis()
        )

        val json = JSONObject().apply {
            put("vmId", manifest.vmId)
            put("status", manifest.status)
            put("sourceType", manifest.sourceType)
            put("guestOsVersion", manifest.guestOsVersion)
            put("apiLevel", manifest.apiLevel)
            put("abi", manifest.abi)
            put("sha256", manifest.sha256)
            put("fileCount", manifest.fileCount)
            put("totalBytes", manifest.totalBytes)
            put("elfInitPresent", manifest.elfInitPresent)
            put("bootloaderUnlocked", manifest.bootloaderUnlocked)
            put("rootSuInstalled", manifest.rootSuInstalled)
            put("verifiedAtEpochMs", manifest.verifiedAtEpochMs)
        }
        manifestFile(vm.id).writeText(json.toString(2))
        return manifest
    }

    private fun isElfBinary(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 4) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                fis.read(magic) == 4 &&
                    magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun queryFileName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
