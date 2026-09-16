package ch.steigis.dinghy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browses one directory level of a folder's global index.
 *
 * Every entry here exists in the index whether or not its content is on the
 * device. The checkbox is the pin: ticking it writes an exception into
 * .stignore and the file is fetched.
 */
@Composable
fun BrowserScreen(
    folderId: String,
    prefix: String,
    onOpenDirectory: (String) -> Unit,
) {
    var entries by remember(folderId, prefix) { mutableStateOf<List<EntryInfo>?>(null) }
    var error by remember(folderId, prefix) { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    var sheetFor by remember { mutableStateOf<EntryInfo?>(null) }
    // A photo folder is the library seen through Syncthing: there is nothing
    // to pin (it is all here already) and nothing to download.
    var photoFolder by remember(folderId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderId) { photoFolder = SyncEngine.isPhotoFolder(folderId) }

    sheetFor?.let { selected ->
        FileSheet(
            folderId = folderId,
            entry = selected,
            onDismiss = { sheetFor = null; reloadToken++ },
        )
    }

    LaunchedEffect(folderId, prefix, reloadToken) {
        error = null
        entries = try {
            SyncEngine.browse(folderId, prefix)
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            emptyList()
        }
    }

    // A pinned file arrives asynchronously, so the list has to be re-read until
    // it lands -- otherwise a file the user just asked for keeps claiming it is
    // not downloaded. Polling stops as soon as nothing is outstanding.
    val pending = entries.orEmpty().any { it.isSelected && it.isRemoteOnly }
    LaunchedEffect(folderId, prefix, pending) {
        while (pending) {
            delay(1500)
            entries = runCatching { SyncEngine.browse(folderId, prefix) }.getOrDefault(entries)
        }
    }

    val current = entries
    when {
        error != null -> Text(
            error!!,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )

        current == null -> Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        current.isEmpty() -> Text(
            "Nothing here yet. If this folder was just added, the index is still arriving.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(current, key = { it.path }) { entry ->
                EntryRow(
                    entry = entry,
                    photoFolder = photoFolder,
                    onOpen = {
                        if (entry.isDirectory) {
                            onOpenDirectory(entry.path.trimEnd('/') + "/")
                        } else {
                            sheetFor = entry
                        }
                    },
                    onToggle = { selected ->
                        scope.launch {
                            runCatching { SyncEngine.setSelected(folderId, entry.path, selected) }
                                .onFailure { error = it.message }
                            reloadToken++
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: EntryInfo,
    photoFolder: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A folder and a file used to be distinguished only by a trailing
        // slash, which is easy to miss when scanning a long list. The icon
        // carries that now, so the slash is gone.
        Icon(
            painter = painterResource(
                if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file,
            ),
            contentDescription = stringResource(
                if (entry.isDirectory) R.string.cd_folder else R.string.cd_file,
            ),
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                entry.subtitle(photoFolder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Pinning writes to .stignore, which a photo folder has no room for:
        // the checkbox would only ever report an error.
        if (!photoFolder) {
            Checkbox(
                checked = entry.isSelected,
                onCheckedChange = onToggle,
            )
        }
    }
}

private fun EntryInfo.subtitle(photoFolder: Boolean = false): String = buildString {
    append(
        when {
            isDirectory -> "folder"
            // The engine reports a file on a virtual filesystem as not
            // present, because its path does not exist on disk. For a photo
            // folder that is backwards: this device is where it comes from.
            photoFolder -> "${formatSize(size)} · in your photo library"
            isLocallyPresent -> "${formatSize(size)} · on device"
            else -> "${formatSize(size)} · not downloaded"
        },
    )
    // Worth calling out: a conflict copy is a second version of a file that
    // changed in two places, and it is easy to mistake for a stray duplicate.
    if (isConflictCopy) append(" · conflict copy")
}

private fun formatSize(bytes: Long): String {
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
