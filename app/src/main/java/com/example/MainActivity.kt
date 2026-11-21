package com.example

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.VmInstanceEntity
import com.example.data.local.VmRunState
import com.example.ui.GuestDeviceScreen
import com.example.ui.HypervisorUiState
import com.example.ui.HypervisorViewModel
import com.example.ui.VmWorkspaceTab
import com.example.ui.components.DraggableMiniFloatingVm
import com.example.ui.components.VirtualGuestDisplay
import com.example.ui.screens.EngineScreen
import com.example.ui.screens.FileVaultScreen
import com.example.ui.screens.InstancesScreen
import com.example.ui.screens.ViewportScreen
import com.example.ui.theme.KestrelVMTheme

private const val ACTION_PIP_CONTROL = "com.example.kestrelvm.ACTION_PIP_CONTROL"
private const val EXTRA_PIP_COMMAND = "pip_command"
private const val CMD_TOGGLE_PAUSE = 1
private const val CMD_CYCLE_VM = 2

private val EXIT_FULLSCREEN_KEY_COMBO = setOf(
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_POWER
)

private data class WorkspaceTabItem(
    val tab: VmWorkspaceTab,
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    internal val viewModel: HypervisorViewModel by viewModels()
    private val pressedComboKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && handleFullscreenComboKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            pressedComboKeys.remove(event.keyCode)
        }
        return super.onKeyUp(keyCode, event)
    }

    internal fun handleFullscreenComboKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP &&
            code != KeyEvent.KEYCODE_VOLUME_DOWN &&
            code != KeyEvent.KEYCODE_POWER
        ) {
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressedComboKeys.add(code)
                if (viewModel.isFullscreenActiveNow() &&
                    pressedComboKeys.containsAll(EXIT_FULLSCREEN_KEY_COMBO)
                ) {
                    pressedComboKeys.clear()
                    viewModel.exitVmFullscreen(viaKeyCombo = true)
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                pressedComboKeys.remove(code)
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            DisposableEffect(this) {
                val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                    viewModel.setPipMode(info.isInPictureInPictureMode)
                }
                addOnPictureInPictureModeChangedListener(listener)
                onDispose {
                    removeOnPictureInPictureModeChangedListener(listener)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PipRemoteActionReceiver(
                    onTogglePause = {
                        state.activeVm?.let { viewModel.togglePauseVm(it.id) }
                    },
                    onCycleVm = { viewModel.cycleRunningVm() }
                )
            }

            LaunchedEffect(state.activeVm?.id, state.activeVm?.widthPx, state.activeVm?.heightPx, state.activeVm?.runState) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPictureInPictureParams(buildPipParams(state.activeVm))
                }
            }

            val isFullscreen = state.activeVm != null && state.isVmFullscreen && !state.isInPipMode
            LaunchedEffect(isFullscreen) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            KestrelVMTheme(darkTheme = state.darkTheme) {
                when {
                    state.isInPipMode && state.activeVm != null -> {
                        val vm = state.activeVm!!
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            VirtualGuestDisplay(
                                vm = vm,
                                guestScreen = state.guestScreen,
                                guestApps = state.activeVmApps,
                                recentPackages = state.guestRecentPackages,
                                isShadeOpen = false,
                                guestBrightness = state.guestBrightness,
                                guestWifiEnabled = state.guestWifiEnabled,
                                guestLayoutBounds = false,
                                hostHardware = state.hostHardware,
                                hostSensors = state.hostSensors,
                                vmLogs = state.syscallLogs,
                                vmFiles = state.activeVmFiles,
                                compactPipMode = true,
                                resolveHostFile = { viewModel.resolveHostFile(it) },
                                onBootVm = { viewModel.bootVm(vm.id) },
                                onTogglePauseVm = { viewModel.togglePauseVm(vm.id) },
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
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    isFullscreen && state.activeVm != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onPreviewKeyEvent { keyEvent ->
                                    handleFullscreenComboKeyEvent(keyEvent.nativeKeyEvent)
                                }
                        ) {
                            FullscreenGuestVmContainer(
                                vm = state.activeVm!!,
                                state = state,
                                viewModel = viewModel
                            )
                        }
                    }

                    else -> {
                        KestrelHypervisorShell(
                            state = state,
                            viewModel = viewModel,
                            onEnterSystemPip = { enterSystemPip(state.activeVm) }
                        )
                    }
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPipMode(isInPictureInPictureMode)
    }

    private fun enterSystemPip(activeVm: VmInstanceEntity?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(buildPipParams(activeVm))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(vm: VmInstanceEntity?): PictureInPictureParams {
        val rawW = vm?.widthPx ?: 1080
        val rawH = vm?.heightPx ?: 1920
        val ratio = (rawW.toFloat() / rawH.toFloat()).coerceIn(0.45f, 2.2f)
        val scaledW = (ratio * 1000).toInt().coerceIn(450, 2200)
        val scaledH = 1000

        val dm = resources.displayMetrics
        val sourceHint = android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)

        val actions = buildPipActions(vm)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(scaledW, scaledH))
            .setSourceRectHint(sourceHint)
            .setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(vm?.runState == VmRunState.RUNNING.name)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipActions(vm: VmInstanceEntity?): List<RemoteAction> {
        val isPaused = vm?.runState == VmRunState.PAUSED.name
        val pauseIntent = PendingIntent.getBroadcast(
            this,
            CMD_TOGGLE_PAUSE,
            Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_COMMAND, CMD_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cycleIntent = PendingIntent.getBroadcast(
            this,
            CMD_CYCLE_VM,
            Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_COMMAND, CMD_CYCLE_VM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseAction = RemoteAction(
            Icon.createWithResource(
                this,
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            ),
            getString(R.string.pip_action_toggle_pause),
            getString(R.string.pip_action_toggle_pause),
            pauseIntent
        )
        val cycleAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_menu_rotate),
            getString(R.string.pip_action_next_vm),
            getString(R.string.pip_action_next_vm),
            cycleIntent
        )
        return listOf(pauseAction, cycleAction)
    }
}

