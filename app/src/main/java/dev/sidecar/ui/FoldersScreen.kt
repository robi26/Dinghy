package dev.sidecar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import dev.sidecar.engine.FolderInfo
import dev.sidecar.engine.SyncEngine
import kotlinx.coroutines.launch

internal fun formatBytes(bytes: Long): String {
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

/** Folder list plus the add-folder form. */
@Composable
fun FoldersSection(enabled: Boolean, onOpenFolder: (FolderInfo) -> Unit) {
    var folders by remember { mutableStateOf<List<FolderInfo>>(emptyList()) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(enabled, reloadToken) {
        folders = if (enabled) SyncEngine.folders() else emptyList()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                "Folders",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (folders.isEmpty()) {
                Text(
                    "No folders yet. Add one with the same ID as on your other device.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                folders.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFolder(folder) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(folder.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(if (folder.isSelective) "on-demand" else "full sync")
                                    if (folder.isPaused) append(" · paused")
                                    append(" · ${folder.globalFiles} files")
                                    // The point of the app: stored is normally a
                                    // small fraction of what is visible.
                                    append(
                                        " · ${formatBytes(folder.localBytes)} of " +
                                            formatBytes(folder.globalBytes),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FolderShares(folder.id)
                    HorizontalDivider()
                }
            }

            AddFolderForm(enabled = enabled, onAdded = { reloadToken++ })
        }
    }
}

/**
 * Which peers a folder is shared with. A folder that exists only locally will
 * never receive an index, so this is not optional polish.
 */
@Composable
private fun FolderShares(folderId: String) {
    var shares by remember(folderId) { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var reload by remember(folderId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderId, reload) { shares = SyncEngine.folderShares(folderId) }

    shares.forEach { (deviceId, shared) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = shared,
                onCheckedChange = { want ->
                    scope.launch {
                        runCatching { SyncEngine.shareFolder(folderId, deviceId, want) }
                        reload++
                    }
                },
            )
            Text(
                "share with ${deviceId.take(7)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AddFolderForm(enabled: Boolean, onAdded: () -> Unit) {
    var folderId by remember { mutableStateOf("") }
    var onDemand by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = folderId,
            onValueChange = { folderId = it; message = null },
            label = { Text("Folder ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = onDemand, onCheckedChange = { onDemand = it })
            Column {
                Text("On-demand", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Receive the file list without downloading anything",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = enabled && folderId.isNotBlank(),
            onClick = {
                val id = folderId.trim()
                scope.launch {
                    message = try {
                        SyncEngine.addFolder(id, onDemand)
                        folderId = ""
                        onAdded()
                        "Added $id"
                    } catch (t: Throwable) {
                        t.message ?: t.javaClass.simpleName
                    }
                }
            },
        ) { Text("Add folder") }
    }
}
