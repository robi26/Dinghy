package ch.steigis.dinghy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.steigis.dinghy.R
import androidx.compose.runtime.rememberCoroutineScope
import ch.steigis.dinghy.engine.DeviceInfo
import ch.steigis.dinghy.engine.DiscoveredDevice
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The device list.
 *
 * A list and nothing else: one row per peer, its connection state as a glyph
 * rather than a sentence, and everything else behind the row. What used to be
 * here -- this device's full id, the listen addresses, an inline add-device
 * form with two text fields -- was most of a screen's worth of detail competing
 * with the one thing the tab is for.
 */
@Composable
fun DevicesScreen(
    contentPadding: PaddingValues,
    onOpenDevice: (deviceId: String, label: String) -> Unit,
    onOpenThisDevice: () -> Unit,
    onAddDevice: () -> Unit,
) {
    val state by SyncEngine.state.collectAsStateWithLifecycle()
    val running = state as? EngineState.Running
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var discovered by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Keyed on the engine state so a connect or drop shows immediately, and
    // repeating because the state flow alone does not carry everything a row
    // shows -- see PEER_REFRESH_MILLIS.
    LaunchedEffect(state) {
        while (true) {
            devices = if (running != null) SyncEngine.devices() else emptyList()
            discovered = if (running != null) SyncEngine.discoveredDevices() else emptyList()
            delay(PEER_REFRESH_MILLIS)
        }
    }

    TabColumn(contentPadding) {
        SetupWarnings()

        if (running != null) {
            SectionLabel(stringResource(R.string.label_this_device))
            Card(modifier = Modifier.fillMaxWidth()) {
                NavigationRow(
                    iconRes = R.drawable.ic_devices,
                    title = stringResource(R.string.label_this_device),
                    subtitle = running.deviceId.substringBefore('-'),
                    onClick = onOpenThisDevice,
                )
            }
        }

        SectionLabel(stringResource(R.string.label_associated_devices))
        Card(modifier = Modifier.fillMaxWidth()) {
            if (devices.isEmpty()) {
                EmptyNote(stringResource(R.string.devices_empty))
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    val connected = device.isConnected && !device.isPaused
                    NavigationRow(
                        iconRes = R.drawable.ic_devices,
                        title = device.displayName,
                        subtitle = when {
                            device.isPaused -> stringResource(R.string.device_paused)
                            device.isConnected -> stringResource(R.string.device_connected)
                            else -> stringResource(R.string.device_not_connected)
                        },
                        onClick = { onOpenDevice(device.deviceId, device.displayName) },
                        // Bars for a live connection, the usual chevron
                        // otherwise: only one of them says the peer is
                        // reachable.
                        trailing = if (connected) {
                            { ConnectedBars() }
                        } else {
                            { RowChevron() }
                        },
                    )
                }
            }
        }

        // Only when there is something to show: an empty "Found nearby" is a
        // standing question about whether discovery is broken.
        if (discovered.isNotEmpty()) {
            SectionLabel(stringResource(R.string.label_discovered_devices))
            Card(modifier = Modifier.fillMaxWidth()) {
                discovered.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    NavigationRow(
                        iconRes = R.drawable.ic_devices,
                        title = device.displayName,
                        subtitle = device.addresses.firstOrNull()
                            ?: stringResource(R.string.device_not_connected),
                        // The row itself does nothing: adding a device is a
                        // deliberate act, so it lives on the button.
                        onClick = {},
                        trailing = {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        SyncEngine.addPeer(device.deviceId, device.addresses)
                                    }.onFailure {
                                        error = it.message ?: it.javaClass.simpleName
                                    }
                                    devices = SyncEngine.devices()
                                    discovered = SyncEngine.discoveredDevices()
                                }
                            }) { Text(stringResource(R.string.action_link_device)) }
                        },
                    )
                }
                EmptyNote(stringResource(R.string.discovered_hint))
            }
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        AddCard(
            text = stringResource(R.string.action_add_device),
            enabled = running != null,
            onClick = onAddDevice,
        )
    }
}

@Composable
private fun ConnectedBars() {
    Icon(
        painter = painterResource(R.drawable.ic_connected),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(18.dp),
    )
}
