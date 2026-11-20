package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.unit.dp
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmRunState
import com.example.engine.HypervisorCatalog
import com.example.ui.HypervisorUiState
import kotlin.math.roundToInt

@Composable
fun InstancesScreen(
    state: HypervisorUiState,
    onLoadAndBootVm: (String) -> Unit,
    onStopVm: (String) -> Unit,
    onDeleteVm: (String) -> Unit,
    onCreateVm: (
        name: String,
        osVersion: String,
        apiLevel: Int,
        abi: String,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
        refreshRateHz: Int,
        cores: Int,
        ramMb: Int,
        seccompMode: String,
        accentTag: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val hw = state.hostHardware

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Choose Virtual Device",
                                style = MaterialTheme.typography.titleMedium
                            )
                            OutlinedButton(
                                onClick = { showCreateDialog = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("create_vm_button")
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New VM", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(
                            text = "Host: ${hw.cpuCores} CPU Cores (${hw.cpuAbi}) • ${hw.availableRamMb}/${hw.totalRamMb} MB RAM • HW Accel: ${if (hw.hardwareAccelerated) hw.gpuRenderer else "Software"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.instances, key = { it.id }) { vm ->
                VmChooserRow(
                    vm = vm,
                    canDelete = state.instances.size > 1,
                    onLoadAndBoot = { onLoadAndBootVm(vm.id) },
                    onStop = { onStopVm(vm.id) },
                    onDelete = { onDeleteVm(vm.id) }
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateVmDialog(
            hostCores = state.hostHardware.cpuCores,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, os, api, abi, w, h, dpi, hz, cores, ram, seccomp, accent ->
                showCreateDialog = false
                onCreateVm(name, os, api, abi, w, h, dpi, hz, cores, ram, seccomp, accent)
            }
        )
    }
}

@Composable
private fun VmChooserRow(
    vm: VmInstanceEntity,
    canDelete: Boolean,
    onLoadAndBoot: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val isRunning = vm.runState == VmRunState.RUNNING.name || vm.runState == VmRunState.PAUSED.name

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vm_card_${vm.id}"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        text = vm.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${vm.guestOsVersion} • ${vm.abi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = vm.runState,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = buildString {
                    append("${vm.widthPx}×${vm.heightPx} • ${vm.dpi}dpi • ${vm.refreshRateHz}Hz • ${vm.allocatedCores}c/${vm.allocatedRamMb / 1024}GB")
                    if (vm.bootloaderUnlocked) append(" • Factory Unlocked")
                    if (vm.rootEnabled) append(" • Root")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onLoadAndBoot,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("load_vm_${vm.id}")
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRunning) "Open VM" else "Boot & Load")
                }

                if (isRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("stop_vm_${vm.id}")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                if (canDelete && !isRunning) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("delete_vm_${vm.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete ${vm.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateVmDialog(
    hostCores: Int,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        osVersion: String,
        apiLevel: Int,
        abi: String,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
        refreshRateHz: Int,
        cores: Int,
        ramMb: Int,
        seccompMode: String,
        accentTag: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("Android ROM ${System.currentTimeMillis() % 100}") }
    var selectedOsIdx by remember { mutableIntStateOf(0) }
    var selectedPresetIdx by remember { mutableIntStateOf(1) }
    var coresFloat by remember { mutableFloatStateOf(hostCores.coerceAtLeast(2).toFloat()) }
    var ramGbFloat by remember { mutableFloatStateOf(4f) }

    val osOptions = HypervisorCatalog.osTemplates
    val presets = HypervisorCatalog.displayPresets

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Virtual Android", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("VM Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_vm_name_input")
                )

                Text("Android Version (Factory Unlocked + Root)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    osOptions.take(2).forEachIndexed { idx, (label, _, _) ->
                        FilterChip(
                            selected = selectedOsIdx == idx,
                            onClick = { selectedOsIdx = idx },
                            label = { Text(label.substringBefore(" ("), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    osOptions.drop(2).forEachIndexed { idx, (label, _, _) ->
                        val actualIdx = idx + 2
                        FilterChip(
                            selected = selectedOsIdx == actualIdx,
                            onClick = { selectedOsIdx = actualIdx },
                            label = { Text(label.substringBefore(" ("), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Display Profile", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.take(3).forEachIndexed { idx, preset ->
                        FilterChip(
                            selected = selectedPresetIdx == idx,
                            onClick = { selectedPresetIdx = idx },
                            label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("CPU Passthrough: ${coresFloat.roundToInt()} / $hostCores Cores", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = coresFloat,
                    onValueChange = { coresFloat = it },
                    valueRange = 1f..hostCores.coerceAtLeast(2).toFloat(),
                    steps = (hostCores - 2).coerceAtLeast(0)
                )

                Text("RAM: ${ramGbFloat.roundToInt()} GB", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = ramGbFloat,
                    onValueChange = { ramGbFloat = it },
                    valueRange = 1f..8f,
                    steps = 6
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val (osLabel, api, abi) = osOptions[selectedOsIdx]
                    val preset = presets[selectedPresetIdx]
                    val accent = when (selectedOsIdx % 3) {
                        0 -> "CYAN"
                        1 -> "EMERALD"
                        else -> "AMBER"
                    }
                    onCreate(
                        name, osLabel, api, abi,
                        preset.widthPx, preset.heightPx, preset.dpi, preset.refreshRateHz,
                        coresFloat.roundToInt(), ramGbFloat.roundToInt() * 1024,
                        "SECCOMP_BPF + PTRACE", accent
                    )
                },
                modifier = Modifier.testTag("confirm_create_vm_button")
            ) {
                Text("Boot & Load")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
