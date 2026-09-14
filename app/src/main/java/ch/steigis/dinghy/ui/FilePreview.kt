package ch.steigis.dinghy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import coil3.compose.SubcomposeAsyncImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An in-app look at a file, for the types where that is cheap.
 *
 * The point is to answer "is this the one I wanted" without leaving the app or
 * downloading anything. Everything is served by the engine's localhost
 * streaming server, so a file that is not on the device previews by pulling
 * only the blocks the decoder actually reads.
 *
 * Images and text are read straight from that URL. PDF needs a seekable file
 * descriptor instead, so it goes through PdfPreview.
 */
private enum class PreviewKind { Image, Text, Pdf, None }

private val imageExtensions =
    setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

private val textExtensions = setOf(
    "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml",
    "toml", "ini", "conf", "cfg", "properties", "kt", "java", "py", "rb", "rs",
    "go", "c", "h", "cpp", "hpp", "js", "ts", "css", "html", "sh", "gradle",
)

/**
 * Caps, so a preview cannot become an accidental full download. Anything larger
 * still has Open and Download a copy, which is the honest answer for it.
 */
private const val IMAGE_LIMIT_BYTES = 32L * 1024 * 1024
private const val TEXT_LIMIT_BYTES = 512L * 1024

/**
 * PDF is read a page at a time rather than whole, so the cap is about how many
 * range requests a document might cost, not about memory.
 */
private const val PDF_LIMIT_BYTES = 128L * 1024 * 1024

/** Read at most this much of a text file; enough to see what it is. */
private const val TEXT_READ_BYTES = 64 * 1024

private fun EntryInfo.previewKind(): PreviewKind {
    if (isDirectory) return PreviewKind.None
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        extension in imageExtensions -> PreviewKind.Image
        extension in textExtensions -> PreviewKind.Text
        extension == "pdf" -> PreviewKind.Pdf
        else -> PreviewKind.None
    }
}

@Composable
fun FilePreview(folderId: String, entry: EntryInfo, modifier: Modifier = Modifier) {
    val kind = remember(entry.path) { entry.previewKind() }
    if (kind == PreviewKind.None) return

    val limit = when (kind) {
        PreviewKind.Image -> IMAGE_LIMIT_BYTES
        PreviewKind.Text -> TEXT_LIMIT_BYTES
        PreviewKind.Pdf -> PDF_LIMIT_BYTES
        PreviewKind.None -> 0L
    }
    if (entry.size > limit) {
        PreviewNote("Too large to preview here — use Open instead.", modifier)
        return
    }

    if (kind == PreviewKind.Pdf) {
        PdfPreviewBlock(folderId, entry, modifier)
        return
    }

    var url by remember(folderId, entry.path) { mutableStateOf<String?>(null) }
    var failed by remember(folderId, entry.path) { mutableStateOf(false) }

    LaunchedEffect(folderId, entry.path) {
        val resolved = runCatching { SyncEngine.onDemandUrl(folderId, entry.path) }.getOrNull()
        if (resolved.isNullOrEmpty()) failed = true else url = resolved
    }

    when {
        failed -> PreviewNote("No preview — the file could not be reached.", modifier)
        url == null -> PreviewBox(modifier) { CircularProgressIndicator() }
        // Subcompose rather than AsyncImage so the fetch has visible states.
        // Resolving the URL is instant; what takes time is the image itself,
        // which for a file that is not on the device means pulling blocks from
        // a peer -- so a plain AsyncImage leaves an empty box for exactly as
        // long as the interesting part takes.
        kind == PreviewKind.Image -> SubcomposeAsyncImage(
            model = url,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            loading = { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) },
            error = {
                Text(
                    "No preview — this image could not be decoded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            },
            modifier = modifier
                .fillMaxWidth()
                // A minimum as well as a maximum: without it the box is zero
                // height while loading, so the spinner has nowhere to sit and
                // the sheet jumps when the image arrives.
                .heightIn(min = 120.dp, max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )

        else -> TextPreview(url!!, modifier)
    }
}

@Composable
private fun TextPreview(url: String, modifier: Modifier) {
    var text by remember(url) { mutableStateOf<String?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { readAtMost(url, TEXT_READ_BYTES) }.getOrNull()
        }
        if (loaded == null) failed = true else text = loaded
    }

    when {
        failed -> PreviewNote("No preview — the file could not be read.", modifier)
        text == null -> PreviewBox(modifier) { CircularProgressIndicator() }
        else -> Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text!!,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun PreviewBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PreviewNote(message: String, modifier: Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Reads the first [max] bytes and decodes them as UTF-8.
 *
 * A plain read loop rather than InputStream.readNBytes, which is API 33 and this
 * app supports 26. Truncating mid-character is possible and harmless here:
 * decoding replaces the partial sequence rather than throwing, and the tail of a
 * cut-off preview is not load-bearing.
 */
private fun readAtMost(url: String, max: Int): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
    }
    return try {
        connection.inputStream.use { it.readAtMost(max) }.toString(Charsets.UTF_8)
    } finally {
        connection.disconnect()
    }
}

private fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < max) {
        val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (read <= 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
