package ch.steigis.dinghy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.DeviceInfo
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.FolderInfo
import ch.steigis.dinghy.engine.SyncEngine
import kotlinx.coroutines.launch

/**
 * The folder list, built from the same parts as the device list.
 *
 * A row per folder and nothing else. The add-folder form used to sit open at
 * the bottom of the same card, and every folder carried a stack of "share with
 * ABCDEFG" checkboxes underneath it, so the list of folders was the smallest
 * part of the folder list.
 *
 * Tapping a folder still browses it. That is the app's primary action and does
 * not deserve an extra tap, so a folder's settings live behind the browser's
 * app bar rather than behind this row.
 */
@Composable
fun FoldersScreen(
    contentPadding: PaddingValues,
    onOpenFolder: (FolderInfo) -> Unit,
    onAddFolder: () -> Unit,
) {
    val state by SyncEngine.state.collectAsStateWithLifecycle()
    val running = state is EngineState.Running
    var folders by remember { mutableStateOf<List<FolderInfo>>(emptyList()) }

    LaunchedEffect(state) {
        folders = if (running) SyncEngine.folders() else emptyList()
    }

    TabColumn(contentPadding) {
        SectionLabel(stringResource(R.string.label_synced_folders))
        Card(modifier = Modifier.fillMaxWidth()) {
            if (folders.isEmpty()) {
                EmptyNote(stringResource(R.string.folders_empty))
            } else {
                folders.forEachIndexed { index, folder ->
                    if (index > 0) HorizontalDivider()
                    NavigationRow(
                        iconRes = R.drawable.ic_folder,
                        title = folder.label,
                        subtitle = folder.summary(),
                        onClick = { onOpenFolder(folder) },
                    )
                }
            }
        }

        AddCard(
            text = stringResource(R.string.action_add_folder),
            enabled = running,
            onClick = onAddFolder,
        )
    }
}

/** The one line under a folder's name: how it syncs, and how little it stores. */
@Composable
private fun FolderInfo.summary(): String = buildString {
    append(
        if (isSelective) {
            stringResource(R.string.folder_on_demand)
        } else {
            stringResource(R.string.folder_full_sync)
        },
    )
    if (isPaused) append(" · ${stringResource(R.string.folder_paused)}")
    append(" · ${stringResource(R.string.folder_files, globalFiles)}")
    // The point of the app: stored is normally a small fraction of visible.
    append(" · ${formatBytes(localBytes)} of ${formatBytes(globalBytes)}")
}

/** Adding a folder, on a screen of its own rather than under the list. */
@Composable
fun AddFolderScreen(contentPadding: PaddingValues, onAdded: () -> Unit) {
    var folderId by remember { mutableStateOf("") }
    var onDemand by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    TabColumn(contentPadding) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = folderId,
                    onValueChange = { folderId = it; message = null },
                    label = { Text(stringResource(R.string.hint_folder_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.hint_folder_id_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onDemand, onCheckedChange = { onDemand = it })
                    Column {
                        Text(
                            stringResource(R.string.folder_on_demand_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.folder_on_demand_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(
                    enabled = folderId.isNotBlank(),
                    onClick = {
                        val id = folderId.trim()
                        scope.launch {
                            message = try {
                                SyncEngine.addFolder(id, onDemand)
                                folderId = ""
                                // Back to the list the folder is now in.
                                onAdded()
                                null
                            } catch (t: Throwable) {
                                t.message ?: t.javaClass.simpleName
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_add_folder_button)) }
            }
        }
    }
}

/**
 * One folder's settings. Sharing is the load-bearing part: a folder shared with
 * nobody will never receive an index, so it is not optional polish.
 */
@Composable
fun FolderSettingsScreen(folderId: String, contentPadding: PaddingValues) {
    var devices by remember(folderId) { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var shares by remember(folderId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var reload by remember(folderId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderId, reload) {
        devices = SyncEngine.devices()
        shares = SyncEngine.folderShares(folderId).toMap()
    }

    TabColumn(contentPadding) {
        SectionLabel(stringResource(R.string.label_shared_with))
        Card(modifier = Modifier.fillMaxWidth()) {
            if (devices.isEmpty()) {
                EmptyNote(stringResource(R.string.shares_no_devices))
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = shares[device.deviceId] == true,
                            onCheckedChange = { want ->
                                scope.launch {
                                    runCatching {
                                        SyncEngine.shareFolder(folderId, device.deviceId, want)
                                    }
                                    reload++
                                }
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            // The device's name, not seven characters of its id:
                            // the device list already knows what it is called.
                            Text(
                                device.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (device.isConnected) {
                                    stringResource(R.string.device_connected)
                                } else {
                                    stringResource(R.string.device_not_connected)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}
