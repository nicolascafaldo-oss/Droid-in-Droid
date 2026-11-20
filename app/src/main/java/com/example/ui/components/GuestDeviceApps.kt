package com.example.ui.components

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.local.GuestInstalledAppEntity
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmSandboxFileEntity
import com.example.engine.HypervisorCatalog
import com.example.engine.SyscallLogEntry
import com.example.hardware.HostHardwareSnapshot
import com.example.hardware.HostSensorSample
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun GuestSettingsApp(
    vm: VmInstanceEntity,
    guestApps: List<GuestInstalledAppEntity>,
    vmFiles: List<VmSandboxFileEntity>,
    layoutBounds: Boolean,
    accent: Color,
    onApplyDisplay: (widthPx: Int, heightPx: Int, dpi: Int, hz: Int) -> Unit,
    onToggleLayoutBounds: () -> Unit,
    onUninstallApp: (GuestInstalledAppEntity) -> Unit
) {
    var section by remember { mutableStateOf("MAIN") }
    var dpiFloat by remember(vm.dpi) { mutableFloatStateOf(vm.dpi.toFloat()) }
    var hz by remember(vm.refreshRateHz) { mutableIntStateOf(vm.refreshRateHz) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Text(
                    text = when (section) {
                        "DISPLAY" -> "Settings › Display"
                        "APPS" -> "Settings › Apps (${guestApps.size})"
                        "ABOUT" -> "Settings › About Phone"
                        else -> "Android Settings"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
            }
            if (section != "MAIN") {
                OutlinedButton(
                    onClick = { section = "MAIN" },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("All", style = MaterialTheme.typography.labelSmall) }
            }
        }

        when (section) {
            "DISPLAY" -> {
                Text("Screen Resolution: ${vm.widthPx}×${vm.heightPx}", style = MaterialTheme.typography.labelMedium)
                Text("Density Spoof: ${dpiFloat.roundToInt()} dpi", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = dpiFloat,
                    onValueChange = { dpiFloat = (it / 20f).roundToInt() * 20f },
                    valueRange = 160f..640f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 90, 120, 144).forEach { targetHz ->
                        OutlinedButton(
                            onClick = { hz = targetHz },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (hz == targetHz) "● ${targetHz}Hz" else "${targetHz}Hz",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Button(
                    onClick = { onApplyDisplay(vm.widthPx, vm.heightPx, dpiFloat.roundToInt(), hz) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Apply Virtual Display") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Developer Layout Bounds", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = layoutBounds, onCheckedChange = { onToggleLayoutBounds() })
                }
            }

            "APPS" -> {
                guestApps.forEach { app ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "${app.packageName} • v${app.versionName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!app.isSystemApp) {
                                OutlinedButton(
                                    onClick = { onUninstallApp(app) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Uninstall", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                Text("SYSTEM", style = MaterialTheme.typography.labelSmall, color = accent)
                            }
                        }
                    }
                }
            }

            "ABOUT" -> {
                val totalKb = vmFiles.sumOf { it.sizeBytes } / 1024L
                AboutRow("Device Name", vm.name)
                AboutRow("Android Version", vm.guestOsVersion)
                AboutRow("Bootloader", if (vm.bootloaderUnlocked) "Factory Unlocked" else "Locked")
                AboutRow("Root Access", if (vm.rootEnabled) "Enabled (su / uid=0)" else "Disabled")
                AboutRow("CPU Passthrough", "${vm.allocatedCores} Cores (${vm.abi})")
                AboutRow("GPU Hardware Accel", "GLES20 / EGL Bridge")
                AboutRow("Allocated RAM", "${vm.allocatedRamMb} MB")
                AboutRow("Sandbox Storage", "$totalKb KB used (${vmFiles.size} files)")
                AboutRow("Build Fingerprint", "kestrel/${vm.id}/userdebug:${vm.apiLevel}")
            }

            else -> {
                SettingsMenuCard("Display & DPI Scaling", "${vm.widthPx}×${vm.heightPx} • ${vm.dpi}dpi • ${vm.refreshRateHz}Hz", accent) {
                    section = "DISPLAY"
                }
                SettingsMenuCard("Apps & Installed APKs", "${guestApps.size} packages in /data/app", accent) {
                    section = "APPS"
                }
                SettingsMenuCard("About Virtual Phone", "${vm.guestOsVersion} • ${vm.abi}", accent) {
                    section = "ABOUT"
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuCard(title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun GuestFilesApp(
    vm: VmInstanceEntity,
    vmFiles: List<VmSandboxFileEntity>,
    accent: Color,
    onInstallApk: (VmSandboxFileEntity) -> Unit,
    onOpenVaultImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text("Guest Files (${vm.name})", style = MaterialTheme.typography.titleSmall, color = accent)
            }
            OutlinedButton(
                onClick = onOpenVaultImport,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Import", style = MaterialTheme.typography.labelSmall)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vmFiles, key = { it.id }) { file ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.fileName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${file.virtualPath} • ${file.sizeBytes / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (file.category == "APK") {
                            Button(
                                onClick = { onInstallApk(file) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("guest_install_apk_${file.id}")
                            ) {
                                Text("Install", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuestPackageInstallerScreen(
    vm: VmInstanceEntity,
    apkFile: VmSandboxFileEntity,
    accent: Color,
    onInstallConfirmed: (VmSandboxFileEntity, (GuestInstalledAppEntity) -> Unit) -> Unit,
    onLaunchInstalled: (String) -> Unit,
    onDone: () -> Unit
) {
    var phase by remember(apkFile.id) { mutableStateOf("CONFIRM") }
    var progress by remember(apkFile.id) { mutableFloatStateOf(0f) }
    var installedApp by remember(apkFile.id) { mutableStateOf<GuestInstalledAppEntity?>(null) }

    LaunchedEffect(phase) {
        if (phase == "INSTALLING") {
            progress = 0.25f
            delay(350L)
            progress = 0.65f
            delay(350L)
            onInstallConfirmed(apkFile) { app ->
                installedApp = app
                progress = 1f
                phase = "DONE"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Android, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
                Column {
                    Text(
                        text = apkFile.apkPackageName ?: apkFile.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Target VM: ${vm.name} (${vm.guestOsVersion})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Source: ${apkFile.virtualPath}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "Version: ${apkFile.apkVersionName ?: "1.0"} • Target SDK: ${apkFile.apkTargetSdk ?: vm.apiLevel}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "Isolation: /data/app/<pkg>/base.apk under ${vm.seccompMode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
            }

            if (phase == "INSTALLING") {
                Text("Staging APK into guest /data/app…", style = MaterialTheme.typography.labelMedium, color = accent)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else if (phase == "DONE" && installedApp != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text("App installed onto ${vm.name} Home Screen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (phase) {
                "CONFIRM" -> {
                    OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { phase = "INSTALLING" },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("confirm_guest_apk_install")
                    ) { Text("Install") }
                }
                "INSTALLING" -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Installing…") }
                }
                else -> {
                    OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
                    Button(
                        onClick = { installedApp?.let { onLaunchInstalled(it.packageName) } ?: onDone() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Open") }
                }
            }
        }
    }
}

@Composable
fun GuestTerminalApp(
    vm: VmInstanceEntity,
    guestApps: List<GuestInstalledAppEntity>,
    vmFiles: List<VmSandboxFileEntity>,
    logs: List<SyscallLogEntry>,
    hostHardware: HostHardwareSnapshot
) {
    var commandInput by remember { mutableStateOf("") }
    val promptPrefix = if (vm.rootEnabled) "root@${vm.id}:~#" else "shell@${vm.id}:~$"
    val history = remember(vm.id, vm.rootEnabled) {
        mutableStateListOf(
            "$promptPrefix uname -a",
            "Linux localhost 6.1.0-kestrel-${vm.abi} #1 SMP PREEMPT (${vm.seccompMode})",
            "Type 'help' for commands (id, su, getprop, wm size, pm list packages, ls, ps, free)"
        )
    }

    fun execute(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        history.add("$promptPrefix $trimmed")
        when {
            trimmed == "clear" -> history.clear()
            trimmed == "help" -> history.add("Commands: id | su | uname -a | getprop | wm size | wm density | pm list packages | ls | free | ps | trace | clear")
            trimmed == "id" -> {
                if (vm.rootEnabled) {
                    history.add("uid=0(root) gid=0(root) groups=0(root) context=u:r:kestrel_su:s0")
                } else {
                    history.add("uid=2000(shell) gid=2000(shell)")
                }
            }
            trimmed == "su" || trimmed.startsWith("su ") -> {
                if (vm.rootEnabled) {
                    history.add("kestrel_su: uid=0(root) shell granted (/system/xbin/su)")
                } else {
                    history.add("su: Permission denied (enable Root in Hardware tab)")
                }
            }
            trimmed.startsWith("uname") -> history.add("Linux kestrel-guest 6.1.0 ${vm.abi} ${vm.guestOsVersion}")
            trimmed == "wm size" -> history.add("Physical size: ${vm.widthPx}x${vm.heightPx} @ ${vm.refreshRateHz}Hz")
            trimmed == "wm density" -> history.add("Physical density: ${vm.dpi}")
            trimmed == "getprop" -> {
                history.add("[ro.build.version.release]: [${vm.guestOsVersion}]")
                history.add("[ro.product.cpu.abi]: [${vm.abi}]")
                history.add("[ro.sf.lcd_density]: [${vm.dpi}]")
                history.add("[ro.boot.flash.locked]: [${if (vm.bootloaderUnlocked) "0" else "1"}]")
                history.add("[ro.boot.verifiedbootstate]: [${if (vm.bootloaderUnlocked) "orange" else "green"}]")
                history.add("[persist.kestrel.root]: [${if (vm.rootEnabled) "1" else "0"}]")
                history.add("[ro.hardware.egl]: [kestrel_egl_bridge]")
            }
            trimmed == "pm list packages" -> {
                guestApps.forEach { history.add("package:${it.packageName} (v${it.versionName})") }
            }
            trimmed.startsWith("ls") -> {
                vmFiles.forEach { history.add("${it.sizeBytes}B  ${it.virtualPath}") }
            }
            trimmed.startsWith("free") -> {
                history.add("Mem: ${vm.allocatedRamMb}M total, ${hostHardware.availableRamMb}M host_avail")
            }
            trimmed == "ps" -> {
                history.add("PID 1    /init (seccomp-bpf)")
                history.add("PID 42   /system/bin/servicemanager (/dev/binder_proxy)")
                history.add("PID 88   /system/bin/surfaceflinger (hwcomposer.kestrel)")
                history.add("PID 124  system_server (${vm.allocatedCores} vCPUs)")
            }
            trimmed == "trace" -> {
                logs.filter { it.vmId == vm.id }.take(4).forEach {
                    history.add("[${it.layer}] ${it.syscallOrIpc} (${it.latencyUs}us)")
                }
            }
            else -> history.add("sh: $trimmed: executed in sandbox (${vm.seccompMode})")
        }
        commandInput = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(history) { line ->
                Text(
                    text = line,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = if (line.startsWith("kestrel_sh@")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("pm list packages", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { execute(commandInput) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) { Text("Run", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
fun GuestCalculatorApp(accent: Color) {
    var display by remember { mutableStateOf("0") }
    var operand by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var resetOnNext by remember { mutableStateOf(false) }

    fun pressDigit(d: String) {
        if (resetOnNext || display == "0") {
            display = d
            resetOnNext = false
        } else {
            display += d
        }
    }

    fun applyOp(op: String) {
        val current = display.toDoubleOrNull() ?: 0.0
        val prev = operand
        if (prev != null && pendingOp != null && !resetOnNext) {
            val res = when (pendingOp) {
                "+" -> prev + current
                "-" -> prev - current
                "×" -> prev * current
                "÷" -> if (current == 0.0) Double.NaN else prev / current
                else -> current
            }
            display = if (res.isNaN()) "Error" else if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res).trimEnd('0').trimEnd('.')
            operand = if (res.isNaN()) null else res
        } else {
            operand = current
        }
        pendingOp = if (op == "=") null else op
        resetOnNext = true
    }

    val rows = listOf(
        listOf("7", "8", "9", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("1", "2", "3", "-"),
        listOf("C", "0", "=", "+")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = display,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 26.sp,
                color = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                maxLines = 1
            )
        }
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { key ->
                    val isOp = key in listOf("÷", "×", "-", "+", "=", "C")
                    Button(
                        onClick = {
                            when (key) {
                                "C" -> {
                                    display = "0"; operand = null; pendingOp = null; resetOnNext = false
                                }
                                "÷", "×", "-", "+", "=" -> applyOp(key)
                                else -> pressDigit(key)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isOp) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isOp) accent else MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    ) {
                        Text(key, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun GuestNotesApp(
    vm: VmInstanceEntity,
    vmFiles: List<VmSandboxFileEntity>,
    accent: Color,
    onSaveNote: (title: String, body: String) -> Unit
) {
    var title by remember { mutableStateOf("vm_note") }
    var body by remember { mutableStateOf("") }
    val docs = vmFiles.filter { it.category == "DOCUMENT" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text("Sandbox Notes (/sdcard/Documents)", style = MaterialTheme.typography.titleSmall, color = accent)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("File name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Note content (exportable via File Vault)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (body.isNotBlank()) {
                    onSaveNote(title, body)
                    body = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save to ${vm.name}") }

        docs.forEach { doc ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${doc.virtualPath} (${doc.sizeBytes} B)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun GuestEglSurfaceApp(
    vm: VmInstanceEntity,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "EGL / GLES20 Surface Bridge",
            style = MaterialTheme.typography.titleSmall,
            color = accent
        )
        Text(
            text = if (vm.eglBridgeEnabled) {
                "Hardware OpenGL ES 2.0 Surface active (${vm.widthPx}×${vm.heightPx} @ ${vm.refreshRateHz}Hz)"
            } else {
                "EGL Hardware Bridge disabled in Engine tab — software fallback"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(KestrelPrismGlRenderer())
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private class KestrelPrismGlRenderer : GLSurfaceView.Renderer {
    private val vertices = floatArrayOf(
        0.0f, 0.62f, 0.0f,
        -0.55f, -0.45f, 0.0f,
        0.55f, -0.45f, 0.0f
    )
    private lateinit var vertexBuffer: FloatBuffer
    private var program = 0
    private var angle = 0f
    private val mvpMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.043f, 0.059f, 0.078f, 1.0f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val vShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "uniform mat4 uMVPMatrix; attribute vec4 vPosition; void main() { gl_Position = uMVPMatrix * vPosition; }"
        )
        val fShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; void main() { gl_FragColor = vec4(0.133, 0.827, 0.933, 1.0); }"
        )
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        angle = (angle + 1.8f) % 360f
        Matrix.setRotateM(mvpMatrix, 0, angle, 0f, 1f, 0.25f)

        val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun compileShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
        }
    }
}

@Composable
fun GuestInstalledApkSandboxView(
    vm: VmInstanceEntity,
    app: GuestInstalledAppEntity,
    iconFile: File?,
    accent: Color,
    onUninstall: () -> Unit
) {
    val swDp = (minOf(vm.widthPx, vm.heightPx) / (vm.dpi / 160f)).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (iconFile != null && iconFile.exists()) {
                AsyncImage(
                    model = iconFile,
                    contentDescription = app.appName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Surface(
                    color = accent.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Android, contentDescription = null, tint = accent)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${app.packageName} • v${app.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sandboxed App Container (${vm.name})", style = MaterialTheme.typography.labelLarge, color = accent)
                Text("APK Path: ${app.apkVirtualPath}", style = MaterialTheme.typography.labelSmall)
                Text("Target SDK: ${app.targetSdk} • Activities: ${app.activitiesCount}", style = MaterialTheme.typography.labelSmall)
                Text("Viewport Scaling: ${vm.widthPx}×${vm.heightPx} • sw${swDp}dp @ ${vm.dpi}dpi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                if (app.permissionsSummary.isNotBlank()) {
                    Text("Manifest Permissions: ${app.permissionsSummary}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedButton(
            onClick = onUninstall,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Uninstall from ${vm.name}", color = MaterialTheme.colorScheme.error)
        }
    }
}
