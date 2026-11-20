package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.VmInstanceEntity
import com.example.engine.CleanRoomLayerSpec
import com.example.engine.HypervisorCatalog
import com.example.ui.HypervisorUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun EngineScreen(
    state: HypervisorUiState,
    onUpdatePassthrough: (
        vm: VmInstanceEntity,
        eglBridge: Boolean,
        sensors: Boolean,
        audio: Boolean,
        binderProxy: Boolean,
        rootEnabled: Boolean,
        bootloaderUnlocked: Boolean,
        seccompMode: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeVm = state.activeVm
    var expandedLayer by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Hardware & Sandbox Settings",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "JNI Bridge: ${state.bridgeRuntimeSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Host Hardware Telemetry Card
            item {
                val hw = state.hostHardware
                val usedRamRatio = if (hw.totalRamMb > 0L) {
                    ((hw.totalRamMb - hw.availableRamMb).toFloat() / hw.totalRamMb.toFloat()).coerceIn(0f, 1f)
                } else 0.5f

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Host Silicon & GPU Acceleration", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = "${hw.deviceModel} • ${hw.cpuCores} CPU Cores • ABIs: ${hw.allAbis.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "GPU Renderer: ${hw.gpuRenderer} • Vulkan: ${if (hw.vulkanSupported) "Supported" else "GLES20"} • HW Accel: ${if (hw.hardwareAccelerated) "ON" else "OFF"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        LinearProgressIndicator(
                            progress = { usedRamRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Host RAM Free: ${hw.availableRamMb} / ${hw.totalRamMb} MB",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "Temp: ${hw.batteryTempC}°C • ${hw.batteryLevelPct}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        // CPU Core Frequency Strip
                        if (hw.coreFreqMhz.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                hw.coreFreqMhz.forEachIndexed { idx, mhz ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "C$idx: ${mhz?.let { "${it}MHz" } ?: "Online"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            Text(
                                text = "IMU: Acc(${"%.2f".format(state.hostSensors.accelX)}, ${"%.2f".format(state.hostSensors.accelY)}, ${"%.2f".format(state.hostSensors.accelZ)}) • Lux ${"%.0f".format(state.hostSensors.lux)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Per-VM Passthrough & Unlock/Root Controls
            if (activeVm != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Text(
                                    text = "VM Hardware, Bootloader & Root (${activeVm.name})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            PassthroughToggleRow(
                                title = "Factory Unlocked Bootloader",
                                subtitle = "ro.boot.flash.locked=0 • ro.oem_unlock_supported=1",
                                checked = activeVm.bootloaderUnlocked,
                                tag = "toggle_bootloader_unlocked",
                                onCheckedChange = { checked ->
                                    onUpdatePassthrough(
                                        activeVm,
                                        activeVm.eglBridgeEnabled,
                                        activeVm.sensorPassthroughEnabled,
                                        activeVm.audioBridgeEnabled,
                                        activeVm.binderProxyEnabled,
                                        activeVm.rootEnabled,
                                        checked,
                                        activeVm.seccompMode
                                    )
                                }
                            )
                            PassthroughToggleRow(
                                title = "Root Access (su / uid=0)",
                                subtitle = "/system/xbin/su binary & persist.kestrel.root=1",
                                checked = activeVm.rootEnabled,
                                tag = "toggle_root_access",
                                onCheckedChange = { checked ->
                                    onUpdatePassthrough(
                                        activeVm,
                                        activeVm.eglBridgeEnabled,
                                        activeVm.sensorPassthroughEnabled,
                                        activeVm.audioBridgeEnabled,
                                        activeVm.binderProxyEnabled,
                                        checked,
                                        activeVm.bootloaderUnlocked,
                                        activeVm.seccompMode
                                    )
                                }
                            )
                            PassthroughToggleRow(
                                title = "Hardware EGL / GLES Acceleration",
                                subtitle = "hwcomposer.kestrel.so + gralloc ANativeWindow zero-copy",
                                checked = activeVm.eglBridgeEnabled,
                                tag = "toggle_egl_bridge",
                                onCheckedChange = { checked ->
                                    onUpdatePassthrough(
                                        activeVm,
                                        checked,
                                        activeVm.sensorPassthroughEnabled,
                                        activeVm.audioBridgeEnabled,
                                        activeVm.binderProxyEnabled,
                                        activeVm.rootEnabled,
                                        activeVm.bootloaderUnlocked,
                                        activeVm.seccompMode
                                    )
                                }
                            )
                            PassthroughToggleRow(
                                title = "Host IMU Sensor Passthrough",
                                subtitle = "Forward accelerometer, gyro & light via sensors.kestrel.so",
                                checked = activeVm.sensorPassthroughEnabled,
                                tag = "toggle_sensor_bridge",
                                onCheckedChange = { checked ->
                                    onUpdatePassthrough(
                                        activeVm,
                                        activeVm.eglBridgeEnabled,
                                        checked,
                                        activeVm.audioBridgeEnabled,
                                        activeVm.binderProxyEnabled,
                                        activeVm.rootEnabled,
                                        activeVm.bootloaderUnlocked,
                                        activeVm.seccompMode
                                    )
                                }
                            )
                            PassthroughToggleRow(
                                title = "AAudio Hardware Bridge",
                                subtitle = "audio.primary.kestrel.so low-latency PCM pipe",
                                checked = activeVm.audioBridgeEnabled,
                                tag = "toggle_audio_bridge",
                                onCheckedChange = { checked ->
                                    onUpdatePassthrough(
                                        activeVm,
                                        activeVm.eglBridgeEnabled,
                                        activeVm.sensorPassthroughEnabled,
                                        checked,
                                        activeVm.binderProxyEnabled,
                                        activeVm.rootEnabled,
                                        activeVm.bootloaderUnlocked,
                                        activeVm.seccompMode
                                    )
                                }
                            )
                            PassthroughToggleRow(
                                title = "Userspace Binder IPC Proxy",
                                subtitle = "Isolate guest servicemanager from host /dev/binder",
                                checked = activeVm.binderProxyEnabled,
                                tag = "toggle_binder_proxy",
                                onCheckedChange = { checked ->
                                    onUpdatePassthrough(
                                        activeVm,
                                        activeVm.eglBridgeEnabled,
                                        activeVm.sensorPassthroughEnabled,
                                        activeVm.audioBridgeEnabled,
                                        checked,
                                        activeVm.rootEnabled,
                                        activeVm.bootloaderUnlocked,
                                        activeVm.seccompMode
                                    )
                                }
                            )

                            Text("Syscall Interception Mode", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("SECCOMP_BPF + PTRACE", "PTRACE_SYSCALL", "USER_LAND_NS").forEach { mode ->
                                    FilterChip(
                                        selected = activeVm.seccompMode == mode,
                                        onClick = {
                                            onUpdatePassthrough(
                                                activeVm,
                                                activeVm.eglBridgeEnabled,
                                                activeVm.sensorPassthroughEnabled,
                                                activeVm.audioBridgeEnabled,
                                                activeVm.binderProxyEnabled,
                                                activeVm.rootEnabled,
                                                activeVm.bootloaderUnlocked,
                                                mode
                                            )
                                        },
                                        label = { Text(mode, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Syscall / Binder IPC Stream
            item {
                Text("Live Syscall & Binder IPC Trace", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.syscallLogs.isEmpty()) {
                            Text(
                                text = "Waiting for active VM syscall events…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.syscallLogs.take(10).forEach { log ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${log.timestamp} [${log.layer}] ${log.syscallOrIpc}",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${log.latencyUs}µs",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Text(
                                    text = "  ${log.vmName}: ${log.detail}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Clean-Room 3-Layer Stack Reference
            item {
                Text("Clean-Room 3-Layer Architecture Spec", style = MaterialTheme.typography.titleMedium)
            }
            items(HypervisorCatalog.cleanRoomLayers, key = { it.layerNumber }) { spec ->
                LayerSpecCard(
                    spec = spec,
                    expanded = expandedLayer == spec.layerNumber,
                    onToggleExpand = {
                        expandedLayer = if (expandedLayer == spec.layerNumber) null else spec.layerNumber
                    }
                )
            }
        }
    }
}

@Composable
private fun PassthroughToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun LayerSpecCard(
    spec: CleanRoomLayerSpec,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Layer ${spec.layerNumber}: ${spec.title}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${spec.languages} • ${spec.artifactName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse layer spec" else "Expand layer spec"
                )
            }
            Text(
                text = spec.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.responsibilities.forEach { item ->
                        Text(
                            text = "• $item",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(
                                text = spec.interfaceSnippet,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
