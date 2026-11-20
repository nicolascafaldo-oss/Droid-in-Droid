package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.VmSandboxFileEntity
import com.example.ui.HypervisorUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.io.File

@Composable
fun FileVaultScreen(
    state: HypervisorUiState,
    onImportUri: (android.net.Uri, String?) -> Unit,
    onInstallApkInVm: (VmSandboxFileEntity) -> Unit,
    onExportFile: (VmSandboxFileEntity, android.net.Uri) -> Unit,
    onDeleteFile: (VmSandboxFileEntity) -> Unit,
    onPreviewFile: (VmSandboxFileEntity) -> Unit,
    onDismissPreview: () -> Unit,
    resolveHostFile: (String) -> File,
    modifier: Modifier = Modifier
) {
    var categoryFilter by remember { mutableStateOf("ALL") }
    var pendingExportFile by remember { mutableStateOf<VmSandboxFileEntity?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) onImportUri(uri, "MEDIA")
        }
    )

    val documentImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) onImportUri(uri, null)
        }
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { destUri ->
            val file = pendingExportFile
            pendingExportFile = null
            if (destUri != null && file != null) {
                onExportFile(file, destUri)
            }
        }
    )

    val targetVm = state.vaultVm
    val filteredFiles = remember(state.activeVmFiles, categoryFilter) {
        if (categoryFilter == "ALL") state.activeVmFiles
        else state.activeVmFiles.filter { it.category == categoryFilter }
    }
    val totalKb = remember(state.activeVmFiles) {
        state.activeVmFiles.sumOf { it.sizeBytes } / 1024L
    }

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
                        text = "Files — ${targetVm?.name ?: "Sandbox"}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Import APKs, media, or documents into this VM, or export sandbox files to your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_media_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import_media))
                    }

                    OutlinedButton(
                        onClick = { documentImportLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_apk_file_button")
                    ) {
                        Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import_file))
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("ALL", "APK", "MEDIA", "DOCUMENT", "SYSTEM").forEach { cat ->
                        FilterChip(
                            selected = categoryFilter == cat,
                            onClick = { categoryFilter = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${state.activeVmFiles.size} files (${totalKb} KB)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (filteredFiles.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderShared,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            )
                            Text("No files in this filter", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Use Import Media or Import APK / File above to transfer items into ${targetVm?.name ?: "this VM"}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredFiles, key = { it.id }) { file ->
                    SandboxFileCard(
                        file = file,
                        hostFile = resolveHostFile(file.hostRelativePath),
                        onInstallApk = { onInstallApkInVm(file) },
                        onExport = {
                            pendingExportFile = file
                            exportLauncher.launch(file.fileName)
                        },
                        onInspect = { onPreviewFile(file) },
                        onDelete = { onDeleteFile(file) }
                    )
                }
            }
        }
    }

    state.previewFileContent?.let { (file, content) ->
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = { Text(file.virtualPath, style = MaterialTheme.typography.titleSmall) },
            text = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = content,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissPreview) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SandboxFileCard(
    file: VmSandboxFileEntity,
    hostFile: File,
    onInstallApk: () -> Unit,
    onExport: () -> Unit,
    onInspect: () -> Unit,
    onDelete: () -> Unit
) {
    val isImage = file.mimeType.startsWith("image/") && hostFile.exists()
    val isInspectableText = file.category == "SYSTEM" ||
        file.mimeType.startsWith("text/") ||
        file.mimeType == "application/xml" ||
        file.mimeType == "application/json"

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isImage) {
                    AsyncImage(
                        model = hostFile,
                        contentDescription = file.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (file.category) {
                                    "APK" -> Icons.Rounded.Android
                                    "MEDIA" -> Icons.Rounded.Image
                                    "SYSTEM" -> Icons.Rounded.Code
                                    else -> Icons.Rounded.Description
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(file.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.virtualPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = buildString {
                        append(formatFileSize(file.sizeBytes))
                        if (file.apkPackageName != null) {
                            append(" • ${file.apkPackageName}")
                            if (file.apkVersionName != null) append(" v${file.apkVersionName}")
                        }
                    }
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (file.category != "SYSTEM") {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${file.fileName}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (file.category == "APK") {
                    Button(
                        onClick = onInstallApk,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("vault_install_apk_${file.id}")
                    ) {
                        Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Install in VM", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (isInspectableText) {
                    OutlinedButton(
                        onClick = onInspect,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Inspect", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Button(
                    onClick = onExport,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.testTag("export_file_${file.id}")
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_export_file), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
