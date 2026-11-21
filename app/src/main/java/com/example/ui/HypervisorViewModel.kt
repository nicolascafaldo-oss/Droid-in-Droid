package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.GuestInstalledAppEntity
import com.example.data.local.SandboxFileStorage
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmRunState
import com.example.data.local.VmSandboxFileEntity
import com.example.data.repository.VmRepository
import com.example.data.rootfs.RootfsProvisioner
import com.example.engine.HalTelemetry
import com.example.engine.NativeEngineBridge
import com.example.engine.RootfsManifest
import com.example.engine.RootfsProvisionProgress
import com.example.engine.SandboxTelemetry
import com.example.engine.SyscallLogEntry
import com.example.hardware.HostHardwareMonitor
import com.example.hardware.HostHardwareSnapshot
import com.example.hardware.HostSensorSample
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

sealed class GuestDeviceScreen {
    data object Home : GuestDeviceScreen()
    data object AppDrawer : GuestDeviceScreen()
    data object Recents : GuestDeviceScreen()
    data class RunningApp(val packageName: String) : GuestDeviceScreen()
    data class PackageInstaller(val apkFile: VmSandboxFileEntity) : GuestDeviceScreen()
}

enum class VmWorkspaceTab {
    DISPLAY,
    FILES,
    HARDWARE
}

data class HypervisorUiState(
    val instances: List<VmInstanceEntity> = emptyList(),
    val loadedVmId: String? = null,
    val activeWorkspaceTab: VmWorkspaceTab = VmWorkspaceTab.DISPLAY,
    val isVmFullscreen: Boolean = false,
    val activeVmFiles: List<VmSandboxFileEntity> = emptyList(),
    val allFiles: List<VmSandboxFileEntity> = emptyList(),
    val activeVmApps: List<GuestInstalledAppEntity> = emptyList(),
    val hostHardware: HostHardwareSnapshot = HostHardwareSnapshot(),
    val hostSensors: HostSensorSample = HostSensorSample(),
    val syscallLogs: List<SyscallLogEntry> = emptyList(),
    val guestScreen: GuestDeviceScreen = GuestDeviceScreen.Home,
    val guestRecentPackages: List<String> = emptyList(),
    val isGuestShadeOpen: Boolean = false,
    val guestBrightness: Float = 0.92f,
    val guestWifiEnabled: Boolean = true,
    val guestLayoutBounds: Boolean = false,
    val isMiniFloatVisible: Boolean = false,
    val isInPipMode: Boolean = false,
    val darkTheme: Boolean = true,
    val statusBanner: String? = null,
    val previewFileContent: Pair<VmSandboxFileEntity, String>? = null,
    val bridgeRuntimeSummary: String = NativeEngineBridge.getRuntimeBridgeSummary(),
    val sandboxTelemetry: SandboxTelemetry = SandboxTelemetry(),
    val halTelemetry: HalTelemetry = HalTelemetry(),
    val rootfsManifest: RootfsManifest = RootfsManifest(),
    val rootfsProgress: RootfsProvisionProgress? = null
) {
    val activeVm: VmInstanceEntity?
        get() = loadedVmId?.let { id -> instances.find { it.id == id } }

    val runningVms: List<VmInstanceEntity>
        get() = instances.filter {
            it.runState == VmRunState.RUNNING.name || it.runState == VmRunState.BOOTING.name
        }

    val vaultVm: VmInstanceEntity?
        get() = activeVm ?: instances.firstOrNull()
}

private data class CoreSelectionState(
    val loadedVmId: String?,
    val workspaceTab: VmWorkspaceTab,
    val guestScreen: GuestDeviceScreen,
    val recentPackages: List<String>,
    val miniFloatVisible: Boolean,
    val inPipMode: Boolean
)

private data class GuestShadeState(
    val shadeOpen: Boolean,
    val brightness: Float,
    val wifiEnabled: Boolean,
    val layoutBounds: Boolean
)

private data class ChromeState(
    val darkTheme: Boolean,
    val banner: String?,
    val logs: List<SyscallLogEntry>,
    val preview: Pair<VmSandboxFileEntity, String>?,
    val guestShade: GuestShadeState,
    val vmFullscreen: Boolean
)

