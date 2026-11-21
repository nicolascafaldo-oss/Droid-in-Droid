package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.GuestInstalledAppEntity
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmRunState
import com.example.data.local.VmSandboxFileEntity
import com.example.engine.SyscallLogEntry
import com.example.hardware.HostHardwareSnapshot
import com.example.hardware.HostSensorSample
import com.example.ui.GuestDeviceScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun VirtualGuestDisplay(
    vm: VmInstanceEntity,
    guestScreen: GuestDeviceScreen,
    guestApps: List<GuestInstalledAppEntity>,
    recentPackages: List<String>,
    isShadeOpen: Boolean,
    guestBrightness: Float,
    guestWifiEnabled: Boolean,
    guestLayoutBounds: Boolean,
    hostHardware: HostHardwareSnapshot,
    hostSensors: HostSensorSample,
    vmLogs: List<SyscallLogEntry>,
    vmFiles: List<VmSandboxFileEntity>,
    compactPipMode: Boolean,
    resolveHostFile: (String) -> File,
    onBootVm: () -> Unit,
    onTogglePauseVm: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenRecents: () -> Unit,
    onGoHome: () -> Unit,
    onGoBack: () -> Unit,
    onCloseRecentTask: (String) -> Unit,
    onToggleShade: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onToggleWifi: () -> Unit,
    onToggleLayoutBounds: () -> Unit,
    onApplyDisplay: (Int, Int, Int, Int) -> Unit,
    onInstallApkRequest: (VmSandboxFileEntity) -> Unit,
    onInstallApkConfirm: (VmSandboxFileEntity, (GuestInstalledAppEntity) -> Unit) -> Unit,
    onUninstallApp: (GuestInstalledAppEntity) -> Unit,
    onSaveNote: (String, String) -> Unit,
    onOpenHostVault: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreenMode: Boolean = false
) {
    val rawRatio = (vm.widthPx.toFloat() / vm.heightPx.toFloat()).coerceIn(0.42f, 2.38f)
    val accent = when (vm.accentTag) {
        "EMERALD" -> MaterialTheme.colorScheme.secondary
        "AMBER" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val densityScale = vm.dpi / 160f
    val swDp = (minOf(vm.widthPx, vm.heightPx) / densityScale).roundToInt()
    val clockTime = remember { SimpleDateFormat("HH:mm", Locale.US).format(Date()) }

    Surface(
        modifier = modifier
            .then(if (fullscreenMode) Modifier.fillMaxSize() else Modifier.aspectRatio(rawRatio))
            .testTag("virtual_guest_display"),
        shape = when {
            fullscreenMode -> RoundedCornerShape(0.dp)
            compactPipMode -> RoundedCornerShape(14.dp)
            else -> RoundedCornerShape(18.dp)
        },
        color = MaterialTheme.colorScheme.background,
        border = if (fullscreenMode) null else BorderStroke(
            width = if (compactPipMode) 1.5.dp else 1.5.dp,
            color = if (vm.runState == VmRunState.RUNNING.name) accent else MaterialTheme.colorScheme.outline
        ),
        tonalElevation = if (fullscreenMode) 0.dp else 4.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(guestBrightness.coerceIn(0.4f, 1f))
            ) {
                // OEM Android Status Bar (tap to pull down Quick Settings Shade)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(enabled = !compactPipMode && vm.runState == VmRunState.RUNNING.name) {
                            onToggleShade()
                        }
                        .padding(
                            horizontal = if (compactPipMode) 8.dp else 14.dp,
                            vertical = if (compactPipMode) 4.dp else 6.dp
                        )
                        .testTag("guest_status_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (compactPipMode) vm.name else clockTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (!compactPipMode && guestWifiEnabled) {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "${hostHardware.batteryLevelPct}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Virtual Device Screen Body
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (guestLayoutBounds && !compactPipMode) {
                                Modifier.border(1.dp, accent.copy(alpha = 0.65f))
                            } else Modifier
                        )
                ) {
                    when (vm.runState) {
                        VmRunState.STOPPED.name -> GuestOfflineOverlay(
                            title = "${vm.name} Powered Off",
                            subtitle = "${vm.guestOsVersion} • ${vm.abi}",
                            actionLabel = if (compactPipMode) "Boot" else "Boot Android VM",
                            onAction = onBootVm,
                            compact = compactPipMode
                        )

                        VmRunState.BOOTING.name -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = accent,
                                modifier = Modifier.size(if (compactPipMode) 24.dp else 40.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Booting ${vm.guestOsVersion}…",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                textAlign = TextAlign.Center
                            )
                        }

                        VmRunState.PAUSED.name -> GuestOfflineOverlay(
                            title = "SIGSTOP Paused",
                            subtitle = "State preserved (${vm.allocatedRamMb} MB)",
                            actionLabel = "Resume",
                            onAction = onTogglePauseVm,
                            compact = compactPipMode
                        )

                        else -> {
                            if (compactPipMode) {
                                CompactPipGuestView(vm = vm, swDp = swDp, sensors = hostSensors, accent = accent)
                            } else {
                                AnimatedContent(
                                    targetState = guestScreen,
                                    label = "guest_device_screen"
                                ) { screen ->
                                    when (screen) {
                                        GuestDeviceScreen.Home -> GuestAndroidHomeScreen(
                                            vm = vm,
                                            swDp = swDp,
                                            clockTime = clockTime,
                                            guestApps = guestApps,
                                            accent = accent,
                                            resolveHostFile = resolveHostFile,
                                            onLaunchApp = onLaunchApp,
                                            onOpenDrawer = onOpenDrawer
                                        )

                                        GuestDeviceScreen.AppDrawer -> GuestAppDrawerScreen(
                                            vm = vm,
                                            guestApps = guestApps,
                                            accent = accent,
                                            resolveHostFile = resolveHostFile,
                                            onLaunchApp = onLaunchApp
                                        )

                                        GuestDeviceScreen.Recents -> GuestRecentsOverviewScreen(
                                            recentPackages = recentPackages,
                                            guestApps = guestApps,
                                            accent = accent,
                                            resolveHostFile = resolveHostFile,
                                            onLaunchApp = onLaunchApp,
                                            onCloseTask = onCloseRecentTask,
                                            onGoHome = onGoHome
                                        )

                                        is GuestDeviceScreen.PackageInstaller -> GuestPackageInstallerScreen(
                                            vm = vm,
                                            apkFile = screen.apkFile,
                                            accent = accent,
                                            onInstallConfirmed = onInstallApkConfirm,
                                            onLaunchInstalled = onLaunchApp,
                                            onDone = onGoHome
                                        )

                                        is GuestDeviceScreen.RunningApp -> {
                                            when (screen.packageName) {
                                                "com.android.settings" -> GuestSettingsApp(
                                                    vm = vm,
                                                    guestApps = guestApps,
                                                    vmFiles = vmFiles,
                                                    layoutBounds = guestLayoutBounds,
                                                    accent = accent,
                                                    onApplyDisplay = onApplyDisplay,
                                                    onToggleLayoutBounds = onToggleLayoutBounds,
                                                    onUninstallApp = onUninstallApp
                                                )

                                                "com.android.documentsui" -> GuestFilesApp(
                                                    vm = vm,
                                                    vmFiles = vmFiles,
                                                    accent = accent,
                                                    onInstallApk = onInstallApkRequest,
                                                    onOpenVaultImport = onOpenHostVault
                                                )

                                                "com.kestrel.term" -> GuestTerminalApp(
                                                    vm = vm,
                                                    guestApps = guestApps,
                                                    vmFiles = vmFiles,
                                                    logs = vmLogs,
                                                    hostHardware = hostHardware
                                                )

                                                "com.android.calculator2" -> GuestCalculatorApp(accent = accent)

                                                "com.kestrel.notes" -> GuestNotesApp(
                                                    vm = vm,
                                                    vmFiles = vmFiles,
                                                    accent = accent,
                                                    onSaveNote = onSaveNote
                                                )

                                                "com.kestrel.egl" -> GuestEglSurfaceApp(vm = vm, accent = accent)

                                                "com.kestrel.dpilab" -> GuestDpiLabApp(vm = vm, swDp = swDp, accent = accent)

                                                "com.kestrel.imu" -> GuestSensorProbeApp(vm = vm, sensors = hostSensors, accent = accent)

                                                else -> {
                                                    val installed = guestApps.find { it.packageName == screen.packageName }
                                                    if (installed != null) {
                                                        GuestInstalledApkSandboxView(
                                                            vm = vm,
                                                            app = installed,
                                                            iconFile = installed.iconHostPath?.let(resolveHostFile),
                                                            accent = accent,
                                                            onUninstall = { onUninstallApp(installed) }
                                                        )
                                                    } else {
                                                        GuestOfflineOverlay(
                                                            title = "App Not Found",
                                                            subtitle = screen.packageName,
                                                            actionLabel = "Home",
                                                            onAction = onGoHome,
                                                            compact = false
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pull-down Quick Settings & Notification Shade
                    if (!compactPipMode && vm.runState == VmRunState.RUNNING.name) {
                        androidx.compose.animation.AnimatedVisibility(visible = isShadeOpen) {
                            GuestQuickSettingsShade(
                                vm = vm,
                                swDp = swDp,
                                wifiEnabled = guestWifiEnabled,
                                brightness = guestBrightness,
                                accent = accent,
                                onToggleWifi = onToggleWifi,
                                onBrightnessChange = onBrightnessChange,
                                onOpenSettings = { onLaunchApp("com.android.settings") },
                                onDismiss = onToggleShade
                            )
                        }
                    }
                }

                // Virtual Android 3-Button Navigation Bar
                if (!compactPipMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onGoBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("guest_nav_back")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_guest_back),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onGoHome,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("guest_nav_home")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Home,
                                contentDescription = stringResource(R.string.cd_guest_home),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(
                            onClick = onOpenRecents,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("guest_nav_recents")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GridView,
                                contentDescription = stringResource(R.string.cd_guest_recents),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestAndroidHomeScreen(
    vm: VmInstanceEntity,
    swDp: Int,
    clockTime: String,
    guestApps: List<GuestInstalledAppEntity>,
    accent: Color,
    resolveHostFile: (String) -> File,
    onLaunchApp: (String) -> Unit,
    onOpenDrawer: () -> Unit
) {
    val dateLabel = remember { SimpleDateFormat("EEE, MMM d", Locale.US).format(Date()) }
    val homeApps = remember(guestApps) {
        val userApps = guestApps.filter { !it.isSystemApp }
        val oemOrder = listOf(
            "com.android.calculator2",
            "com.kestrel.notes",
            "com.kestrel.term",
            "com.android.documentsui",
            "com.android.settings"
        )
        val orderedOem = oemOrder.mapNotNull { pkg -> guestApps.find { it.packageName == pkg } }
        (userApps + orderedOem).distinctBy { it.packageName }.take(8)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Stock AOSP At-A-Glance Date & Time Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = clockTime,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // OEM Launcher App Grid
            val columns = if (swDp >= 600) 5 else 4
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
            ) {
                items(homeApps, key = { it.packageName }) { app ->
                    GuestAppTile(
                        app = app,
                        accent = accent,
                        resolveHostFile = resolveHostFile,
                        onClick = { onLaunchApp(app.packageName) }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Stock AOSP Search Pill (opens App Drawer)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDrawer)
                        .testTag("guest_search_pill")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search apps",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Search apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Stock AOSP Bottom Dock
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockShortcut("Files", Icons.Rounded.Folder, MaterialTheme.colorScheme.primary) {
                        onLaunchApp("com.android.documentsui")
                    }
                    DockShortcut("Terminal", Icons.Rounded.Terminal, MaterialTheme.colorScheme.secondary) {
                        onLaunchApp("com.kestrel.term")
                    }
                    DockShortcut("Apps", Icons.Rounded.Apps, MaterialTheme.colorScheme.tertiary, onOpenDrawer)
                    DockShortcut("Settings", Icons.Rounded.Settings, MaterialTheme.colorScheme.onSurfaceVariant) {
                        onLaunchApp("com.android.settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun DockShortcut(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("guest_dock_${label.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun GuestAppTile(
    app: GuestInstalledAppEntity,
    accent: Color,
    resolveHostFile: (String) -> File,
    onClick: () -> Unit
) {
    val iconFile = app.iconHostPath?.let(resolveHostFile)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            .testTag("guest_icon_${app.packageName}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (iconFile != null && iconFile.exists()) {
            AsyncImage(
                model = iconFile,
                contentDescription = app.appName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            )
        } else {
            val icon = when (app.packageName) {
                "com.android.settings" -> Icons.Rounded.Settings
                "com.android.documentsui" -> Icons.Rounded.Folder
                "com.kestrel.term" -> Icons.Rounded.Terminal
                "com.android.calculator2" -> Icons.Rounded.Calculate
                "com.kestrel.notes" -> Icons.Rounded.EditNote
                "com.kestrel.egl" -> Icons.Rounded.ViewInAr
                "com.kestrel.dpilab" -> Icons.Rounded.AspectRatio
                "com.kestrel.imu" -> Icons.Rounded.Sensors
                else -> Icons.Rounded.Android
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = app.appName, tint = accent, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GuestAppDrawerScreen(
    vm: VmInstanceEntity,
    guestApps: List<GuestInstalledAppEntity>,
    accent: Color,
    resolveHostFile: (String) -> File,
    onLaunchApp: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(guestApps, query) {
        if (query.isBlank()) guestApps
        else guestApps.filter {
            it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search ${guestApps.size} apps in ${vm.name}", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filtered, key = { it.packageName }) { app ->
                GuestAppTile(
                    app = app,
                    accent = accent,
                    resolveHostFile = resolveHostFile,
                    onClick = { onLaunchApp(app.packageName) }
                )
            }
        }
    }
}

@Composable
private fun GuestRecentsOverviewScreen(
    recentPackages: List<String>,
    guestApps: List<GuestInstalledAppEntity>,
    accent: Color,
    resolveHostFile: (String) -> File,
    onLaunchApp: (String) -> Unit,
    onCloseTask: (String) -> Unit,
    onGoHome: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Guest Tasks (${recentPackages.size})", style = MaterialTheme.typography.titleSmall, color = accent)
            Button(onClick = onGoHome, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text("Home", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (recentPackages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recent apps open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recentPackages) { pkg ->
                    val app = guestApps.find { it.packageName == pkg }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLaunchApp(pkg) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app?.appName ?: pkg, style = MaterialTheme.typography.titleSmall)
                                Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onCloseTask(pkg) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close task")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestQuickSettingsShade(
    vm: VmInstanceEntity,
    swDp: Int,
    wifiEnabled: Boolean,
    brightness: Float,
    accent: Color,
    onToggleWifi: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("guest_quick_settings_shade")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${vm.name} Quick Settings", style = MaterialTheme.typography.titleSmall, color = accent)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close shade", modifier = Modifier.size(16.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = wifiEnabled,
                    onClick = onToggleWifi,
                    label = { Text(if (wifiEnabled) "Virtual Wi-Fi" else "Offline", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = true,
                    onClick = onOpenSettings,
                    label = { Text("${vm.dpi}dpi / sw${swDp}dp", style = MaterialTheme.typography.labelSmall) }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Brightness6, contentDescription = null, modifier = Modifier.size(16.dp))
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.4f..1f,
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Kestrel Sandbox Active • ${vm.seccompMode} • Binder Proxy ${if (vm.binderProxyEnabled) "ON" else "OFF"}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun GuestDpiLabApp(vm: VmInstanceEntity, swDp: Int, accent: Color) {
    val densityScale = vm.dpi / 160f
    val heightDp = (vm.heightPx / densityScale).roundToInt()
    val widthDp = (vm.widthPx / densityScale).roundToInt()
    val bucket = densityBucketName(vm.dpi)
    val layoutMode = if (swDp >= 600) "Expanded Tablet (sw600dp+)" else "Compact Phone (<600dp)"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Display & DPI Scaling Lab", style = MaterialTheme.typography.titleMedium, color = accent)
            Text(
                "Logical Viewport: ${widthDp}dp × ${heightDp}dp • $bucket (${densityScale}x)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Qualifiers Active: $layoutMode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text("Physical: ${vm.widthPx}×${vm.heightPx}px @ ${vm.refreshRateHz}Hz", style = MaterialTheme.typography.labelSmall)
                    val sampleScaleFactor = (420f / vm.dpi.toFloat()).coerceIn(0.65f, 1.45f)
                    Text("Sample 16sp Body Copy at ${vm.dpi}dpi", fontSize = (15f * sampleScaleFactor).sp)
                }
            }
        }
    }
}

@Composable
private fun GuestSensorProbeApp(vm: VmInstanceEntity, sensors: HostSensorSample, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Virtual Sensor HAL (sensors.kestrel.so)", style = MaterialTheme.typography.titleSmall, color = accent)
        if (!vm.sensorPassthroughEnabled) {
            Text(
                "IMU passthrough is disabled for this VM. Enable it in the Engine tab to stream live host SensorManager data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        } else {
            val secondary = MaterialTheme.colorScheme.secondary
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val midY = size.height / 2f
                val pathX = Path()
                val pathY = Path()
                val steps = 40
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val px = t * size.width
                    val waveA = midY + sin(t * 8f + sensors.accelX) * (size.height * 0.28f)
                    val waveB = midY + sin(t * 6f + sensors.accelY) * (size.height * 0.22f)
                    if (i == 0) {
                        pathX.moveTo(px, waveA)
                        pathY.moveTo(px, waveB)
                    } else {
                        pathX.lineTo(px, waveA)
                        pathY.lineTo(px, waveB)
                    }
                }
                drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, midY), Offset(size.width, midY), 1f)
                drawPath(pathX, color = accent, style = Stroke(width = 3f, cap = StrokeCap.Round))
                drawPath(pathY, color = secondary, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            }
            Text("ACCEL X:${"%.2f".format(sensors.accelX)} Y:${"%.2f".format(sensors.accelY)} Z:${"%.2f".format(sensors.accelZ)} m/s²", style = MaterialTheme.typography.labelSmall)
            Text("GYRO  X:${"%.2f".format(sensors.gyroX)} Y:${"%.2f".format(sensors.gyroY)} Z:${"%.2f".format(sensors.gyroZ)} rad/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun StatusPulseDot(isRunning: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(
                if (isRunning) MaterialTheme.colorScheme.secondary
                else accent.copy(alpha = 0.45f)
            )
    )
}

@Composable
private fun GuestOfflineOverlay(title: String, subtitle: String, actionLabel: String, onAction: () -> Unit, compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 8.dp else 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (compact) 22.dp else 44.dp))
        Spacer(modifier = Modifier.height(if (compact) 4.dp else 10.dp))
        Text(title, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!compact) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(modifier = Modifier.height(if (compact) 6.dp else 14.dp))
        Button(onClick = onAction, contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 18.dp, vertical = 6.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CompactPipGuestView(vm: VmInstanceEntity, swDp: Int, sensors: HostSensorSample, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(vm.guestOsVersion, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${vm.widthPx}×${vm.heightPx} • sw${swDp}dp • ${vm.refreshRateHz}Hz", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CPU ${vm.allocatedCores}c", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(
                if (vm.sensorPassthroughEnabled) "IMU ${"%.1f".format(sensors.accelX)}/${"%.1f".format(sensors.accelY)}" else "IMU OFF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DraggableMiniFloatingVm(
    vm: VmInstanceEntity,
    hostHardware: HostHardwareSnapshot,
    hostSensors: HostSensorSample,
    resolveHostFile: (String) -> File,
    onBootVm: () -> Unit,
    onTogglePause: () -> Unit,
    onExpandToViewport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(160f) }
    var expandedSize by remember { mutableStateOf(false) }
    val widthDp = if (expandedSize) 220.dp else 156.dp

    Card(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(widthDp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x).coerceAtLeast(0f)
                    offsetY = (offsetY + dragAmount.y).coerceAtLeast(0f)
                }
            }
            .testTag("mini_floating_vm_window"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("FLOAT • ${vm.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row {
                    IconButton(onClick = { expandedSize = !expandedSize }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.ZoomOutMap, contentDescription = "Resize floating window", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = onExpandToViewport, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = "Open full viewport", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close floating window", modifier = Modifier.size(15.dp))
                    }
                }
            }
            VirtualGuestDisplay(
                vm = vm,
                guestScreen = GuestDeviceScreen.Home,
                guestApps = emptyList(),
                recentPackages = emptyList(),
                isShadeOpen = false,
                guestBrightness = 1f,
                guestWifiEnabled = true,
                guestLayoutBounds = false,
                hostHardware = hostHardware,
                hostSensors = hostSensors,
                vmLogs = emptyList(),
                vmFiles = emptyList(),
                compactPipMode = true,
                resolveHostFile = resolveHostFile,
                onBootVm = onBootVm,
                onTogglePauseVm = onTogglePause,
                onLaunchApp = {},
                onOpenDrawer = {},
                onOpenRecents = {},
                onGoHome = {},
                onGoBack = {},
                onCloseRecentTask = {},
                onToggleShade = {},
                onBrightnessChange = {},
                onToggleWifi = {},
                onToggleLayoutBounds = {},
                onApplyDisplay = { _, _, _, _ -> },
                onInstallApkRequest = {},
                onInstallApkConfirm = { _, _ -> },
                onUninstallApp = {},
                onSaveNote = { _, _ -> },
                onOpenHostVault = {},
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(
                    onClick = onTogglePause,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                ) {
                    Icon(if (vm.runState == VmRunState.PAUSED.name) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (vm.runState == VmRunState.PAUSED.name) "Resume" else "Pause", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun densityBucketName(dpi: Int): String = when {
    dpi <= 180 -> "mdpi"
    dpi <= 260 -> "hdpi"
    dpi <= 360 -> "xhdpi"
    dpi <= 500 -> "xxhdpi"
    else -> "xxxhdpi"
}
