package ch.steigis.dinghy.provider

import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the provider against whatever the device is actually syncing.
 *
 * This is an integration test by intent: the interesting behaviour is a real
 * read of a file whose bytes live on another machine, which nothing local can
 * fake. Tests skip rather than fail when no suitable folder is configured.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun engineRunning() {
        assumeTrue("engine did not start", SyncEngine.ensureStartedBlocking(context))

        // Running is not the same as connected. Reading a file that is not on
        // this device needs a peer to pull blocks from, and the test runner
        // restarts the process, so the connection has to be waited for.
        val connected = runBlocking {
            withTimeoutOrNull(PEER_TIMEOUT_MS) {
                SyncEngine.state.first { it is EngineState.Running && it.connectedPeers > 0 }
            }
        }
        assumeTrue("no peer connected", connected != null)
    }

    @Test
    fun listsRootsForEveryFolder() {
        val folders = runBlocking { SyncEngine.folders() }
        assumeTrue("no folders configured", folders.isNotEmpty())

        val uri = DocumentsContract.buildRootsUri(AUTHORITY)
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            requireNotNull(cursor)
            assertEquals(folders.size, cursor.count)
        }
    }

    /**
     * The point of the whole app: a file that is not on this device is readable
     * through the system's file APIs, and the bytes are correct.
     */
    @Test
    fun readsAFileThatIsNotOnThisDevice() {
        val folders = runBlocking { SyncEngine.folders() }
        val candidate = folders.firstNotNullOfOrNull { folder ->
            runBlocking { SyncEngine.browse(folder.id, "") }
                .firstOrNull { it.isRemoteOnly && it.size > READ_AT + READ_LENGTH }
                ?.let { folder.id to it }
        }
        assumeTrue("no remote-only file large enough to test", candidate != null)

        val (folderId, entry) = candidate!!
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, "$folderId:${entry.path}")

        val fromProvider = context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream)
            stream.skip(READ_AT)
            val buffer = ByteArray(READ_LENGTH)
            var read = 0
            while (read < READ_LENGTH) {
                val n = stream.read(buffer, read, READ_LENGTH - read)
                if (n <= 0) break
                read += n
            }
            assertEquals("short read from proxy descriptor", READ_LENGTH, read)
            buffer
        }

        // Compare against a direct range request to the streaming server. That
        // path shares no code with the proxy descriptor under test, so matching
        // bytes mean the descriptor really served the right region.
        val url = runBlocking { SyncEngine.onDemandUrl(folderId, entry.path) }
        val expected = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            setRequestProperty("Range", "bytes=$READ_AT-${READ_AT + READ_LENGTH - 1}")
            try {
                inputStream.use { it.readBytes() }
            } finally {
                disconnect()
            }
        }
        assertTrue("bytes differ from the engine's own read", expected.contentEquals(fromProvider))

        assertTrue(
            "file should still not be downloaded",
            runBlocking { SyncEngine.entry(folderId, entry.path) }?.isLocallyPresent == false,
        )
    }

    /**
     * Search has to reach the global index, not just what is on disk --
     * otherwise the picker would find nothing on a device that syncs
     * on demand.
     */
    @Test
    fun searchFindsFilesThatAreNotDownloaded() {
        val folders = runBlocking { SyncEngine.folders() }
        assumeTrue("no folders configured", folders.isNotEmpty())

        val sample = folders.firstNotNullOfOrNull { folder ->
            runBlocking { SyncEngine.browse(folder.id, "") }
                .firstOrNull { it.isRemoteOnly }
                ?.let { folder.id to it.name }
        }
        assumeTrue("no remote-only file to search for", sample != null)

        val (folderId, name) = sample!!
        val term = name.substringBeforeLast('.').take(6)
        val uri = DocumentsContract.buildSearchDocumentsUri(AUTHORITY, folderId, term)

        val args = Bundle().apply { putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, term) }
        context.contentResolver.query(uri, null, args, null).use { cursor ->
            requireNotNull(cursor)
            assertTrue("search returned nothing for '$term'", cursor.count > 0)
            val nameIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertTrue(
                "no result contained '$term': $names",
                names.any { it.contains(term, ignoreCase = true) },
            )
        }
    }

    /**
     * The other direction: a file written through the picker has to leave the
     * device. In an on-demand folder everything is ignored by default, so the
     * file only reaches a peer if the provider selected it and had it scanned.
     *
     * Writes into whatever folder the device syncs, and deletes what it wrote:
     * a peer will see the file appear and disappear.
     */
    @Test
    fun aCreatedFileIsSelectedAndIndexed() {
        val folder = runBlocking { SyncEngine.folders() }.firstOrNull { it.isSelective }
        assumeTrue("no on-demand folder configured", folder != null)

        val parent = DocumentsContract.buildDocumentUri(AUTHORITY, "${folder!!.id}:")
        val name = "dinghy-upload-test-${System.currentTimeMillis()}.txt"
        val uri = DocumentsContract
            .createDocument(context.contentResolver, parent, "text/plain", name)
        requireNotNull(uri) { "createDocument returned nothing" }

        try {
            val content = ByteArray(WRITE_LENGTH) { it.toByte() }
            context.contentResolver.openOutputStream(uri, "w").use { stream ->
                requireNotNull(stream).write(content)
            }

            // The scan the close triggers is asynchronous inside the engine.
            val indexed = runBlocking {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    var entry = SyncEngine.entry(folder.id, name)
                    while (entry == null || entry.size != content.size.toLong()) {
                        delay(POLL_MILLIS)
                        entry = SyncEngine.entry(folder.id, name)
                    }
                    entry
                }
            }

            requireNotNull(indexed) { "$name never made it into the index" }
            assertTrue("created file is ignored, so no peer will ever get it", indexed.isSelected)
            assertTrue("created file is not on disk", indexed.isLocallyPresent)
        } finally {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    private companion object {
        const val AUTHORITY = "ch.steigis.dinghy.documents"
        const val READ_AT = 5_000_000L
        const val READ_LENGTH = 4096
        const val PEER_TIMEOUT_MS = 60_000L
        const val WRITE_LENGTH = 4096
        const val SCAN_TIMEOUT_MS = 30_000L
        const val POLL_MILLIS = 500L
    }
}
