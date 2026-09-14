package dev.sidecar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sidecar.R
import dev.sidecar.engine.EntryInfo
import dev.sidecar.engine.SyncEngine
import kotlinx.coroutines.delay

/**
 * Searches the global index, so it finds files that are not on the device.
 * Debounced: each keystroke would otherwise start a full index scan.
 */
@Composable
fun SearchScreen(folderId: String) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EntryInfo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        results = runCatching { SyncEngine.search(query, folderId) }.getOrDefault(emptyList())
        searching = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = { it.path }) { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        entry.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
