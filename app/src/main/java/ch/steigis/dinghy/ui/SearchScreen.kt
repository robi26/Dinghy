package ch.steigis.dinghy.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import kotlinx.coroutines.delay

/**
 * Searches the global index, so it finds files that are not on the device.
 * Debounced: each keystroke would otherwise start a full index scan.
 *
 * An empty [folderId] searches every folder, which is what the Search tab does;
 * the app bar action inside a folder passes that folder's id to narrow it.
 *
 * A result is only useful if you can act on it: files open the same sheet the
 * browser uses, and directories hand back to [onOpenDirectory] so the caller can
 * push the browser at that folder and path.
 */
@Composable
fun SearchScreen(
    folderId: String = "",
    onOpenDirectory: ((folderId: String, folderLabel: String, prefix: String) -> Unit)? = null,
) {
    // Saveable, not just remembered: opening a directory result replaces this
    // screen with the browser, and without this the query is gone when you come
    // back. The results are re-fetched rather than saved -- the LaunchedEffect
    // re-runs on the restored query.
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EntryInfo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<EntryInfo?>(null) }

    sheetFor?.let { selected ->
        FileSheet(
            // A result from a search across folders carries its own folder; one
            // from a search inside a folder falls back to the screen's.
            folderId = selected.folderId.ifEmpty { folderId },
            entry = selected,
            onDismiss = { sheetFor = null },
        )
    }

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
            // Keyed by folder and path together: across folders the path alone
            // is not unique, and duplicate keys crash a LazyColumn.
            items(results, key = { it.folderId + "/" + it.path }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) {
                                onOpenDirectory?.invoke(
                                    entry.folderId.ifEmpty { folderId },
                                    entry.folderLabel.ifEmpty { entry.folderId },
                                    entry.path.trimEnd('/') + "/",
                                )
                            } else {
                                sheetFor = entry
                            }
                        }
                        .padding(16.dp),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // Only worth naming the folder when the search spanned
                        // more than one.
                        if (folderId.isEmpty() && entry.folderLabel.isNotEmpty()) {
                            "${entry.folderLabel} · ${entry.path}"
                        } else {
                            entry.path
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
