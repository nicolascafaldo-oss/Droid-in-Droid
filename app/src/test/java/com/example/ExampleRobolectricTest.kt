package com.example

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.SandboxFileStorage
import com.example.data.local.VmRunState
import com.example.data.local.VmSandboxFileEntity
import com.example.data.repository.VmRepository
import com.example.engine.NativeEngineBridge
import com.example.ui.VmWorkspaceTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var storage: SandboxFileStorage
    private lateinit var repository: VmRepository

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = SandboxFileStorage(context)
        repository = VmRepository(db.vmDao(), storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `app name matches KestrelVM`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("KestrelVM", appName)
    }

    @Test
    fun `seed creates factory unlocked rooted VMs system guest apps and build_prop`() = runTest {
        repository.ensureSeeded(hostCores = 8)
        val instances = repository.allInstances.first()
        assertEquals(3, instances.size)
        assertTrue(instances.all { it.runState == VmRunState.STOPPED.name })
        assertTrue(instances.all { it.bootloaderUnlocked && it.rootEnabled })
        assertTrue(instances.any { it.id == "vm-aosp14-dev" && it.allocatedCores == 8 })

        val apps = repository.allGuestApps.first().filter { it.vmId == "vm-aosp14-dev" }
        assertTrue(apps.any { it.packageName == "com.android.settings" })
        assertTrue(apps.any { it.packageName == "com.android.documentsui" })
        assertTrue(apps.any { it.packageName == "com.kestrel.egl" })

        val buildProp = repository.resolveHostFile("vm-aosp14-dev/system/build.prop")
        assertTrue(buildProp.exists())
        val propsText = buildProp.readText()
        assertTrue(propsText.contains("ro.sf.lcd_density=420"))
        assertTrue(propsText.contains("ro.boot.flash.locked=0"))
        assertTrue(propsText.contains("persist.kestrel.root=1"))

        val suBinary = repository.resolveHostFile("vm-aosp14-dev/system/xbin/su")
        assertTrue(suBinary.exists())
    }

    @Test
    fun `installApkInVm stages virtual app in data_app and uninstall removes it`() = runTest {
        repository.ensureSeeded(hostCores = 8)
        val apkRelPath = "vm-aosp14-dev/data/local/tmp/sample_game.apk"
        val hostApk = repository.resolveHostFile(apkRelPath)
        hostApk.parentFile?.mkdirs()
        hostApk.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

        val sandboxFile = VmSandboxFileEntity(
            vmId = "vm-aosp14-dev",
            fileName = "sample_game.apk",
            virtualPath = "/data/local/tmp/sample_game.apk",
            hostRelativePath = apkRelPath,
            mimeType = "application/vnd.android.package-archive",
            sizeBytes = hostApk.length(),
            category = "APK",
            apkPackageName = "com.demo.kestrelgame",
            apkVersionName = "2.1.0",
            apkTargetSdk = 34
        )
        val installed = repository.installApkInVm("vm-aosp14-dev", sandboxFile)
        assertEquals("com.demo.kestrelgame", installed.packageName)
        assertTrue(File(storage.sandboxesRoot, installed.apkHostPath).exists())

        repository.uninstallGuestApp(installed)
        assertFalse(File(storage.sandboxesRoot, installed.apkHostPath).exists())
    }

    @Test
    fun `native bridge path translation blocks directory traversal`() {
        NativeEngineBridge.bootSandbox("vm-test", "/data/kestrel/vm-test", 1)
        val translated = NativeEngineBridge.translateGuestPath("vm-test", "/../../etc/passwd")
        assertEquals("/data/kestrel/vm-test/etc/passwd", translated)
    }

    @Test
    fun `saveGuestNote writes document inside VM sandbox`() = runTest {
        repository.ensureSeeded(hostCores = 8)
        val note = repository.saveGuestNote("vm-aosp10-game", "fps_notes", "90Hz steady")
        val file = repository.resolveHostFile(note.hostRelativePath)
        assertTrue(file.exists())
        assertEquals("90Hz steady", file.readText())
    }

    @Test
    fun `loadAndOpenVm starts fullscreen and volUp_volDown_power combo returns to viewport`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.viewModel.loadAndOpenVm("vm-aosp14-dev")
        assertTrue(activity.viewModel.isFullscreenActiveNow())

        // Single volume key press must not exit fullscreen
        assertFalse(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(activity.viewModel.isFullscreenActiveNow())

        // Volume Down while Volume Up is held still waits for Power
        assertFalse(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertTrue(activity.viewModel.isFullscreenActiveNow())

        // Completing Volume Up + Volume Down + Power exits fullscreen back to viewport
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER)))
        assertFalse(activity.viewModel.isFullscreenActiveNow())
    }

    @Test
    fun `two-way file transfer imports into sandbox and exports back to host uri`() = runTest {
        repository.ensureSeeded(hostCores = 8)
        val incomingHostFile = File(context.cacheDir, "host_payload.txt").apply {
            writeText("kestrel-two-way-transfer-ok")
        }

        val imported = repository.importFile("vm-aosp14-dev", Uri.fromFile(incomingHostFile), "DOCUMENT")
        assertEquals("DOCUMENT", imported.category)
        val storedInSandbox = repository.resolveHostFile(imported.hostRelativePath)
        assertTrue(storedInSandbox.exists())
        assertEquals("kestrel-two-way-transfer-ok", storedInSandbox.readText())

        val exportedHostFile = File(context.cacheDir, "exported_payload.txt").apply { delete() }
        val bytesWritten = repository.exportFile(imported, Uri.fromFile(exportedHostFile))
        assertTrue(bytesWritten > 0L)
        assertTrue(exportedHostFile.exists())
        assertEquals("kestrel-two-way-transfer-ok", exportedHostFile.readText())
    }

    @Test
    fun `top workspace tabs are visible and switch tabs after exiting fullscreen`() {
        composeTestRule.runOnUiThread {
            composeTestRule.activity.viewModel.loadAndOpenVm("vm-aosp14-dev")
            composeTestRule.activity.viewModel.exitVmFullscreen()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_viewport").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_files").assertIsDisplayed().performClick()
        assertEquals(VmWorkspaceTab.FILES, composeTestRule.activity.viewModel.uiState.value.activeWorkspaceTab)

        composeTestRule.onNodeWithTag("nav_engine").assertIsDisplayed().performClick()
        assertEquals(VmWorkspaceTab.HARDWARE, composeTestRule.activity.viewModel.uiState.value.activeWorkspaceTab)

        composeTestRule.onNodeWithTag("nav_viewport").performClick()
        assertEquals(VmWorkspaceTab.DISPLAY, composeTestRule.activity.viewModel.uiState.value.activeWorkspaceTab)
    }

    @Test
    fun `binder proxy isolates host nodes and hal telemetry records audio pulse`() {
        NativeEngineBridge.bootSandbox("vm-epic1", "/data/kestrel/vm-epic1", 1)
        assertEquals(
            "/data/kestrel/vm-epic1/dev/binder_proxy",
            NativeEngineBridge.translateGuestPath("vm-epic1", "/dev/binder")
        )

        val ping = NativeEngineBridge.transactBinder("vm-epic1", "servicemanager", 1, "")
        assertEquals(0, ping.status)
        assertTrue(ping.response.contains("PONG"))

        val blocked = NativeEngineBridge.transactBinder("vm-epic1", "/dev/binder", 1, "")
        assertEquals(-1, blocked.status)
        assertTrue(blocked.response.contains("EACCES"))

        val sandboxTelemetry = NativeEngineBridge.getSandboxTelemetry("vm-epic1")
        assertTrue(sandboxTelemetry.blockedHostEscapes >= 1L)

        val frames = NativeEngineBridge.playAudioPulse("vm-epic1", freqHz = 528, durationMs = 80)
        assertTrue(frames > 0)
        val halTelemetry = NativeEngineBridge.getHalTelemetry("vm-epic1")
        assertTrue(halTelemetry.audioActive)
        assertTrue(halTelemetry.audioFramesWritten >= frames.toLong())
    }

    @Test
    fun `rootfs provisioner extracts safe zip entries and neutralizes zip-slip traversal`() = runTest {
        repository.ensureSeeded(hostCores = 8)
        val vm = repository.allInstances.first().first { it.id == "vm-aosp14-dev" }

        val zipFile = File(context.cacheDir, "guest_rootfs_test.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../../escaped.txt"))
            zos.write("should-never-escape".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("system/etc/kestrel.conf"))
            zos.write("kestrel.rootfs=verified\n".toByteArray())
            zos.closeEntry()
        }

        val provisioner = com.example.data.rootfs.RootfsProvisioner(context)
        val manifest = provisioner.provisionFromLocalArchive(vm, Uri.fromFile(zipFile)) { _, _ -> }
        assertEquals("VERIFIED", manifest.status)
        assertTrue(manifest.fileCount >= 3)
        assertTrue(manifest.sha256.length == 64)

        val extracted = File(provisioner.getVmRootDir(vm.id), "system/etc/kestrel.conf")
        assertTrue(extracted.exists())
        assertEquals("kestrel.rootfs=verified\n", extracted.readText())
        assertFalse(File(context.filesDir, "escaped.txt").exists())
    }

    @Test
    fun `rootfs provisioner rejects insecure http and malformed urls`() = runTest {
        repository.ensureSeeded(hostCores = 8)
        val vm = repository.allInstances.first().first { it.id == "vm-aosp14-dev" }
        val provisioner = com.example.data.rootfs.RootfsProvisioner(context)

        val insecureThrown = runCatching {
            provisioner.provisionFromHttpsUrl(vm, "http://example.com/rootfs.tar.gz", null) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(insecureThrown is IllegalArgumentException)

        val malformedThrown = runCatching {
            provisioner.provisionFromHttpsUrl(vm, "not a valid uri :::://", null) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(malformedThrown is IllegalArgumentException)
    }
}
