package ch.steigis.dinghy.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.DeviceInfo
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.FolderInfo
import ch.steigis.dinghy.engine.SyncEngine
import ch.steigis.dinghy.photos.hasFullLibraryAccess
import kotlinx.coroutines.delay
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
        while (true) {
            folders = if (running) SyncEngine.folders() else emptyList()
            delay(PEER_REFRESH_MILLIS)
        }
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
    append(
        " · " + pluralStringResource(
            R.plurals.folder_files,
            // Counts this large are not real, but the cast has to be total.
            globalFiles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            globalFiles,
        ),
    )
    // The point of the app: stored is normally a small fraction of visible.
    append(" · ${formatBytes(localBytes)} of ${formatBytes(globalBytes)}")
}

/** Adding a folder, on a screen of its own rather than under the list. */
@Composable
fun AddFolderScreen(contentPadding: PaddingValues, onAdded: () -> Unit) {
    var folderId by remember { mutableStateOf("") }
    var onDemand by remember { mutableStateOf(true) }
    var photos by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun add() {
        val id = folderId.trim()
        scope.launch {
            message = try {
                if (photos) SyncEngine.addPhotoFolder(id) else SyncEngine.addFolder(id, onDemand)
                folderId = ""
                // Back to the list the folder is now in.
                onAdded()
                null
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
        }
    }

    // Whether the whole library can be read, kept current across a trip to the
    // system settings and back.
    var fullAccess by remember { mutableStateOf(hasFullLibraryAccess(context)) }
    var useSettings by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            fullAccess = hasFullLibraryAccess(context)
            // Coming back from the settings with access granted: the warning
            // about not having it is now wrong.
            if (fullAccess) message = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Partial access ("Select photos") leaves READ_MEDIA_IMAGES denied, which
    // is the outcome we want: a folder holding only the photos picked in that
    // dialog would delete the rest from every device once it synced.
    val photoAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        fullAccess = granted[readImagesPermission()] == true && hasFullLibraryAccess(context)
        if (fullAccess) {
            add()
        } else {
            // Asking again from here reopens the photo picker rather than the
            // allow/limit dialog, so there is no way back to full access from
            // inside the app: the system settings are the only route.
            message = context.getString(R.string.photo_folder_denied)
            useSettings = true
        }
    }

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
                    Checkbox(
                        checked = photos,
                        onCheckedChange = { photos = it; message = null },
                    )
                    Column {
                        Text(
                            stringResource(R.string.folder_photos_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.folder_photos_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // A photo folder has nothing to fetch on demand: it sends the
                // library and never receives anything.
                if (!photos) {
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
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(
                    enabled = folderId.isNotBlank(),
                    onClick = {
                        when {
                            !photos || fullAccess -> add()
                            useSettings -> context.startActivity(appSettings(context.packageName))
                            else -> photoAccess.launch(photoPermissions())
                        }
                    },
                ) {
                    Text(
                        when {
                            !photos -> stringResource(R.string.action_add_folder_button)
                            useSettings && !fullAccess ->
                                stringResource(R.string.action_photo_settings)

                            else -> stringResource(R.string.action_add_photo_folder_button)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One folder's settings. Sharing is the load-bearing part: a folder shared with
 * nobody will never receive an index, so it is not optional polish.
 */
@Composable
fun FolderSettingsScreen(
    folderId: String,
    contentPadding: PaddingValues,
    onRemoved: () -> Unit,
) {
    var devices by remember(folderId) { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var shares by remember(folderId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var reload by remember(folderId) { mutableStateOf(0) }
    var folder by remember(folderId) { mutableStateOf<FolderInfo?>(null) }
    var confirmFullSync by remember(folderId) { mutableStateOf(false) }
    var confirmRemove by remember(folderId) { mutableStateOf(false) }
    var deleteFiles by remember(folderId) { mutableStateOf(false) }
    var error by remember(folderId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderId, reload) {
        while (true) {
            devices = SyncEngine.devices()
            shares = SyncEngine.folderShares(folderId).toMap()
            folder = SyncEngine.folders().firstOrNull { it.id == folderId }
            delay(PEER_REFRESH_MILLIS)
        }
    }

    TabColumn(contentPadding) {
        SectionLabel(stringResource(R.string.label_folder_sync))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (folder?.isPhotoFolder == true) {
                                R.string.folder_photos_title
                            } else {
                                R.string.folder_on_demand_title
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            if (folder?.isPhotoFolder == true) {
                                R.string.folder_photos_summary
                            } else {
                                R.string.folder_on_demand_summary
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A photo folder is neither selective nor writable: there is
                // nothing here to switch, and switching it would rewrite an
                // .stignore the virtual filesystem cannot hold.
                if (folder?.isPhotoFolder != true) Switch(
                    checked = folder?.isSelective == true,
                    enabled = folder != null,
                    onCheckedChange = { wantSelective ->
                        if (wantSelective) {
                            // Turning it on only stops fetching more; what is
                            // already here is ignored, not deleted.
                            scope.launch {
                                runCatching { SyncEngine.setFolderSelective(folderId, true) }
                                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                                reload++
                            }
                        } else {
                            // Turning it off starts downloading the whole
                            // folder, so it gets asked about first.
                            confirmFullSync = true
                        }
                    },
                )
            }
        }

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

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(onClick = { deleteFiles = false; confirmRemove = true }) {
            Text(stringResource(R.string.action_remove_folder))
        }
    }

    if (confirmFullSync) {
        AlertDialog(
            onDismissRequest = { confirmFullSync = false },
            title = { Text(stringResource(R.string.full_sync_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.full_sync_body,
                        formatBytes(folder?.globalBytes ?: 0L),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFullSync = false
                    scope.launch {
                        runCatching { SyncEngine.setFolderSelective(folderId, false) }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        reload++
                    }
                }) { Text(stringResource(R.string.action_download_everything)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFullSync = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_folder_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remove_folder_body))
                    // Opt in, not out. Unlinking leaves the downloaded files
                    // alone; deleting them is not recoverable from in here, so
                    // it has to be asked for rather than assumed.
                    //
                    // A photo folder holds no files of its own -- they are the
                    // library's -- so there is nothing to offer to delete, and
                    // offering it would read as "delete my photos".
                    if (folder?.isPhotoFolder != true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = deleteFiles,
                                onCheckedChange = { deleteFiles = it },
                            )
                            Text(
                                stringResource(R.string.remove_folder_delete),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (deleteFiles) {
                        Text(
                            stringResource(R.string.remove_folder_delete_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        runCatching { SyncEngine.removeFolder(folderId, deleteFiles) }
                            .onSuccess { onRemoved() }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                    }
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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

/** The permission that reading the photo library needs, by platform version. */
private fun readImagesPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Asked for together: without ACCESS_MEDIA_LOCATION the system strips the GPS
 * tags out of every photo it hands over, so the backup would lose them.
 */
private fun photoPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(readImagesPermission(), Manifest.permission.ACCESS_MEDIA_LOCATION)
    } else {
        arrayOf(readImagesPermission())
    }

/** The app's own page in the system settings, where access can be widened. */
private fun appSettings(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
