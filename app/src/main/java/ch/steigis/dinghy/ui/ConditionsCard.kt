package ch.steigis.dinghy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R
import ch.steigis.dinghy.settings.Settings

/**
 * When syncing is allowed to run. Changing a toggle takes effect immediately
 * because the service re-evaluates conditions on every settings-driven change.
 */
@Composable
fun ConditionsCard() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var onMetered by remember { mutableStateOf(settings.syncOnMetered) }
    var onlyCharging by remember { mutableStateOf(settings.syncOnlyWhenCharging) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ToggleRow(
                title = stringResource(R.string.settings_metered),
                summary = stringResource(R.string.settings_metered_summary),
                checked = onMetered,
                onChange = {
                    onMetered = it
                    settings.syncOnMetered = it
                },
            )
            ToggleRow(
                title = stringResource(R.string.settings_charging),
                summary = stringResource(R.string.settings_charging_summary),
                checked = onlyCharging,
                onChange = {
                    onlyCharging = it
                    settings.syncOnlyWhenCharging = it
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
