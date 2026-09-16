package ch.steigis.dinghy.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Exposes synced folders to the system file picker and any app that uses it.
 *
 * The point is that entries here come from the *global* index, so files that
 * have never been downloaded are listed and can be opened: those are served
 * through a proxy file descriptor backed by range requests against the engine's
 * streaming server. Files that are on disk are handed over as ordinary
 * descriptors.
 *
 * This is the Android counterpart of the upstream project's iOS File Provider
 * extension.
 */
class DinghyDocumentsProvider : DocumentsProvider() {

    private lateinit var proxyThread: HandlerThread
    private lateinit var proxyHandler: Handler

    override fun onCreate(): Boolean {
        // Proxy descriptor callbacks must not run on a binder thread: their
        // reads go out over the network.
        proxyThread = HandlerThread("dinghy-proxy-fd").apply { start() }
        proxyHandler = Handler(proxyThread.looper)
        return true
    }

    // ---- roots -------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = context ?: return cursor

        // The system can call this in a cold process, before anything has
        // started the engine.
        if (!SyncEngine.ensureStartedBlocking(context)) return cursor

        runBlocking { SyncEngine.folders() }.forEach { folder ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, folder.id)
                add(Root.COLUMN_DOCUMENT_ID, documentId(folder.id, ""))
                add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                add(Root.COLUMN_SUMMARY, folder.label)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(
                    Root.COLUMN_FLAGS,
                    // Without FLAG_SUPPORTS_CREATE the root is browsable but never
                    // offered as a destination by a "save to..." dialog. A photo
                    // folder is the photo library seen through Syncthing: it has
                    // no disk behind it to write to.
                    Root.FLAG_SUPPORTS_IS_CHILD or
                        Root.FLAG_SUPPORTS_SEARCH or
                        if (folder.isPhotoFolder) 0 else Root.FLAG_SUPPORTS_CREATE,
                )
            }
        }
        return cursor
    }

    // ---- documents ---------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (folderId, path) = parse(documentId)

        if (path.isEmpty()) {
            val folder = runBlocking { SyncEngine.folders() }.firstOrNull { it.id == folderId }
                ?: throw FileNotFoundException("no folder $folderId")
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, folder.label)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
                add(
                    Document.COLUMN_FLAGS,
                    if (folder.isPhotoFolder) 0 else Document.FLAG_DIR_SUPPORTS_CREATE,
                )
            }
            return cursor
        }

        val entry = runBlocking { SyncEngine.entry(folderId, path) }
            ?: throw FileNotFoundException("no entry $documentId")
        cursor.addEntry(folderId, entry, !runBlocking { SyncEngine.isPhotoFolder(folderId) })
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (folderId, path) = parse(parentDocumentId)
        val prefix = if (path.isEmpty()) "" else path.trimEnd('/') + "/"

        val writable = !runBlocking { SyncEngine.isPhotoFolder(folderId) }
        runBlocking { SyncEngine.browse(folderId, prefix) }
            .forEach { cursor.addEntry(folderId, it, writable) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentFolder, parentPath) = parse(parentDocumentId)
        val (childFolder, childPath) = parse(documentId)
        if (parentFolder != childFolder) return false
        if (parentPath.isEmpty()) return childPath.isNotEmpty()
        return childPath.startsWith(parentPath.trimEnd('/') + "/")
    }

    /**
     * Modern pickers search through the Bundle overload; the framework's
     * default implementation of it rejects a null Bundle rather than falling
     * back, so this is the one that has to be implemented.
     */
    override fun querySearchDocuments(
        rootId: String,
        projection: Array<out String>?,
        queryArgs: Bundle,
    ): Cursor {
        val query = queryArgs.getString(DocumentsContract.QUERY_ARG_DISPLAY_NAME).orEmpty()
        return searchCursor(rootId, query, projection)
    }

    /** Retained for pickers that still use the pre-Bundle search path. */
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor = searchCursor(rootId, query, projection)

    private fun searchCursor(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (query.isBlank()) return cursor
        // Searches the global index, so results include files that are not on
        // this device -- the same set the in-app browser shows.
        val writable = !runBlocking { SyncEngine.isPhotoFolder(rootId) }
        runBlocking { SyncEngine.search(query, rootId, SEARCH_LIMIT) }
            .forEach { cursor.addEntry(rootId, it, writable) }
        return cursor
    }

    // ---- opening -----------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (folderId, path) = parse(documentId)

        if (mode != "r") {
            // Writes land on the real file and are then picked up by Syncthing
            // like any other local change, so they propagate to every peer that
            // shares the folder. Only paths that exist on disk can be written:
            // there is nothing to modify for a file that was never downloaded.
            val root = runBlocking { SyncEngine.folderPath(folderId) }
                ?: throw FileNotFoundException("no folder $folderId")
            val file = File(root, path)
            if (!file.exists() && file.parentFile?.isDirectory != true) {
                throw UnsupportedOperationException(
                    "Cannot write $path: it is not downloaded to this device",
                )
            }
            // The index only learns about the write once the folder is
            // scanned. Scanning when the writer closes costs one scan of one
            // path and is what turns a file copied in here into a file the
            // peers receive; the alternative is waiting for the filesystem
            // watcher, or up to an hour for the periodic rescan.
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.parseMode(mode),
                proxyHandler,
            ) { SyncEngine.rescanLater(folderId, path) }
        }
        val entry = runBlocking { SyncEngine.entry(folderId, path) }
            ?: throw FileNotFoundException("no entry $documentId")

        if (entry.isLocallyPresent) {
            val root = runBlocking { SyncEngine.folderPath(folderId) }
                ?: throw FileNotFoundException("no folder $folderId")
            val file = File(root, path)
            if (file.isFile) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        // Not on disk: serve it straight from peers.
        val url = runBlocking { SyncEngine.onDemandUrl(folderId, path) }
        if (url.isNullOrEmpty()) throw FileNotFoundException("no stream for $documentId")

        val storage = context?.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("no storage manager")
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            RemoteFileCallback(url, entry.size),
            proxyHandler,
        )
    }

    // ---- writes ------------------------------------------------------------

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val (folderId, parentPath) = parse(parentDocumentId)
        val parent = localFile(folderId, parentPath)
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw FileNotFoundException("$parentPath is not available on this device")
        }

        val target = File(parent, displayName)
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            target.mkdir()
        } else {
            target.createNewFile()
        }
        if (!created) throw FileNotFoundException("could not create $displayName")

        val childPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"

        // An on-demand folder ignores everything it was not told to keep, and
        // an ignored file is never offered to a peer: unselected, the new file
        // would sit on this device and go nowhere. The rollback matters as much
        // as the selection -- a file that cannot be selected has not been
        // backed up, and failing here says so rather than leaving a copy that
        // looks synced and is not.
        try {
            runBlocking { SyncEngine.selectLocalFile(folderId, childPath) }
        } catch (e: Exception) {
            target.delete()
            throw FileNotFoundException("could not select $displayName: ${e.message}")
        }

        // A file is scanned when its writer closes, in openDocument. A
        // directory has no write to wait for.
        if (mimeType == Document.MIME_TYPE_DIR) SyncEngine.rescanLater(folderId, childPath)

        return documentId(folderId, childPath)
    }

    override fun deleteDocument(documentId: String) {
        val (folderId, path) = parse(documentId)
        val file = localFile(folderId, path)
        if (!file.exists()) {
            throw UnsupportedOperationException(
                "Cannot delete $path: it is not downloaded to this device",
            )
        }
        if (!file.deleteRecursively()) throw FileNotFoundException("could not delete $path")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (folderId, path) = parse(documentId)
        val file = localFile(folderId, path)
        if (!file.exists()) {
            throw UnsupportedOperationException(
                "Cannot rename $path: it is not downloaded to this device",
            )
        }
        val target = File(file.parentFile, displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("could not rename $path")

        val parentPath = path.trimEnd('/').substringBeforeLast('/', "")
        val newPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
        return documentId(folderId, newPath)
    }

    private fun localFile(folderId: String, path: String): File {
        val root = runBlocking { SyncEngine.folderPath(folderId) }
            ?: throw FileNotFoundException("no folder $folderId")
        return if (path.isEmpty()) File(root) else File(root, path)
    }

    // ---- helpers -----------------------------------------------------------

    private fun MatrixCursor.addEntry(folderId: String, entry: EntryInfo, writable: Boolean) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId(folderId, entry.path))
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(
                Document.COLUMN_MIME_TYPE,
                if (entry.isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(entry.name),
            )
            add(Document.COLUMN_SIZE, if (entry.isDirectory) null else entry.size)
            add(Document.COLUMN_LAST_MODIFIED, null)
            // Only advertise what will actually succeed: an entry that is not on
            // disk cannot be renamed, deleted or written.
            add(
                Document.COLUMN_FLAGS,
                if (!entry.isLocallyPresent || !writable) {
                    0
                } else if (entry.isDirectory) {
                    Document.FLAG_DIR_SUPPORTS_CREATE or
                        Document.FLAG_SUPPORTS_DELETE or
                        Document.FLAG_SUPPORTS_RENAME
                } else {
                    Document.FLAG_SUPPORTS_WRITE or
                        Document.FLAG_SUPPORTS_DELETE or
                        Document.FLAG_SUPPORTS_RENAME
                },
            )
        }
    }

    private fun mimeTypeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /**
     * Document IDs are "<folderID>:<path>". Syncthing folder IDs are letters,
     * digits, dash, underscore and dot, so a colon separates them unambiguously.
     */
    private fun documentId(folderId: String, path: String) = "$folderId:$path"

    private fun parse(documentId: String): Pair<String, String> {
        val index = documentId.indexOf(':')
        if (index < 0) throw FileNotFoundException("malformed document id $documentId")
        return documentId.substring(0, index) to documentId.substring(index + 1)
    }

    private companion object {
        const val SEARCH_LIMIT = 200L

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
