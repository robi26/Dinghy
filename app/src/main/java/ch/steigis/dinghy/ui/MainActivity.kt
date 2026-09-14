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

/** Where the user is. Small enough that a navigation library would be overhead. */
private sealed interface Screen {
    data object Status : Screen
    data class Browse(val folderId: String, val label: String, val prefix: String) : Screen
    data class Search(val folderId: String, val label: String) : Screen
}

/**
 * One app bar for every screen, which is what makes browsing legible: the title
 * says which folder you are in, the line under it says where inside that folder,
 * and the arrow goes back up a level. Before this the only way back was the
 * system gesture and nothing on screen said how deep you were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DinghyApp() {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Status)) }
    val current = stack.last()

    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (current) {
                                is Screen.Status -> stringResource(R.string.app_name)
                                is Screen.Browse -> current.label
                                is Screen.Search -> current.label
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val crumb = when (current) {
                            is Screen.Browse -> "/" + current.prefix.trimEnd('/')
                            is Screen.Search -> stringResource(R.string.action_search)
                            is Screen.Status -> null
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
                    if (stack.size > 1) {
                        IconButton(onClick = { stack = stack.dropLast(1) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (current is Screen.Browse) {
                        TextButton(onClick = {
                            stack = stack + Screen.Search(current.folderId, current.label)
                        }) { Text(stringResource(R.string.action_search)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when (current) {
            is Screen.Status -> StatusScreen(
                contentPadding = innerPadding,
                onOpenFolder = { folder ->
                    stack = stack + Screen.Browse(folder.id, folder.label, "")
                },
            )

            is Screen.Search -> Box(modifier = Modifier.padding(innerPadding)) {
                SearchScreen(folderId = current.folderId)
            }

            is Screen.Browse -> Box(modifier = Modifier.padding(innerPadding)) {
                BrowserScreen(
                    folderId = current.folderId,
                    prefix = current.prefix,
                    onOpenDirectory = { childPrefix ->
                        stack = stack + Screen.Browse(current.folderId, current.label, childPrefix)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusScreen(
    contentPadding: PaddingValues,
    onOpenFolder: (ch.steigis.dinghy.engine.FolderInfo) -> Unit,
) {
    val context = LocalContext.current
    val state by SyncEngine.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Inset first, scroll second: the content keeps clear of the status
            // and gesture bars instead of sliding underneath them. The title
            // that used to be here now lives in the app bar.
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SetupWarnings()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(statusLine(state), style = MaterialTheme.typography.titleMedium)

                if (state is EngineState.Running) {
                    val running = state as EngineState.Running
                    Text(
                        stringResource(R.string.label_device_id),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Shown in full rather than ellipsised: this is the string
                    // the user reads out or compares against another device, so
                    // truncating it defeats the point. Monospace so the groups
                    // line up and a transposed character is visible.
                    Text(
                        running.deviceId,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { context.copyToClipboard(running.deviceId) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.action_copy))
                    }
                    Text(
                        "${stringResource(R.string.label_folders)}: ${running.folders}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (running.listenAddresses.isNotEmpty()) {
                        Text(
                            running.listenAddresses.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        FoldersSection(
            enabled = state is EngineState.Running,
            onOpenFolder = onOpenFolder,
        )

        AddDeviceCard(enabled = state is EngineState.Running)

        ConditionsCard()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    Settings(context).syncEnabled = true
                    SyncService.start(context)
                },
                enabled = state is EngineState.Stopped || state is EngineState.Failed,
            ) {
                Text(stringResource(R.string.action_start))
            }
            OutlinedButton(
                onClick = {
                    Settings(context).syncEnabled = false
                    SyncService.stop(context)
                },
                enabled = state !is EngineState.Stopped,
            ) {
                Text(stringResource(R.string.action_stop))
            }
        }

        Text(
            "${stringResource(R.string.label_engine)}: ${Core.coreVersion()}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun statusLine(state: EngineState): String = when (state) {
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
private fun SetupWarnings() {
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

private fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("device id", text))
}