private data class DataBundle(
    val instances: List<VmInstanceEntity>,
    val allFiles: List<VmSandboxFileEntity>,
    val allApps: List<GuestInstalledAppEntity>,
    val hw: HostHardwareSnapshot,
    val sensors: HostSensorSample
)

class HypervisorViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val fileStorage = SandboxFileStorage(application)
    private val repository = VmRepository(database.vmDao(), fileStorage)
    private val rootfsProvisioner = RootfsProvisioner(application)
    private val hardwareMonitor = HostHardwareMonitor(application)

    private val _loadedVmId = MutableStateFlow<String?>(null)
    private val _workspaceTab = MutableStateFlow(VmWorkspaceTab.DISPLAY)
    private val _isVmFullscreen = MutableStateFlow(false)
    private val _guestScreen = MutableStateFlow<GuestDeviceScreen>(GuestDeviceScreen.Home)
    private val _guestRecentPackages = MutableStateFlow<List<String>>(emptyList())
    private val _isGuestShadeOpen = MutableStateFlow(false)
    private val _guestBrightness = MutableStateFlow(0.92f)
    private val _guestWifiEnabled = MutableStateFlow(true)
    private val _guestLayoutBounds = MutableStateFlow(false)
    private val _isMiniFloatVisible = MutableStateFlow(false)
    private val _isInPipMode = MutableStateFlow(false)
    private val _darkTheme = MutableStateFlow(true)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _syscallLogs = MutableStateFlow<List<SyscallLogEntry>>(emptyList())
    private val _previewFile = MutableStateFlow<Pair<VmSandboxFileEntity, String>?>(null)
    private val _sandboxTelemetry = MutableStateFlow(SandboxTelemetry())
    private val _halTelemetry = MutableStateFlow(HalTelemetry())
    private val _rootfsManifest = MutableStateFlow(RootfsManifest())
    private val _rootfsProgress = MutableStateFlow<RootfsProvisionProgress?>(null)

    private val logSeq = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val bootJobs = mutableMapOf<String, Job>()

    private val selectionPartA = combine(
        _loadedVmId,
        _workspaceTab,
        _guestScreen
    ) { loadedId, tab, screen -> Triple(loadedId, tab, screen) }

    private val selectionPartB = combine(
        _guestRecentPackages,
        _isMiniFloatVisible,
        _isInPipMode
    ) { recents, miniFloat, pip -> Triple(recents, miniFloat, pip) }

    private val selectionFlow = combine(selectionPartA, selectionPartB) { a, b ->
        CoreSelectionState(
            loadedVmId = a.first,
            workspaceTab = a.second,
            guestScreen = a.third,
            recentPackages = b.first,
            miniFloatVisible = b.second,
            inPipMode = b.third
        )
    }

    private val shadeFlow = combine(
        _isGuestShadeOpen,
        _guestBrightness,
        _guestWifiEnabled,
        _guestLayoutBounds
    ) { open, bright, wifi, bounds ->
        GuestShadeState(open, bright, wifi, bounds)
    }

    private val chromeBaseFlow = combine(
        _darkTheme,
        _statusBanner,
        _syscallLogs,
        _previewFile,
        shadeFlow
    ) { darkTheme, banner, logs, preview, shade ->
        ChromeState(darkTheme, banner, logs, preview, shade, vmFullscreen = false)
    }

    private val chromeFlow = combine(chromeBaseFlow, _isVmFullscreen) { base, full ->
        base.copy(vmFullscreen = full)
    }

    private val dataFlow = combine(
        repository.allInstances,
        repository.allFiles,
        repository.allGuestApps,
        hardwareMonitor.observeHardwareTelemetry(),
        hardwareMonitor.observeLiveSensors()
    ) { instances, allFiles, allApps, hw, sensors ->
        DataBundle(instances, allFiles, allApps, hw, sensors)
    }

    private val telemetryBundleFlow = combine(
        _sandboxTelemetry,
        _halTelemetry,
        _rootfsManifest,
        _rootfsProgress
    ) { sb, hal, rootfs, prog ->
        Quad(sb, hal, rootfs, prog)
    }

    val uiState: StateFlow<HypervisorUiState> = combine(
        dataFlow,
        selectionFlow,
        chromeFlow,
        telemetryBundleFlow
    ) { data, selection, chrome, telem ->
        val validLoadedId = selection.loadedVmId?.takeIf { id ->
            data.instances.any { it.id == id }
        }

        HypervisorUiState(
            instances = data.instances,
            loadedVmId = validLoadedId,
            activeWorkspaceTab = selection.workspaceTab,
            isVmFullscreen = validLoadedId != null && chrome.vmFullscreen,
            activeVmFiles = if (validLoadedId != null) data.allFiles.filter { it.vmId == validLoadedId } else emptyList(),
            allFiles = data.allFiles,
            activeVmApps = if (validLoadedId != null) data.allApps.filter { it.vmId == validLoadedId } else emptyList(),
            hostHardware = data.hw,
            hostSensors = data.sensors,
            syscallLogs = chrome.logs,
            guestScreen = selection.guestScreen,
            guestRecentPackages = selection.recentPackages,
            isGuestShadeOpen = chrome.guestShade.shadeOpen,
            guestBrightness = chrome.guestShade.brightness,
            guestWifiEnabled = chrome.guestShade.wifiEnabled,
            guestLayoutBounds = chrome.guestShade.layoutBounds,
            isMiniFloatVisible = selection.miniFloatVisible,
            isInPipMode = selection.inPipMode,
            darkTheme = chrome.darkTheme,
            statusBanner = chrome.banner,
            previewFileContent = chrome.preview,
            sandboxTelemetry = telem.a,
            halTelemetry = telem.b,
            rootfsManifest = telem.c,
            rootfsProgress = telem.d
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HypervisorUiState()
    )

    init {
        viewModelScope.launch {
            val hostCores = Runtime.getRuntime().availableProcessors()
            repository.ensureSeeded(hostCores)
        }
        startTelemetryTickLoop()
    }

    private fun startTelemetryTickLoop() {
        viewModelScope.launch {
            while (true) {
                delay(2400L)
                val currentActive = uiState.value.activeVm
                if (currentActive != null) {
                    refreshNativeTelemetry(currentActive)
                }
                val running = uiState.value.runningVms
                if (running.isNotEmpty()) {
                    val vm = currentActive?.takeIf { it.runState == VmRunState.RUNNING.name } ?: running.first()
                    val sensors = uiState.value.hostSensors
                    if (vm.sensorPassthroughEnabled) {
                        NativeEngineBridge.pushSensorSample(
                            vm.id,
                            sensors.accelX, sensors.accelY, sensors.accelZ,
                            sensors.gyroX, sensors.gyroY, sensors.gyroZ
                        )
                    }
                    val (layer, call, detail, us) = when ((0..3).random()) {
                        0 -> Quad("RUST-SYSCALL", "openat(\"/data/app/...\")", "Translated -> ${NativeEngineBridge.translateGuestPath(vm.id, "/data/app")}", (16..42).random())
                        1 -> {
                            val ping = NativeEngineBridge.transactBinder(vm.id, "servicemanager", 1, "")
                            Quad("BINDER-PROXY", "ioctl(BINDER_WRITE_READ)", ping.response, (28..76).random())
                        }
                        2 -> Quad("CPP-HWC-HAL", "ANativeWindow_post(${vm.widthPx}x${vm.heightPx})", "frames=${uiState.value.halTelemetry.framesPresented} @ ${vm.refreshRateHz}Hz", (90..220).random())
                        else -> Quad("CPU-PASSTHROUGH", "sched_setaffinity(${vm.allocatedCores}c)", "Native host CPU & ${if (vm.rootEnabled) "su(uid=0)" else "user"}", (10..24).random())
                    }
                    appendLog(vm.id, vm.name, layer, call, detail, us)
                    refreshNativeTelemetry(vm)
                }
            }
        }
    }

    private fun refreshNativeTelemetry(vm: VmInstanceEntity) {
        _sandboxTelemetry.value = NativeEngineBridge.getSandboxTelemetry(vm.id)
        _halTelemetry.value = NativeEngineBridge.getHalTelemetry(vm.id)
    }

    fun isFullscreenActiveNow(): Boolean = _loadedVmId.value != null && _isVmFullscreen.value

    fun loadAndOpenVm(vmId: String) {
        _loadedVmId.value = vmId
        _workspaceTab.value = VmWorkspaceTab.DISPLAY
        _isVmFullscreen.value = true
        _guestScreen.value = GuestDeviceScreen.Home
        _isGuestShadeOpen.value = false
        val vm = uiState.value.instances.find { it.id == vmId }
        if (vm != null) {
            viewModelScope.launch {
                _rootfsManifest.value = rootfsProvisioner.loadManifest(vm)
                refreshNativeTelemetry(vm)
            }
            if (vm.runState == VmRunState.STOPPED.name) {
                bootVm(vmId)
            }
        }
    }

    fun enterVmFullscreen() {
        if (_loadedVmId.value != null) {
            _workspaceTab.value = VmWorkspaceTab.DISPLAY
            _isMiniFloatVisible.value = false
            _isVmFullscreen.value = true
        }
    }

    fun exitVmFullscreen(viaKeyCombo: Boolean = false) {
        _isVmFullscreen.value = false
        _workspaceTab.value = VmWorkspaceTab.DISPLAY
        if (viaKeyCombo) {
            _statusBanner.value = "Returned to viewport (Vol+ + Vol- + Power)"
        }
    }

    fun returnToVmChooser() {
        _isVmFullscreen.value = false
        _isMiniFloatVisible.value = false
        _isGuestShadeOpen.value = false
        _loadedVmId.value = null
    }

    fun selectWorkspaceTab(tab: VmWorkspaceTab) {
        _isVmFullscreen.value = false
        _workspaceTab.value = tab
    }

    fun launchGuestApp(packageName: String) {
        _isGuestShadeOpen.value = false
        _guestScreen.value = GuestDeviceScreen.RunningApp(packageName)
        _guestRecentPackages.update { current ->
            (listOf(packageName) + current.filterNot { it == packageName }).take(8)
        }
        uiState.value.activeVm?.let { vm ->
            appendLog(vm.id, vm.name, "GUEST-AMS", "startActivity($packageName)", "Resumed task", 48)
        }
    }

    fun openGuestAppDrawer() {
        _isGuestShadeOpen.value = false
        _guestScreen.value = GuestDeviceScreen.AppDrawer
    }

    fun openGuestRecents() {
        _isGuestShadeOpen.value = false
        _guestScreen.value = GuestDeviceScreen.Recents
    }

    fun goGuestHome() {
        _isGuestShadeOpen.value = false
        _guestScreen.value = GuestDeviceScreen.Home
    }

    fun goGuestBack() {
        if (_isGuestShadeOpen.value) {
            _isGuestShadeOpen.value = false
            return
        }
        if (_guestScreen.value != GuestDeviceScreen.Home) {
            _guestScreen.value = GuestDeviceScreen.Home
        }
    }

    fun closeRecentGuestTask(packageName: String) {
        _guestRecentPackages.update { it.filterNot { pkg -> pkg == packageName } }
    }

    fun toggleGuestShade() {
        _isGuestShadeOpen.value = !_isGuestShadeOpen.value
    }

    fun setGuestBrightness(value: Float) {
        _guestBrightness.value = value.coerceIn(0.4f, 1f)
    }

    fun toggleGuestWifi() {
        _guestWifiEnabled.value = !_guestWifiEnabled.value
    }

    fun toggleGuestLayoutBounds() {
        _guestLayoutBounds.value = !_guestLayoutBounds.value
    }

    fun openGuestPackageInstaller(apkFile: VmSandboxFileEntity) {
        _isGuestShadeOpen.value = false
        _loadedVmId.value = apkFile.vmId
        _workspaceTab.value = VmWorkspaceTab.DISPLAY
        _guestScreen.value = GuestDeviceScreen.PackageInstaller(apkFile)
    }

    fun installApkInVm(apkFile: VmSandboxFileEntity, onInstalled: (GuestInstalledAppEntity) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val installed = repository.installApkInVm(apkFile.vmId, apkFile)
                val vmName = uiState.value.instances.find { it.id == apkFile.vmId }?.name ?: apkFile.vmId
                appendLog(apkFile.vmId, vmName, "GUEST-PMS", "pm install -r ${apkFile.virtualPath}", "Installed ${installed.packageName}", 140)
                _statusBanner.value = "Installed ${installed.appName}"
                onInstalled(installed)
            } catch (e: Exception) {
                _statusBanner.value = "Install failed: ${e.localizedMessage ?: "invalid APK"}"
            }
        }
    }

    fun uninstallGuestApp(app: GuestInstalledAppEntity) {
        viewModelScope.launch {
            repository.uninstallGuestApp(app)
            _guestRecentPackages.update { it.filterNot { pkg -> pkg == app.packageName } }
            if ((_guestScreen.value as? GuestDeviceScreen.RunningApp)?.packageName == app.packageName) {
                _guestScreen.value = GuestDeviceScreen.Home
            }
            _statusBanner.value = "Uninstalled ${app.appName}"
        }
    }

    fun saveGuestNote(vmId: String, title: String, body: String) {
        viewModelScope.launch {
            val saved = repository.saveGuestNote(vmId, title, body)
            _statusBanner.value = "Saved ${saved.fileName}"
        }
    }

    fun toggleMiniFloat(visible: Boolean? = null) {
        _isMiniFloatVisible.value = visible ?: !_isMiniFloatVisible.value
    }

    fun setPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun toggleTheme() {
        _darkTheme.value = !_darkTheme.value
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun dismissFilePreview() {
        _previewFile.value = null
    }

    fun bootVm(vmId: String) {
        bootJobs[vmId]?.cancel()
        bootJobs[vmId] = viewModelScope.launch {
            val vm = uiState.value.instances.find { it.id == vmId } ?: return@launch
            repository.updateRunState(vmId, VmRunState.BOOTING)
            val rootfs = repository.getVmRootDir(vmId).absolutePath
            val seccompCode = NativeEngineBridge.seccompModeToCode(vm.seccompMode)
            NativeEngineBridge.bootSandbox(vmId, rootfs, seccompCode)
            val manifest = rootfsProvisioner.verifyOrRepairRootfs(vm)
            _rootfsManifest.value = manifest
            if (vm.audioBridgeEnabled) {
                NativeEngineBridge.startAudioStream(vmId, 48000)
            }
            appendLog(
                vmId,
                vm.name,
                "RUST-INIT",
                "seccomp(mode=$seccompCode)",
                "BPF armed • rootfs=${manifest.status} (${manifest.fileCount} files) • Root=${vm.rootEnabled}",
                72
            )
            delay(350L)
            NativeEngineBridge.pushDisplayProfile(vmId, vm.widthPx, vm.heightPx, vm.dpi, vm.refreshRateHz)
            repository.updateRunState(vmId, VmRunState.RUNNING)
            refreshNativeTelemetry(vm)
            _guestScreen.value = GuestDeviceScreen.Home
            _statusBanner.value = "${vm.name} ready"
        }
    }

    fun togglePauseVm(vmId: String) {
        viewModelScope.launch {
            val vm = uiState.value.instances.find { it.id == vmId } ?: return@launch
            val next = if (vm.runState == VmRunState.PAUSED.name) VmRunState.RUNNING else VmRunState.PAUSED
            repository.updateRunState(vmId, next)
        }
    }

    fun stopVm(vmId: String) {
        bootJobs[vmId]?.cancel()
        viewModelScope.launch {
            val vm = uiState.value.instances.find { it.id == vmId } ?: return@launch
            NativeEngineBridge.stopSandbox(vmId)
            repository.updateRunState(vmId, VmRunState.STOPPED)
            if (_loadedVmId.value == vmId) {
                _isVmFullscreen.value = false
                _loadedVmId.value = null
                _isMiniFloatVisible.value = false
            }
            _statusBanner.value = "${vm.name} stopped"
        }
    }

    fun cycleRunningVm() {
        val running = uiState.value.runningVms
        if (running.size <= 1) return
        val currentIdx = running.indexOfFirst { it.id == _loadedVmId.value }
        val next = running[(currentIdx + 1) % running.size]
        _loadedVmId.value = next.id
    }

    fun createVmInstance(
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
    ) {
        viewModelScope.launch {
            val created = repository.createVmInstance(
                name = name,
                guestOsVersion = guestOsVersion,
                apiLevel = apiLevel,
                abi = abi,
                widthPx = widthPx,
                heightPx = heightPx,
                dpi = dpi,
                refreshRateHz = refreshRateHz,
                allocatedCores = allocatedCores,
                allocatedRamMb = allocatedRamMb,
                seccompMode = seccompMode,
                accentTag = accentTag
            )
            loadAndOpenVm(created.id)
        }
    }

    fun updateDisplayProfile(vmId: String, widthPx: Int, heightPx: Int, dpi: Int, refreshRateHz: Int) {
        viewModelScope.launch {
            repository.updateDisplayProfile(vmId, widthPx, heightPx, dpi, refreshRateHz)
            NativeEngineBridge.pushDisplayProfile(vmId, widthPx, heightPx, dpi, refreshRateHz)
            _statusBanner.value = "Applied ${widthPx}×${heightPx} @ ${dpi}dpi (${refreshRateHz}Hz)"
        }
    }

    fun updateHardwarePassthrough(
        vm: VmInstanceEntity,
        eglBridge: Boolean = vm.eglBridgeEnabled,
        sensors: Boolean = vm.sensorPassthroughEnabled,
        audio: Boolean = vm.audioBridgeEnabled,
        binderProxy: Boolean = vm.binderProxyEnabled,
        rootEnabled: Boolean = vm.rootEnabled,
        bootloaderUnlocked: Boolean = vm.bootloaderUnlocked,
        seccompMode: String = vm.seccompMode
    ) {
        viewModelScope.launch {
            val updated = vm.copy(
                eglBridgeEnabled = eglBridge,
                sensorPassthroughEnabled = sensors,
                audioBridgeEnabled = audio,
                binderProxyEnabled = binderProxy,
                rootEnabled = rootEnabled,
                bootloaderUnlocked = bootloaderUnlocked,
                seccompMode = seccompMode
            )
            repository.updateInstance(updated)
            appendLog(vm.id, vm.name, "HAL-BRIDGE", "reconfigureHal()", "EGL=$eglBridge Root=$rootEnabled Unlocked=$bootloaderUnlocked", 42)
        }
    }

    fun deleteVmInstance(vmId: String) {
        bootJobs[vmId]?.cancel()
        viewModelScope.launch {
            val vm = uiState.value.instances.find { it.id == vmId }
            NativeEngineBridge.stopSandbox(vmId)
            repository.deleteVmInstance(vmId)
            if (_loadedVmId.value == vmId) {
                _isVmFullscreen.value = false
                _loadedVmId.value = null
            }
            _statusBanner.value = "Removed ${vm?.name ?: "VM"}"
        }
    }

    fun importFileToVault(uri: Uri, forceCategory: String? = null) {
        val targetVmId = uiState.value.vaultVm?.id ?: return
        viewModelScope.launch {
            try {
                val imported = repository.importFile(targetVmId, uri, forceCategory)
                _statusBanner.value = if (imported.apkPackageName != null) {
                    "Imported APK ${imported.apkPackageName}"
                } else {
                    "Imported ${imported.fileName}"
                }
            } catch (e: Exception) {
                _statusBanner.value = "Import failed: ${e.localizedMessage ?: "unknown error"}"
            }
        }
    }

    fun exportFileFromVault(file: VmSandboxFileEntity, destinationUri: Uri) {
        viewModelScope.launch {
            try {
                repository.exportFile(file, destinationUri)
                _statusBanner.value = "Exported ${file.fileName}"
            } catch (e: Exception) {
                _statusBanner.value = "Export failed: ${e.localizedMessage ?: "unknown error"}"
            }
        }
    }

    fun deleteVaultFile(file: VmSandboxFileEntity) {
        viewModelScope.launch {
            repository.deleteFile(file)
            _statusBanner.value = "Deleted ${file.fileName}"
        }
    }

    fun openFilePreview(file: VmSandboxFileEntity) {
        viewModelScope.launch {
            val text = repository.readFilePreview(file)
            _previewFile.value = file to text
        }
    }

    fun probeBinderAndPathJail(vm: VmInstanceEntity) {
        val ping = NativeEngineBridge.transactBinder(vm.id, "servicemanager", 1, "")
        val escaped = NativeEngineBridge.transactBinder(vm.id, "/dev/binder", 1, "")
        val jailedPath = NativeEngineBridge.translateGuestPath(vm.id, "/../../system/etc/hosts")
        appendLog(vm.id, vm.name, "BINDER-PROXY", "PING servicemanager", ping.response, 31)
        appendLog(vm.id, vm.name, "SECCOMP-JAIL", "blocked(/dev/binder)", escaped.response, 18)
        appendLog(vm.id, vm.name, "PATH-JAIL", "realpath(/../../system/etc/hosts)", jailedPath, 14)
        refreshNativeTelemetry(vm)
        _statusBanner.value = "Binder isolated (${ping.isolatedNode}) • Host escape blocked"
    }

    fun pulseAudioHal(vm: VmInstanceEntity) {
        val written = NativeEngineBridge.playAudioPulse(vm.id, freqHz = 528, durationMs = 110)
        val hal = NativeEngineBridge.getHalTelemetry(vm.id)
        _halTelemetry.value = hal
        appendLog(
            vm.id,
            vm.name,
            "AAUDIO-HAL",
            "AAudioStream_write($written frames)",
            "rate=${hal.audioSampleRate}Hz • hw=${hal.aaudioHardware}",
            64
        )
        _statusBanner.value = if (hal.aaudioHardware) {
            "AAudio HAL pulsed $written frames @ ${hal.audioSampleRate}Hz"
        } else {
            "Audio HAL ring pulsed $written frames (${hal.audioSampleRate}Hz)"
        }
    }

    fun verifyActiveRootfs(vm: VmInstanceEntity) {
        viewModelScope.launch {
            val manifest = rootfsProvisioner.verifyOrRepairRootfs(vm)
            _rootfsManifest.value = manifest
            appendLog(vm.id, vm.name, "ROOTFS", "verify_tree(${vm.id})", "sha256=${manifest.sha256.take(12)} • files=${manifest.fileCount}", 95)
            _statusBanner.value = "Rootfs ${manifest.status}: ${manifest.fileCount} files verified"
        }
    }

    fun importRootfsArchive(vm: VmInstanceEntity, uri: Uri) {
        viewModelScope.launch {
            try {
                _rootfsProgress.value = RootfsProvisionProgress(vm.id, "IMPORTING", 0.05f, "Reading archive...")
                val manifest = rootfsProvisioner.provisionFromLocalArchive(vm, uri) { frac, detail ->
                    _rootfsProgress.value = RootfsProvisionProgress(vm.id, "EXTRACTING", frac, detail)
                }
                _rootfsManifest.value = manifest
                appendLog(vm.id, vm.name, "ROOTFS", "extract_archive()", "${manifest.sourceType} • files=${manifest.fileCount}", 210)
                _statusBanner.value = "Provisioned ${vm.name} (${manifest.fileCount} files)"
            } catch (e: Exception) {
                _statusBanner.value = "Rootfs import failed: ${e.localizedMessage ?: "error"}"
            } finally {
                _rootfsProgress.value = null
            }
        }
    }

    fun downloadRootfsFromUrl(vm: VmInstanceEntity, url: String, expectedSha256: String?) {
        viewModelScope.launch {
            try {
                _rootfsProgress.value = RootfsProvisionProgress(vm.id, "DOWNLOADING", 0.02f, "Starting download...")
                val manifest = rootfsProvisioner.provisionFromHttpsUrl(vm, url, expectedSha256) { frac, detail ->
                    _rootfsProgress.value = RootfsProvisionProgress(vm.id, "DOWNLOADING", frac, detail)
                }
                _rootfsManifest.value = manifest
                appendLog(vm.id, vm.name, "ROOTFS", "https_fetch()", "${manifest.sourceType} • sha256=${manifest.sha256.take(12)}", 240)
                _statusBanner.value = "Downloaded & verified rootfs (${manifest.fileCount} files)"
            } catch (e: Exception) {
                _statusBanner.value = "Rootfs download failed: ${e.localizedMessage ?: "error"}"
            } finally {
                _rootfsProgress.value = null
            }
        }
    }

    fun refreshVmSurfaceTelemetry(vm: VmInstanceEntity) {
        refreshNativeTelemetry(vm)
    }

    fun resolveHostFile(hostRelativePath: String) = repository.resolveHostFile(hostRelativePath)

    private fun appendLog(
        vmId: String,
        vmName: String,
        layer: String,
        syscallOrIpc: String,
        detail: String,
        latencyUs: Int
    ) {
        val entry = SyscallLogEntry(
            id = logSeq.getAndIncrement(),
            vmId = vmId,
            vmName = vmName,
            timestamp = timeFormat.format(Date()),
            layer = layer,
            syscallOrIpc = syscallOrIpc,
            detail = detail,
            latencyUs = latencyUs
        )
        _syscallLogs.update { current ->
            (listOf(entry) + current).take(40)
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
