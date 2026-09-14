package ch.steigis.dinghy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import ch.steigis.dinghy.provider.RemoteFileCallback
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One open PDF, and the descriptor and thread keeping it readable.
 *
 * PdfRenderer needs a *seekable* file descriptor, which is the whole reason this
 * works for a file that was never downloaded: StorageManager can hand out a
 * descriptor backed by an arbitrary callback, and [RemoteFileCallback] answers
 * reads with range requests against the engine's streaming server. That is the
 * same mechanism DinghyDocumentsProvider uses to let other apps open files
 * through the system picker.
 *
 * Worth knowing about the cost: a PDF's cross-reference table lives at the end
 * of the file, so opening one seeks to EOF before it reads anything else. Range
 * requests handle that, but each miss is a round trip to a peer -- quick on a
 * local network, noticeably slower over a relay.
 */
private class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val thread: HandlerThread?,
) {
    val pageCount: Int get() = renderer.pageCount

    /** PdfRenderer allows one open page at a time and is not thread safe. */
    private val lock = Mutex()
    private var closed = false

    /**
     * Teardown runs here rather than on whatever thread disposed the UI, so it
     * can take [lock] and wait for a render instead of closing underneath one.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Null once closed, which a render racing disposal can observe. */
    suspend fun render(index: Int, widthPx: Int, maxHeightPx: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                // Fit the page inside both bounds. Scaling by width alone means
                // a tall, narrow page -- a receipt, a plotted strip -- derives a
                // height of tens of thousands of pixels and the allocation below
                // dies of an OutOfMemoryError long before any layout constraint
                // would have clipped it.
                val scale = min(
                    widthPx.toFloat() / page.width,
                    maxHeightPx.toFloat() / page.height,
                )
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PDF pages are transparent where nothing is drawn, which on a
                // dark background renders as unreadable dark-on-dark text.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    /**
     * Safe to call while a render is in flight. PdfRenderer's work is native and
     * uninterruptible, so cancelling the caller does not stop it -- closing the
     * renderer or its descriptor underneath one is a native crash, not an
     * exception. Closing therefore queues behind the same lock renders take.
     */
    fun close() {
        scope.launch {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                thread?.quitSafely()
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    companion object {
        suspend fun open(
            context: Context,
            folderId: String,
            entry: EntryInfo,
        ): PdfDocument = withContext(Dispatchers.IO) {
            // Already on the device: read it directly and skip the server
            // entirely. Same fast path the DocumentsProvider takes.
            val root = SyncEngine.folderPath(folderId)
            val local = if (entry.isLocallyPresent && root != null) {
                File(root, entry.path).takeIf { it.isFile }
            } else {
                null
            }

            if (local != null) {
                val descriptor =
                    ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                return@withContext build(descriptor, null)
            }

            val url = SyncEngine.onDemandUrl(folderId, entry.path)
            require(!url.isNullOrEmpty()) { "no stream for ${entry.path}" }
            val storage = context.getSystemService(StorageManager::class.java)
            requireNotNull(storage) { "no storage manager" }

            // openProxyFileDescriptor refuses the main looper: reads are served
            // on this thread and would deadlock the UI.
            val thread = HandlerThread("pdf-preview-${entry.path.hashCode()}").apply { start() }
            val descriptor = try {
                storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    RemoteFileCallback(url, entry.size),
                    Handler(thread.looper),
                )
            } catch (t: Throwable) {
                thread.quitSafely()
                throw t
            }
            build(descriptor, thread)
        }

        /** Closes what it was given if PdfRenderer rejects the file. */
        private fun build(
            descriptor: ParcelFileDescriptor,
            thread: HandlerThread?,
        ): PdfDocument = try {
            PdfDocument(descriptor, PdfRenderer(descriptor), thread)
        } catch (t: Throwable) {
            runCatching { descriptor.close() }
            thread?.quitSafely()
            throw t
        }
    }
}

@Composable
internal fun PdfPreview(folderId: String, entry: EntryInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var document by remember(folderId, entry.path) { mutableStateOf<PdfDocument?>(null) }
    var failed by remember(folderId, entry.path) { mutableStateOf(false) }
    var pageIndex by remember(folderId, entry.path) { mutableIntStateOf(0) }
    var bitmap by remember(folderId, entry.path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(folderId, entry.path) {
        document = runCatching { PdfDocument.open(context, folderId, entry) }
            .onFailure { failed = true }
            .getOrNull()
    }

    // The descriptor, the render thread and the last bitmap all outlive
    // composition unless they are let go explicitly.
    DisposableEffect(folderId, entry.path) {
        onDispose {
            document?.close()
            document = null
            bitmap = null
        }
    }

    if (failed) {
        Text(
            "No preview — this PDF could not be opened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        // Render at the width it will actually occupy: rendering larger wastes
        // memory on a page that is then scaled down anyway.
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, 2048)
        val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceIn(1, 4096)
        val open = document

        LaunchedEffect(open, pageIndex, widthPx, heightPx) {
            val doc = open ?: return@LaunchedEffect
            val rendered = runCatching { doc.render(pageIndex, widthPx, heightPx) }
                .onFailure { failed = true }
                .getOrNull()
            // Null means the document closed while this was queued behind it,
            // which is disposal rather than a failure.
            if (rendered != null) bitmap = rendered
        }

        val rendered = bitmap
        if (rendered == null) {
            // Same spinner as the image and text previews. Opening a PDF seeks
            // to its cross-reference table at EOF first, so this is visible for
            // a moment even before the first page is decoded.
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = rendered.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val pages = document?.pageCount ?: 0
    if (pages > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { if (pageIndex > 0) pageIndex-- },
                enabled = pageIndex > 0,
            ) { Text("Previous") }

            Text(
                "Page ${pageIndex + 1} of $pages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = { if (pageIndex < pages - 1) pageIndex++ },
                enabled = pageIndex < pages - 1,
            ) { Text("Next") }
        }
    }
}

/** Groups the preview and its pager, so callers place one thing. */
@Composable
internal fun PdfPreviewBlock(folderId: String, entry: EntryInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PdfPreview(folderId, entry)
    }
}
