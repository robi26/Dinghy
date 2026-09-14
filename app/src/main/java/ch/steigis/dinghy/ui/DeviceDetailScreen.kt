package ch.steigis.dinghy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.DeviceInfo
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.SyncEngine
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** This device: the id another device needs, and where it is listening. */
@Composable
fun ThisDeviceScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val state by SyncEngine.state.collectAsStateWithLifecycle()
    val running = state as? EngineState.Running

    TabColumn(contentPadding) {
        if (running == null) {
            Text(statusLine(state), style = MaterialTheme.typography.bodyMedium)
            return@TabColumn
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailLabel(stringResource(R.string.label_device_id))
                // In full and monospace: this is the string read out or compared
                // against another device, so truncating it defeats the point.
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
                ) { Text(stringResource(R.string.action_copy)) }
            }
        }

        if (running.listenAddresses.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DetailLabel(stringResource(R.string.label_listening_on))
                    running.listenAddresses.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One peer: what it is called, whether it is reachable, and how to forget it. */
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    contentPadding: PaddingValues,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SyncEngine.state.collectAsStateWithLifecycle()
    var device by remember(deviceId) { mutableStateOf<DeviceInfo?>(null) }
    var confirmRemove by remember(deviceId) { mutableStateOf(false) }
    var error by remember(deviceId) { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceId, state) { device = SyncEngine.device(deviceId) }

    TabColumn(contentPadding) {
        val current = device

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    when {
                        current == null -> stringResource(R.string.device_not_connected)
                        current.isPaused -> stringResource(R.string.device_paused)
                        current.isConnected -> stringResource(R.string.device_connected)
                        else -> stringResource(R.string.device_not_connected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                // "Never" rather than 1970: a peer that has not connected has a
                // zero timestamp, which the engine layer filters to null.
                Text(
                    current?.lastSeen?.let {
                        stringResource(
                            R.string.device_last_seen,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(it)),
                        )
                    } ?: stringResource(R.string.device_last_seen_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailLabel(stringResource(R.string.label_device_id))
                Text(
                    deviceId,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { context.copyToClipboard(deviceId) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.action_copy)) }
            }
        }

        val addresses = current?.addresses.orEmpty()
        if (addresses.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DetailLabel(stringResource(R.string.label_addresses))
                    addresses.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(onClick = { confirmRemove = true }) {
            Text(stringResource(R.string.action_remove_device))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_device_title)) },
            text = { Text(stringResource(R.string.remove_device_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        runCatching { SyncEngine.removeDevice(deviceId) }
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

@Composable
private fun DetailLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
