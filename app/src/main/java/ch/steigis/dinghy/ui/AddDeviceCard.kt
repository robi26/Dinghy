package ch.steigis.dinghy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R
import ch.steigis.dinghy.binding.sushitrain.Sushitrain
import ch.steigis.dinghy.engine.SyncEngine
import kotlinx.coroutines.launch

/**
 * Pairing by pasted device ID. A QR scanner is the friendlier path and belongs
 * here too, but the typed form is what makes the app usable headlessly.
 */
@Composable
fun AddDeviceCard(enabled: Boolean, onAdded: (() -> Unit)? = null) {
    var deviceId by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val invalidMessage = stringResource(R.string.error_invalid_id)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.label_add_device),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it; message = null },
                label = { Text(stringResource(R.string.hint_device_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.hint_addresses)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = enabled && deviceId.isNotBlank(),
                onClick = {
                    val id = deviceId.trim()
                    if (!Sushitrain.isValidDeviceID(id)) {
                        message = invalidMessage
                        return@Button
                    }
                    scope.launch {
                        message = try {
                            SyncEngine.addPeer(
                                id,
                                address.trim().takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
                            )
                            deviceId = ""
                            address = ""
                            // Adding is the whole reason for this screen, so
                            // finishing it should return to the list the new
                            // device is now in -- not leave the form open with
                            // a line of text underneath it.
                            onAdded?.invoke()
                            "Added ${Sushitrain.shortDeviceID(id)}"
                        } catch (t: Throwable) {
                            t.message ?: t.javaClass.simpleName
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.action_add))
            }
        }
    }
}
