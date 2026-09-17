package ch.steigis.dinghy.ui

import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import ch.steigis.dinghy.provider.DinghyDocumentsProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Actions for one file.
 *
 * "Open" hands the file to another app as a content URI backed by
 * DinghyDocumentsProvider, which streams blocks from peers as the reader seeks
 * -- the file need never have been downloaded.
 * "Download a copy" is a one-off save that does not change what the folder
 * keeps in sync, unlike pinning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSheet(folderId: String, entry: EntryInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Opens fully rather than half height: with a preview above them, Open and
    // Download a copy sit below the fold on a short screen, and nothing tells
    // you the sheet can be dragged.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var progress by remember { mutableStateOf<Double?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            // Scrollable for the same reason: a tall preview on a small screen
            // must not be able to push the actions out of reach.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleLarge)
            Text(
                if (entry.isLocallyPresent) "On this device" else "Streams from your other devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Directly under the presence line: that says whether you have the
            // file, this says how current it is, and the two together are what
            // you came to the sheet to find out. Below the preview they would
            // be separated by the whole thing being previewed.
            Text(
                entry.modifiedAt?.let { millis ->
                    val moment = DateFormat
                        .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(millis))
                    if (entry.modifiedBy.isNotBlank()) {
                        stringResource(R.string.file_modified_by, moment, entry.modifiedBy)
                    } else {
                        stringResource(R.string.file_modified, moment)
                    }
                } ?: stringResource(R.string.file_modified_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Above the actions on purpose: the question a preview answers is
            // whether this is the file you meant, which you want settled before
            // choosing Open or Download.
            FilePreview(folderId = folderId, entry = entry)

            progress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    // Deliberately *not* the engine's http: stream URL: viewers
                    // register intent filters for content: and file:, so that
                    // URL resolves to nothing and every file looks unopenable.
                    // The content URI points at our own DocumentsProvider,
                    // which serves the same stream behind a file descriptor.
                    val uri = DinghyDocumentsProvider.documentUri(folderId, entry.path)
                    Log.i("FileSheet", "opening ${entry.path} as $uri")
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, entry.mimeTypeOrDefault())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, entry.name))
                    }.onFailure {
                        Log.w("FileSheet", "no viewer for ${entry.path}", it)
                        status = "No app on this device can open this file"
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
        // MimeTypeMap knows far more than this list; "*/*" is the last resort,
        // and matches any viewer rather than none.
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }
}
