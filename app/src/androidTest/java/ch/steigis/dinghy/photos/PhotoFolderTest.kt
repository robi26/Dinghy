package ch.steigis.dinghy.photos

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The photo folder end to end: a photo in the library becomes a file in the
 * index, without anything being copied.
 *
 * Everything below the Kotlin tree implementation is Go calling back into
 * Java, so this has to run on a device -- the registration, the folder marker,
 * the scan and the layout cannot be checked any other way.
 */
@RunWith(AndroidJUnit4::class)
class PhotoFolderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()

    // Unique per run: unlinking a folder leaves its index in the database, so
    // a fixed id and name would let a previous run's entries pass this test
    // without anything having been scanned.
    private val folderId = "dinghy-photo-test-${System.currentTimeMillis()}"
    private val name = "dinghy-test-photo-${System.currentTimeMillis()}.jpg"

    @Before
    fun setUp() {
        // RELATIVE_PATH, and with it inserting into the library without
        // holding a storage permission.
        assumeTrue("needs Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        // The folder is empty without this, and the test would do nothing but
        // wait for a scan that cannot read anything. Granting it here rather
        // than relying on the device having been prepared by hand: a test that
        // only passes on the machine it was written on is not a test.
        grant(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION)
        assumeTrue("no access to the photo library", hasFullLibraryAccess(context))

        assumeTrue("engine did not start", SyncEngine.ensureStartedBlocking(context))
    }

    private fun grant(permission: String) {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, permission)
        }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { SyncEngine.removeFolder(folderId, false) } }
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    @Test
    fun aPhotoInTheLibraryIsAFileInTheFolder() {
        val photo = insertPhoto(name)

        runBlocking { SyncEngine.addPhotoFolder(folderId) }

        // The folder is laid out by the month the photo was taken, in UTC.
        // Asked of MediaStore rather than assumed: it decides a photo's date
        // for itself, and ignores the one an insert asks for.
        val (year, month) = monthOf(photo.uri)

        val yearEntry = await("the $year directory") {
            SyncEngine.browse(folderId, "").firstOrNull { it.name == year }
        }
        assertTrue("$year should be a directory", yearEntry.isDirectory)

        val monthEntry = await("the $year/$month directory") {
            SyncEngine.browse(folderId, "$year/").firstOrNull { it.name == month }
        }
        assertTrue("$month should be a directory", monthEntry.isDirectory)

        val entry = await("the photo") {
            SyncEngine.browse(folderId, "$year/$month/").firstOrNull { it.name == name }
        }

        // The size in the index is what a peer will be told to expect, so it
        // has to be the size of the bytes the library actually hands over.
        assertEquals("indexed size differs from the photo", photo.bytes.size.toLong(), entry.size)

        // Nothing was copied: the folder has no directory of its own on disk.
        val path = runBlocking { SyncEngine.folderPath(folderId) }
        assertEquals("a photo folder keeps settings in its path, not a path", CONFIG, path)
    }

    /**
     * A photo added after the folder is already running still reaches it.
     *
     * The library is re-read in the background, so the scan that notices a
     * change is answered from the tree as it stood and only *starts* the
     * rebuild. Nothing would ever see the new photo if the rebuild did not ask
     * for a scan of its own once it landed, and that hand-off is invisible
     * from the outside: the folder would simply stop gaining photos, which is
     * a backup quietly missing everything taken since it was set up.
     */
    @Test
    fun aPhotoAddedAfterTheFirstScanStillAppears() {
        val first = insertPhoto(name)
        runBlocking { SyncEngine.addPhotoFolder(folderId) }

        val (year, month) = monthOf(first.uri)
        await("the first photo") {
            SyncEngine.browse(folderId, "$year/$month/").firstOrNull { it.name == name }
        }

        // Past the rescan addPhotoFolder schedules a couple of seconds after
        // creating the folder. Without this wait that scan picks the next
        // photo up, the test passes in two seconds, and the library-change
        // path it is supposed to cover is never exercised at all.
        runBlocking { delay(SETTLE_MILLIS) }

        val laterName = "dinghy-test-later-${System.currentTimeMillis()}.jpg"
        val later = insertPhoto(laterName)
        val (laterYear, laterMonth) = monthOf(later.uri)

        // Generous, because how long this takes depends on when the rebuild
        // lands: if it finishes before the scan reaches the month directory
        // that scan already sees the photo, and otherwise it takes the second
        // scan, the one the rebuild asks for -- another debounce later.
        val entry = await("the later photo", REBUILD_TIMEOUT_MS) {
            SyncEngine.browse(folderId, "$laterYear/$laterMonth/")
                .firstOrNull { it.name == laterName }
        }
        assertEquals(
            "indexed size differs from the photo",
            later.bytes.size.toLong(),
            entry.size,
        )
    }

    /** Polls until the engine has scanned; a scan is asynchronous. */
    private fun await(
        what: String,
        timeoutMillis: Long = SCAN_TIMEOUT_MS,
        read: suspend () -> EntryInfo?,
    ): EntryInfo {
        val found = runBlocking {
            withTimeoutOrNull(timeoutMillis) {
                var entry = read()
                while (entry == null) {
                    delay(POLL_MILLIS)
                    entry = read()
                }
                entry
            }
        }
        return requireNotNull(found) { "$what never appeared in the folder" }
    }

    /**
     * Where the folder should put [uri], by the same rule the tree uses.
     *
     * All three columns, in the same order the tree falls back through them.
     * DATE_TAKEN and DATE_MODIFIED are both still zero for a moment after an
     * insert, and reading only those put a freshly added photo at epoch zero
     * -- so this looked in 1970/01 while the folder had filed it under the
     * real month, and the photo appeared to have gone missing.
     */
    private fun monthOf(uri: Uri): Pair<String, String> {
        val projection = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val seconds = requireNotNull(resolver.query(uri, projection, null, null, null)).use {
            require(it.moveToFirst()) { "the photo is not in the library" }
            val taken = it.getLong(0)
            val modified = it.getLong(1)
            when {
                taken > 0 -> taken / 1000
                modified > 0 -> modified
                else -> it.getLong(2)
            }
        }
        val date = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC)
        return "%04d".format(date.year) to "%02d".format(date.monthValue)
    }

    private class Photo(val uri: Uri, val bytes: ByteArray)

    /** A real JPEG, so that the library stores and serves it like any photo. */
    private fun insertPhoto(name: String): Photo {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val bytes = ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/DinghyTest")
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "could not add a photo to the library" }
        inserted += uri

        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        return Photo(uri, bytes)
    }

    private companion object {
        const val CONFIG = """{"version":1}"""
        const val SCAN_TIMEOUT_MS = 90_000L

        /** Long enough for the folder's own post-creation scan to have run. */
        const val SETTLE_MILLIS = 8_000L

        /** Two rescan debounces plus the rebuild between them. */
        const val REBUILD_TIMEOUT_MS = 150_000L
        const val POLL_MILLIS = 1_000L
    }
}