@Composable
private fun FullscreenGuestVmContainer(
    vm: VmInstanceEntity,
    state: HypervisorUiState,
    viewModel: HypervisorViewModel
) {
    BackHandler {
        if (state.guestScreen != GuestDeviceScreen.Home || state.isGuestShadeOpen) {
            viewModel.goGuestBack()
        } else {
            viewModel.exitVmFullscreen()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VirtualGuestDisplay(
            vm = vm,
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
            vmFiles = state.activeVmFiles,
            compactPipMode = false,
            resolveHostFile = { viewModel.resolveHostFile(it) },
            onBootVm = { viewModel.bootVm(vm.id) },
            onTogglePauseVm = { viewModel.togglePauseVm(vm.id) },
            onLaunchApp = { viewModel.launchGuestApp(it) },
            onOpenDrawer = { viewModel.openGuestAppDrawer() },
            onOpenRecents = { viewModel.openGuestRecents() },
            onGoHome = { viewModel.goGuestHome() },
            onGoBack = { viewModel.goGuestBack() },
            onCloseRecentTask = { viewModel.closeRecentGuestTask(it) },
            onToggleShade = { viewModel.toggleGuestShade() },
            onBrightnessChange = { viewModel.setGuestBrightness(it) },
            onToggleWifi = { viewModel.toggleGuestWifi() },
            onToggleLayoutBounds = { viewModel.toggleGuestLayoutBounds() },
            onApplyDisplay = { w, h, d, hz -> viewModel.updateDisplayProfile(vm.id, w, h, d, hz) },
            onInstallApkRequest = { viewModel.openGuestPackageInstaller(it) },
            onInstallApkConfirm = { apk, cb -> viewModel.installApkInVm(apk, cb) },
            onUninstallApp = { viewModel.uninstallGuestApp(it) },
            onSaveNote = { title, body -> viewModel.saveGuestNote(vm.id, title, body) },
            onOpenHostVault = { viewModel.selectWorkspaceTab(VmWorkspaceTab.FILES) },
            fullscreenMode = true,
            modifier = Modifier.fillMaxSize()
        )

        // Unobtrusive overlay button to exit fullscreen back to host viewport
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(top = 12.dp, end = 8.dp)
                .clickable { viewModel.exitVmFullscreen() }
                .testTag("exit_fullscreen_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FullscreenExit,
                    contentDescription = "Return to viewport controls",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Viewport",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PipRemoteActionReceiver(
    onTogglePause: () -> Unit,
    onCycleVm: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PIP_CONTROL) return
                when (intent.getIntExtra(EXTRA_PIP_COMMAND, 0)) {
                    CMD_TOGGLE_PAUSE -> onTogglePause()
                    CMD_CYCLE_VM -> onCycleVm()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactHypervisorTopBar(
    title: String,
    showBack: Boolean,
    darkTheme: Boolean,
    workspaceTabs: List<WorkspaceTabItem>,
    selectedTab: VmWorkspaceTab,
    showTopWorkspaceTabs: Boolean,
    onSelectTab: (VmWorkspaceTab) -> Unit,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (showBack) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("back_to_vm_chooser")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to VM list",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toggle_theme_button")
                ) {
                    Icon(
                        imageVector = if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = "Toggle light or dark theme",
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            if (showTopWorkspaceTabs) {
                val selectedIndex = workspaceTabs
                    .indexOfFirst { it.tab == selectedTab }
                    .coerceAtLeast(0)
                PrimaryTabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    workspaceTabs.forEachIndexed { index, item ->
                        val isSelected = selectedIndex == index
                        Tab(
                            selected = isSelected,
                            onClick = { onSelectTab(item.tab) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(item.testTag)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = stringResource(item.labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun KestrelHypervisorShell(
    state: HypervisorUiState,
    viewModel: HypervisorViewModel,
    onEnterSystemPip: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val activeVm = state.activeVm
    val workspaceTabs = remember {
        listOf(
            WorkspaceTabItem(VmWorkspaceTab.DISPLAY, R.string.nav_viewport, Icons.Rounded.Smartphone, "nav_viewport"),
            WorkspaceTabItem(VmWorkspaceTab.FILES, R.string.nav_files, Icons.Rounded.FolderCopy, "nav_files"),
            WorkspaceTabItem(VmWorkspaceTab.HARDWARE, R.string.nav_engine, Icons.Rounded.DeveloperBoard, "nav_engine")
        )
    }

    LaunchedEffect(state.statusBanner) {
        val msg = state.statusBanner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    if (activeVm != null) {
        BackHandler {
            if (state.activeWorkspaceTab == VmWorkspaceTab.DISPLAY &&
                (state.guestScreen != GuestDeviceScreen.Home || state.isGuestShadeOpen)
            ) {
                viewModel.goGuestBack()
            } else {
                viewModel.returnToVmChooser()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp && activeVm != null

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CompactHypervisorTopBar(
                    title = activeVm?.name ?: stringResource(R.string.app_name),
                    showBack = activeVm != null,
                    darkTheme = state.darkTheme,
                    workspaceTabs = workspaceTabs,
                    selectedTab = state.activeWorkspaceTab,
                    showTopWorkspaceTabs = activeVm != null && !useNavRail,
                    onSelectTab = { viewModel.selectWorkspaceTab(it) },
                    onBack = { viewModel.returnToVmChooser() },
                    onToggleTheme = { viewModel.toggleTheme() }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (activeVm == null) {
                    InstancesScreen(
                        state = state,
                        onLoadAndBootVm = { viewModel.loadAndOpenVm(it) },
                        onStopVm = { viewModel.stopVm(it) },
                        onDeleteVm = { viewModel.deleteVmInstance(it) },
                        onCreateVm = { name, os, api, abi, w, h, dpi, hz, cores, ram, seccomp, accent ->
                            viewModel.createVmInstance(name, os, api, abi, w, h, dpi, hz, cores, ram, seccomp, accent)
                        }
                    )
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (useNavRail) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                workspaceTabs.forEach { item ->
                                    NavigationRailItem(
                                        selected = state.activeWorkspaceTab == item.tab,
                                        onClick = { viewModel.selectWorkspaceTab(item.tab) },
                                        icon = {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = stringResource(item.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(item.labelRes)) },
                                        modifier = Modifier.testTag(item.testTag)
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (state.activeWorkspaceTab) {
                                VmWorkspaceTab.DISPLAY -> ViewportScreen(
                                    state = state,
                                    resolveHostFile = { viewModel.resolveHostFile(it) },
                                    onBootVm = { viewModel.bootVm(it) },
                                    onTogglePauseVm = { viewModel.togglePauseVm(it) },
                                    onLaunchGuestApp = { viewModel.launchGuestApp(it) },
                                    onOpenGuestDrawer = { viewModel.openGuestAppDrawer() },
                                    onOpenGuestRecents = { viewModel.openGuestRecents() },
                                    onGoGuestHome = { viewModel.goGuestHome() },
                                    onGoGuestBack = { viewModel.goGuestBack() },
                                    onCloseRecentTask = { viewModel.closeRecentGuestTask(it) },
                                    onToggleShade = { viewModel.toggleGuestShade() },
                                    onBrightnessChange = { viewModel.setGuestBrightness(it) },
                                    onToggleWifi = { viewModel.toggleGuestWifi() },
                                    onToggleLayoutBounds = { viewModel.toggleGuestLayoutBounds() },
                                    onInstallApkRequest = { viewModel.openGuestPackageInstaller(it) },
                                    onInstallApkConfirm = { apk, cb -> viewModel.installApkInVm(apk, cb) },
                                    onUninstallApp = { viewModel.uninstallGuestApp(it) },
                                    onSaveNote = { vmId, title, body -> viewModel.saveGuestNote(vmId, title, body) },
                                    onOpenHostVault = { viewModel.selectWorkspaceTab(VmWorkspaceTab.FILES) },
                                    onEnterFullscreen = { viewModel.enterVmFullscreen() },
                                    onEnterSystemPip = onEnterSystemPip,
                                    onToggleMiniFloat = { viewModel.toggleMiniFloat() },
                                    onApplyDisplayProfile = { vmId, w, h, dpi, hz ->
                                        viewModel.updateDisplayProfile(vmId, w, h, dpi, hz)
                                    }
                                )

                                VmWorkspaceTab.FILES -> FileVaultScreen(
                                    state = state,
                                    onImportUri = { uri, cat -> viewModel.importFileToVault(uri, cat) },
                                    onInstallApkInVm = { apkFile -> viewModel.openGuestPackageInstaller(apkFile) },
                                    onExportFile = { file, dest -> viewModel.exportFileFromVault(file, dest) },
                                    onDeleteFile = { viewModel.deleteVaultFile(it) },
                                    onPreviewFile = { viewModel.openFilePreview(it) },
                                    onDismissPreview = { viewModel.dismissFilePreview() },
                                    resolveHostFile = { viewModel.resolveHostFile(it) }
                                )

                                VmWorkspaceTab.HARDWARE -> EngineScreen(
                                    state = state,
                                    onUpdatePassthrough = { vm, egl, sensors, audio, binder, root, unlocked, seccomp ->
                                        viewModel.updateHardwarePassthrough(
                                            vm = vm,
                                            eglBridge = egl,
                                            sensors = sensors,
                                            audio = audio,
                                            binderProxy = binder,
                                            rootEnabled = root,
                                            bootloaderUnlocked = unlocked,
                                            seccompMode = seccomp
                                        )
                                    },
                                    onProbeBinderAndPath = { viewModel.probeBinderAndPathJail(it) },
                                    onPulseAudioHal = { viewModel.pulseAudioHal(it) },
                                    onVerifyRootfs = { viewModel.verifyActiveRootfs(it) },
                                    onImportRootfsArchive = { vm, uri -> viewModel.importRootfsArchive(vm, uri) },
                                    onDownloadRootfsUrl = { vm, url, sha -> viewModel.downloadRootfsFromUrl(vm, url, sha) }
                                )
                            }
                        }
                    }

                    if (state.isMiniFloatVisible) {
                        DraggableMiniFloatingVm(
                            vm = activeVm,
                            hostHardware = state.hostHardware,
                            hostSensors = state.hostSensors,
                            resolveHostFile = { viewModel.resolveHostFile(it) },
                            onBootVm = { viewModel.bootVm(activeVm.id) },
                            onTogglePause = { viewModel.togglePauseVm(activeVm.id) },
                            onExpandToViewport = {
                                viewModel.toggleMiniFloat(false)
                                viewModel.selectWorkspaceTab(VmWorkspaceTab.DISPLAY)
                            },
                            onClose = { viewModel.toggleMiniFloat(false) }
                        )
                    }
                }
            }
        }
    }
}
