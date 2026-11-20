package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.GuestInstalledAppEntity
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmRunState
import com.example.data.local.VmSandboxFileEntity
import com.example.engine.HypervisorCatalog
import com.example.ui.HypervisorUiState
import com.example.ui.components.VirtualGuestDisplay
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ViewportScreen(
    state: HypervisorUiState,
    resolveHostFile: (String) -> File,
    onBootVm: (String) -> Unit,
    onTogglePauseVm: (String) -> Unit,
    onLaunchGuestApp: (String) -> Unit,
    onOpenGuestDrawer: () -> Unit,
    onOpenGuestRecents: () -> Unit,
    onGoGuestHome: () -> Unit,
    onGoGuestBack: () -> Unit,
    onCloseRecentTask: (String) -> Unit,
    onToggleShade: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onToggleWifi: () -> Unit,
    onToggleLayoutBounds: () -> Unit,
    onInstallApkRequest: (VmSandboxFileEntity) -> Unit,
    onInstallApkConfirm: (VmSandboxFileEntity, (GuestInstalledAppEntity) -> Unit) -> Unit,
    onUninstallApp: (GuestInstalledAppEntity) -> Unit,
    onSaveNote: (String, String, String) -> Unit,
    onOpenHostVault: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onEnterSystemPip: () -> Unit,
    onToggleMiniFloat: () -> Unit,
    onApplyDisplayProfile: (vmId: String, widthPx: Int, heightPx: Int, dpi: Int, hz: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeVm = state.activeVm
    var showSpooferDialog by remember { mutableStateOf(false) }

    if (activeVm == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No VM instance selected", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val vmFiles = remember(state.allFiles, activeVm.id) {
        state.allFiles.filter { it.vmId == activeVm.id }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        val isWide = maxWidth >= 700.dp

        if (isWide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        VirtualGuestDisplay(
                            vm = activeVm,
                            guestScreen = state.guestScreen,
                            guestApps = state.activeVmApps,
                            recentPackages = state.guestRecentPackages,
                            isShadeOpen = state.isGuestShadeOpen,
                            guestBrightness = state.guestBrightness,
                            guestWifiEnabled = state.guestWifiEnabled,
                            guestLayoutBounds = state.guestLayoutBounds,
                            hostHardware = state.hostHardware,
                            hostSensors = state.hostSensors,
                            vmLogs = state.syscallLogs,
                            vmFiles = vmFiles,
                            compactPipMode = false,
                            resolveHostFile = resolveHostFile,
                            onBootVm = { onBootVm(activeVm.id) },
                            onTogglePauseVm = { onTogglePauseVm(activeVm.id) },
                            onLaunchApp = onLaunchGuestApp,
                            onOpenDrawer = onOpenGuestDrawer,
                            onOpenRecents = onOpenGuestRecents,
                            onGoHome = onGoGuestHome,
                            onGoBack = onGoGuestBack,
                            onCloseRecentTask = onCloseRecentTask,
                            onToggleShade = onToggleShade,
                            onBrightnessChange = onBrightnessChange,
                            onToggleWifi = onToggleWifi,
                            onToggleLayoutBounds = onToggleLayoutBounds,
                            onApplyDisplay = { w, h, d, hz -> onApplyDisplayProfile(activeVm.id, w, h, d, hz) },
                            onInstallApkRequest = onInstallApkRequest,
                            onInstallApkConfirm = onInstallApkConfirm,
                            onUninstallApp = onUninstallApp,
                            onSaveNote = { title, body -> onSaveNote(activeVm.id, title, body) },
                            onOpenHostVault = onOpenHostVault,
                            modifier = Modifier.fillMaxHeight()
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ViewportControlsBar(
                        vm = activeVm,
                        isMiniFloatActive = state.isMiniFloatVisible,
                        onEnterFullscreen = onEnterFullscreen,
                        onEnterSystemPip = onEnterSystemPip,
                        onToggleMiniFloat = onToggleMiniFloat,
                        onTogglePause = { onTogglePauseVm(activeVm.id) },
                        onOpenSpoofer = { showSpooferDialog = true },
                        showSpooferButton = false
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        DisplaySpooferContent(
                            vm = activeVm,
                            onApply = { w, h, dpi, hz ->
                                onApplyDisplayProfile(activeVm.id, w, h, dpi, hz)
                            },
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ViewportControlsBar(
                    vm = activeVm,
                    isMiniFloatActive = state.isMiniFloatVisible,
                    onEnterFullscreen = onEnterFullscreen,
                    onEnterSystemPip = onEnterSystemPip,
                    onToggleMiniFloat = onToggleMiniFloat,
                    onTogglePause = { onTogglePauseVm(activeVm.id) },
                    onOpenSpoofer = { showSpooferDialog = true },
                    showSpooferButton = true
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    VirtualGuestDisplay(
                        vm = activeVm,
                        guestScreen = state.guestScreen,
                        guestApps = state.activeVmApps,
                        recentPackages = state.guestRecentPackages,
                        isShadeOpen = state.isGuestShadeOpen,
                        guestBrightness = state.guestBrightness,
                        guestWifiEnabled = state.guestWifiEnabled,
                        guestLayoutBounds = state.guestLayoutBounds,
                        hostHardware = state.hostHardware,
                        hostSensors = state.hostSensors,
                        vmLogs = state.syscallLogs,
                        vmFiles = vmFiles,
                        compactPipMode = false,
                        resolveHostFile = resolveHostFile,
                        onBootVm = { onBootVm(activeVm.id) },
                        onTogglePauseVm = { onTogglePauseVm(activeVm.id) },
                        onLaunchApp = onLaunchGuestApp,
                        onOpenDrawer = onOpenGuestDrawer,
                        onOpenRecents = onOpenGuestRecents,
                        onGoHome = onGoGuestHome,
                        onGoBack = onGoGuestBack,
                        onCloseRecentTask = onCloseRecentTask,
                        onToggleShade = onToggleShade,
                        onBrightnessChange = onBrightnessChange,
                        onToggleWifi = onToggleWifi,
                        onToggleLayoutBounds = onToggleLayoutBounds,
                        onApplyDisplay = { w, h, d, hz -> onApplyDisplayProfile(activeVm.id, w, h, d, hz) },
                        onInstallApkRequest = onInstallApkRequest,
                        onInstallApkConfirm = onInstallApkConfirm,
                        onUninstallApp = onUninstallApp,
                        onSaveNote = { title, body -> onSaveNote(activeVm.id, title, body) },
                        onOpenHostVault = onOpenHostVault,
                        modifier = Modifier.fillMaxHeight()
                    )
                }
            }
        }
    }

    if (showSpooferDialog) {
        AlertDialog(
            onDismissRequest = { showSpooferDialog = false },
            title = { Text("Display & DPI Spoofer", style = MaterialTheme.typography.titleLarge) },
            text = {
                DisplaySpooferContent(
                    vm = activeVm,
                    onApply = { w, h, dpi, hz ->
                        onApplyDisplayProfile(activeVm.id, w, h, dpi, hz)
                        showSpooferDialog = false
                    },
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showSpooferDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ViewportControlsBar(
    vm: VmInstanceEntity,
    isMiniFloatActive: Boolean,
    onEnterFullscreen: () -> Unit,
    onEnterSystemPip: () -> Unit,
    onToggleMiniFloat: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSpoofer: () -> Unit,
    showSpooferButton: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onEnterFullscreen,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.testTag("enter_fullscreen_button")
        ) {
            Icon(Icons.Rounded.Fullscreen, contentDescription = "Fullscreen VM", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Fullscreen", style = MaterialTheme.typography.labelSmall)
        }

        OutlinedButton(
            onClick = onEnterSystemPip,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.testTag("enter_system_pip_button")
        ) {
            Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = stringResource(R.string.cd_pip_button), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_enter_pip), style = MaterialTheme.typography.labelSmall)
        }

        OutlinedButton(
            onClick = onToggleMiniFloat,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.testTag("toggle_mini_float_button")
        ) {
            Icon(Icons.Rounded.Layers, contentDescription = stringResource(R.string.cd_float_button), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (isMiniFloatActive) "Hide Float" else stringResource(R.string.action_float_overlay), style = MaterialTheme.typography.labelSmall)
        }

        if (showSpooferButton) {
            OutlinedButton(
                onClick = onOpenSpoofer,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("open_display_spoofer_button")
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("${vm.dpi}dpi / ${vm.refreshRateHz}Hz", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (vm.runState == VmRunState.RUNNING.name || vm.runState == VmRunState.PAUSED.name) {
            OutlinedButton(
                onClick = onTogglePause,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("viewport_pause_button")
            ) {
                Icon(
                    imageVector = if (vm.runState == VmRunState.PAUSED.name) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (vm.runState == VmRunState.PAUSED.name) "Resume" else "Pause", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DisplaySpooferContent(
    vm: VmInstanceEntity,
    onApply: (widthPx: Int, heightPx: Int, dpi: Int, refreshRateHz: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember(vm.id, vm.widthPx) { mutableFloatStateOf(vm.widthPx.toFloat()) }
    var heightPx by remember(vm.id, vm.heightPx) { mutableFloatStateOf(vm.heightPx.toFloat()) }
    var dpi by remember(vm.id, vm.dpi) { mutableFloatStateOf(vm.dpi.toFloat()) }
    var refreshHz by remember(vm.id, vm.refreshRateHz) { mutableIntStateOf(vm.refreshRateHz) }

    val swDp = (minOf(widthPx, heightPx) / (dpi / 160f)).roundToInt()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Device Presets", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HypervisorCatalog.displayPresets.forEach { preset ->
                FilterChip(
                    selected = widthPx.roundToInt() == preset.widthPx &&
                        heightPx.roundToInt() == preset.heightPx &&
                        dpi.roundToInt() == preset.dpi,
                    onClick = {
                        widthPx = preset.widthPx.toFloat()
                        heightPx = preset.heightPx.toFloat()
                        dpi = preset.dpi.toFloat()
                        refreshHz = preset.refreshRateHz
                    },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("${widthPx.roundToInt()} × ${heightPx.roundToInt()} px", style = MaterialTheme.typography.labelMedium)
                }
                Text("sw${swDp}dp • ${dpi.roundToInt()}dpi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }

        Text("Width: ${widthPx.roundToInt()} px", style = MaterialTheme.typography.labelMedium)
        Slider(value = widthPx, onValueChange = { widthPx = (it / 20f).roundToInt() * 20f }, valueRange = 540f..1600f)

        Text("Height: ${heightPx.roundToInt()} px", style = MaterialTheme.typography.labelMedium)
        Slider(value = heightPx, onValueChange = { heightPx = (it / 20f).roundToInt() * 20f }, valueRange = 960f..3200f)

        Text("Density: ${dpi.roundToInt()} dpi", style = MaterialTheme.typography.labelMedium)
        Slider(value = dpi, onValueChange = { dpi = (it / 20f).roundToInt() * 20f }, valueRange = 160f..640f)

        Text("Virtual Refresh Rate", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 90, 120, 144).forEach { hz ->
                FilterChip(selected = refreshHz == hz, onClick = { refreshHz = hz }, label = { Text("${hz}Hz", style = MaterialTheme.typography.labelSmall) })
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { onApply(widthPx.roundToInt(), heightPx.roundToInt(), dpi.roundToInt(), refreshHz) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("apply_display_profile_button")
        ) { Text("Apply to ${vm.name}") }
    }
}
