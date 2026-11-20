package com.example

import android.content.Context
import android.view.KeyEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.SandboxFileStorage
import com.example.data.local.VmRunState
import com.example.data.local.VmSandboxFileEntity
import com.example.data.repository.VmRepository
import com.example.engine.NativeEngineBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
}
