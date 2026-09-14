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
import ch.steigis.dinghy.engine.DeviceInfo
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.SyncEngine

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

    // Keyed on the engine state so the list re-reads when a peer connects or
    // drops, which is what changes the indicator.
    LaunchedEffect(state) {
        devices = if (running != null) SyncEngine.devices() else emptyList()
    }

    TabColumn(contentPadding) {
        SetupWarnings()

        if (running != null) {
            SectionLabel(stringResource(R.string.label_this_device))
            Card(modifier = Modifier.fillMaxWidth()) {
                DeviceRow(
                    title = stringResource(R.string.label_this_device),
                    subtitle = running.deviceId.substringBefore('-'),
                    connected = false,
                    onClick = onOpenThisDevice,
                )
            }
        }

        SectionLabel(stringResource(R.string.label_associated_devices))
        Card(modifier = Modifier.fillMaxWidth()) {
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.devices_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    DeviceRow(
                        title = device.displayName,
                        subtitle = when {
                            device.isPaused -> stringResource(R.string.device_paused)
                            device.isConnected -> stringResource(R.string.device_connected)
                            else -> stringResource(R.string.device_not_connected)
                        },
                        connected = device.isConnected && !device.isPaused,
                        onClick = { onOpenDevice(device.deviceId, device.displayName) },
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = running != null, onClick = onAddDevice)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.action_add_device),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun DeviceRow(
    title: String,
    subtitle: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_devices),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Bars for a live connection, a chevron otherwise. Both say "there is
        // more behind this row"; only one of them says the peer is reachable.
        Icon(
            painter = painterResource(
                if (connected) R.drawable.ic_connected else R.drawable.ic_chevron_right,
            ),
            contentDescription = null,
            tint = if (connected) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(if (connected) 18.dp else 20.dp),
        )
    }
}
