package dev.sidecar.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.sidecar.engine.EntryInfo
import dev.sidecar.engine.SyncEngine
import java.io.File
import kotlinx.coroutines.launch

/**
 * Actions for one file.
 *
 * "Open" streams through the engine's localhost server, which pulls blocks from
 * peers as the reader seeks -- the file need never have been downloaded.
 * "Download a copy" is a one-off save that does not change what the folder
 * keeps in sync, unlike pinning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSheet(folderId: String, entry: EntryInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var progress by remember { mutableStateOf<Double?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleLarge)
            Text(
                if (entry.isLocallyPresent) "On this device" else "Streams from your other devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            progress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val url = runCatching { SyncEngine.onDemandUrl(folderId, entry.path) }
                            .getOrNull()
                        if (url.isNullOrEmpty()) {
                            status = "Could not build a stream URL"
                            return@launch
                        }
                        Log.i("FileSheet", "on-demand url for ${entry.path}: $url")
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(url.toUri(), entry.mimeTypeOrDefault())
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            status = "No app can open this type"
                        }
                    }
                }) { Text("Open") }

                OutlinedButton(
                    enabled = progress == null,
                    onClick = {
                        scope.launch {
                            progress = 0.0
                            status = null
                            val target = File(
                                context.getExternalFilesDir("downloads"),
                                entry.name,
                            )
                            status = try {
                                SyncEngine.download(folderId, entry.path, target) { fraction ->
                                    progress = fraction
                                }
                                Toast.makeText(context, "Saved ${entry.name}", Toast.LENGTH_SHORT)
                                    .show()
                                "Saved to ${target.absolutePath}"
                            } catch (t: Throwable) {
                                t.message ?: t.javaClass.simpleName
                            }
                            progress = null
                        }
                    },
                ) { Text("Download a copy") }
            }
        }
    }
}

private fun EntryInfo.mimeTypeOrDefault(): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        else -> "*/*"
    }
}
