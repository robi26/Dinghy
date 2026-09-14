package ch.steigis.dinghy.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.steigis.dinghy.R
import ch.steigis.dinghy.binding.core.Core
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.SyncEngine
import ch.steigis.dinghy.service.SyncService
import ch.steigis.dinghy.settings.Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opening the app resumes syncing unless it was explicitly stopped.
        // Without this the node would only ever run after a manual tap, which
        // is not what a background sync daemon should do.
        if (Settings(this).syncEnabled && !SyncEngine.isRunning) {
            SyncService.start(this)
        }

        // targetSdk 36 means Android 15 and up draws this edge to edge whether
        // or not it asks to, so the insets have to be handled rather than
        // ignored. Without it the title sits under the status bar clock and the
        // bottom row sits under the gesture handle.
        enableEdgeToEdge()

        setContent {
            DinghyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DinghyApp()
                }
            }
        }
    }
}

/**
 * The top-level destinations. Splitting them up is what the bottom bar is for: everything used to be one scroll, so the device ID, the folder list, the
 * add-folder form, the add-device form and the sync settings all competed for
 * the same column.
 */
private enum class Tab(val labelRes: Int, val iconRes: Int) {
    Devices(R.string.tab_devices, R.drawable.ic_devices),
    Folders(R.string.tab_folders, R.drawable.ic_folder),
    Search(R.string.tab_search, R.drawable.ic_search),
    Settings(R.string.tab_settings, R.drawable.ic_settings),
}

/**
 * Screens pushed on top of a tab rather than reached from the bar. Small enough
 * that a navigation library would be overhead.
 */
private sealed interface Detail {
    data class Browse(val folderId: String, val label: String, val prefix: String) : Detail
    data class Search(val folderId: String, val label: String) : Detail
    data class Device(val deviceId: String, val label: String) : Detail
    data object ThisDevice : Detail
    data object AddDevice : Detail
    data object AddFolder : Detail
    data class FolderSettings(val folderId: String, val label: String) : Detail
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DinghyApp() {
    var tab by remember { mutableStateOf(Tab.Devices) }
    var stack by remember { mutableStateOf<List<Detail>>(emptyList()) }
    val detail = stack.lastOrNull()
    // Holds each tab's saveable state while it is off screen, so switching tabs
    // -- or pushing the browser on top of one -- does not reset what the user
    // had typed or how far they had scrolled.
    val tabState = rememberSaveableStateHolder()

    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (detail) {
                                is Detail.Browse -> detail.label
                                is Detail.Search -> detail.label
                                is Detail.Device -> detail.label
                                Detail.ThisDevice -> stringResource(R.string.label_this_device)
                                Detail.AddDevice -> stringResource(R.string.label_add_device)
                                Detail.AddFolder -> stringResource(R.string.action_add_folder_button)
                                is Detail.FolderSettings -> detail.label
                                null -> stringResource(tab.labelRes)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val crumb = when (detail) {
                            is Detail.Browse -> "/" + detail.prefix.trimEnd('/')
                            is Detail.Search -> stringResource(R.string.action_search)
                            else -> null
                        }
                        if (crumb != null) {
                            Text(
                                crumb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (detail != null) {
                        IconButton(onClick = { stack = stack.dropLast(1) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (detail is Detail.Browse) {
                        TextButton(onClick = {
                            stack = stack + Detail.Search(detail.folderId, detail.label)
                        }) { Text(stringResource(R.string.action_search)) }
                        IconButton(onClick = {
                            stack = stack + Detail.FolderSettings(detail.folderId, detail.label)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription =
                                    stringResource(R.string.label_folder_settings),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        // A tab stays selected while you are inside one of its
                        // detail screens, so browsing a folder does not leave
                        // the bar looking as though nothing is open.
                        selected = tab == entry,
                        onClick = {
                            // Re-selecting the current tab pops back to its root,
                            // which is the usual way out of a deep folder.
                            stack = emptyList()
                            tab = entry
                        },
                        icon = {
                            Icon(
                                painter = painterResource(entry.iconRes),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val openFolder: (ch.steigis.dinghy.engine.FolderInfo) -> Unit = { folder ->
            stack = stack + Detail.Browse(folder.id, folder.label, "")
        }
        when (detail) {
            is Detail.Search -> Box(modifier = Modifier.padding(innerPadding)) {
                SearchScreen(
                    folderId = detail.folderId,
                    onOpenDirectory = { id, label, prefix ->
                        stack = stack + Detail.Browse(id, label, prefix)
                    },
                )
            }

            Detail.ThisDevice -> ThisDeviceScreen(innerPadding)

            Detail.AddFolder -> AddFolderScreen(
                contentPadding = innerPadding,
                onAdded = { stack = stack.dropLast(1) },
            )

            is Detail.FolderSettings -> FolderSettingsScreen(
                folderId = detail.folderId,
                contentPadding = innerPadding,
                // All the way out, not one step: the browser underneath this
                // screen is showing a folder that no longer exists.
                onRemoved = { stack = emptyList() },
            )

            Detail.AddDevice -> TabColumn(innerPadding) {
                AddDeviceCard(
                    enabled = true,
                    onAdded = { stack = stack.dropLast(1) },
                )
            }

            is Detail.Device -> DeviceDetailScreen(
                deviceId = detail.deviceId,
                contentPadding = innerPadding,
                onRemoved = { stack = stack.dropLast(1) },
            )

            is Detail.Browse -> Box(modifier = Modifier.padding(innerPadding)) {
                BrowserScreen(
                    folderId = detail.folderId,
                    prefix = detail.prefix,
                    onOpenDirectory = { childPrefix ->
                        stack = stack + Detail.Browse(detail.folderId, detail.label, childPrefix)
                    },
                )
            }

            null -> tabState.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.Devices -> DevicesScreen(
                        contentPadding = innerPadding,
                        onOpenDevice = { id, label ->
                            stack = stack + Detail.Device(id, label)
                        },
                        onOpenThisDevice = { stack = stack + Detail.ThisDevice },
                        onAddDevice = { stack = stack + Detail.AddDevice },
                    )
                    Tab.Folders -> FoldersScreen(
                        contentPadding = innerPadding,
                        onOpenFolder = openFolder,
                        onAddFolder = { stack = stack + Detail.AddFolder },
                    )
                // No folderId: the tab searches every folder. The app bar's
                // Search action inside a folder narrows it to that one.
                    Tab.Search -> Box(modifier = Modifier.padding(innerPadding)) {
                        SearchScreen(
                            onOpenDirectory = { id, label, prefix ->
                                stack = stack + Detail.Browse(id, label, prefix)
                            },
                        )
                    }
                    Tab.Settings -> SettingsScreen(innerPadding)
                }
            }
        }
    }
}

/** Shared column treatment for a tab: insets first, then scroll, then padding. */
@Composable
internal fun TabColumn(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val state by SyncEngine.state.collectAsStateWithLifecycle()

    TabColumn(contentPadding) {
        // Engine control lives here rather than on Devices, which is a list of
        // peers and should stay one.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(statusLine(state), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            Settings(context).syncEnabled = true
                            SyncService.start(context)
                        },
                        enabled = state is EngineState.Stopped || state is EngineState.Failed,
                    ) { Text(stringResource(R.string.action_start)) }
                    OutlinedButton(
                        onClick = {
                            Settings(context).syncEnabled = false
                            SyncService.stop(context)
                        },
                        enabled = state !is EngineState.Stopped,
                    ) { Text(stringResource(R.string.action_stop)) }
                }
            }
        }

        ConditionsCard()
        Text(
            "${stringResource(R.string.label_engine)}: ${Core.coreVersion()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun statusLine(state: EngineState): String = when (state) {
    is EngineState.Stopped -> stringResource(R.string.status_stopped)
    is EngineState.Loading -> stringResource(R.string.status_loading)
    is EngineState.Starting -> stringResource(R.string.status_starting)
    is EngineState.Failed -> stringResource(R.string.status_failed, state.message)
    is EngineState.Running ->
        pluralStringResource(
            R.plurals.status_connected,
            state.connectedPeers,
            state.connectedPeers,
            state.totalPeers,
        )
}

/**
 * The three conditions that silently stop background syncing. Each is checked
 * rather than assumed, because on GrapheneOS network access in particular is a
 * revocable per-app permission.
 */
@Composable
internal fun SetupWarnings() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        WarningCard(
            title = stringResource(R.string.warn_notifications_title),
            body = stringResource(R.string.warn_notifications_body),
            action = stringResource(R.string.warn_notifications_action),
            onClick = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    }

    val power = context.getSystemService(PowerManager::class.java)
    if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
        WarningCard(
            title = stringResource(R.string.warn_battery_title),
            body = stringResource(R.string.warn_battery_body),
            action = stringResource(R.string.warn_battery_action),
            onClick = {
                // Deliberately the settings screen rather than the direct
                // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog: the latter is
                // policy-restricted and the user should see what they grant.
                context.startActivity(
                    Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            },
        )
    }

    if (context.checkSelfPermission(Manifest.permission.INTERNET) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        WarningCard(
            title = stringResource(R.string.warn_network_title),
            body = stringResource(R.string.warn_network_body),
            action = stringResource(R.string.warn_network_action),
            onClick = {
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )
    }
}

@Composable
private fun WarningCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

internal fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("device id", text))
}
