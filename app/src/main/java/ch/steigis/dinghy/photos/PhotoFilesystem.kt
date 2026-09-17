package ch.steigis.dinghy.photos

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import ch.steigis.dinghy.binding.sushitrain.CustomFileEntry
import ch.steigis.dinghy.binding.sushitrain.CustomFilesystemType
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The filesystem type a photo folder is configured with. */
const val PHOTO_FS_TYPE = "dinghy.photos.v1"

/**
 * The device's photo library, presented to Syncthing as a folder of files.
 *
 * Nothing is ever copied: the folder's contents are made up on demand from
 * MediaStore, and a photo's bytes are read from the library only when a peer
 * actually asks for that file. The engine sees an ordinary send-only folder.
 *
 * This is the Android counterpart of the upstream project's PhotoFS, and works
 * the same way -- see `external/sushitrain/Docs/photo-fs.md`. The Go side
 * implements Syncthing's filesystem interface on top of the small tree
 * interface below ([CustomFileEntry]), which this file supplies.
 *
 * Two properties are load-bearing and easy to break:
 *
 * - **Never report an empty library.** Syncthing reads "no files" as "every
 *   file was deleted" and would propagate that to the peers holding the
 *   backup. Anything that stops the library being read -- a revoked
 *   permission, a query that fails -- throws, which the engine reports as a
 *   folder error and which changes no index.
 * - **[CustomFileEntry.bytes] must agree with [CustomFileEntry.data].** The
 *   size is what Syncthing writes into the index; the bytes are what the peer
 *   receives. Both come from the same URI for that reason. When that URI
 *   serves the original, MediaStore's recorded size is already the answer;
 *   when it serves a redacted copy the length has to be measured, because
 *   stripping the GPS tags makes the copy shorter than the file on disk.
 */
class PhotoFilesystem(context: Context) : CustomFilesystemType {

    private val appContext = context.applicationContext

    // A rebuild lands asynchronously now, so the folder has to be told to look
    // again: the scan that triggered it was answered from the previous tree.
    private val library = PhotoLibrary(appContext).apply {
        onRebuilt = { this@PhotoFilesystem.onLibraryChanged?.invoke() }
    }

    /** Roots are cached per URI: the engine asks for one per folder start. */
    private val roots = HashMap<String, CustomFileEntry>()

    /** Called when the photo library changes, so folders can be rescanned. */
    @Volatile
    var onLibraryChanged: (() -> Unit)? = null

    private val observerThread by lazy {
        HandlerThread("dinghy-photo-observer").apply { start() }
    }

    private val observer by lazy {
        object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                library.invalidate()
                onLibraryChanged?.invoke()
            }
        }
    }

    private var watching = false

    /**
     * [uri] is the folder's configured path, which for a virtual filesystem is
     * just a string the engine hands back: [PhotoFolderConfig] keeps its
     * settings in it as JSON. An unreadable one falls back to the defaults
     * rather than leaving the folder broken.
     */
    @Synchronized
    override fun root(uri: String): CustomFileEntry {
        roots[uri]?.let { return it }

        val config = PhotoFolderConfig.parse(uri)
        val root = RootEntry(library, config)
        roots[uri] = root

        if (!watching) {
            watching = true
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
        }
        return root
    }
}

/**
 * How the library is laid out as directories.
 *
 * Kept in the folder's path as JSON so the layout can gain options without a
 * migration. Unknown fields and malformed JSON fall back to the defaults --
 * this is parsed on the engine thread at folder start, where throwing would
 * leave the folder unusable.
 */
internal data class PhotoFolderConfig(val version: Int = 1) {

    companion object {
        /** What a newly created photo folder stores in its path. */
        const val DEFAULT_JSON = """{"version":1}"""

        fun parse(uri: String): PhotoFolderConfig = try {
            val version = org.json.JSONObject(uri).optInt("version", 1)
            PhotoFolderConfig(version)
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "photo folder config is not readable, using defaults: $uri", e)
            PhotoFolderConfig()
        }
    }
}

/**
 * The folder root: Syncthing's two internal entries, plus a directory per year.
 *
 * The children are rebuilt from a snapshot of the library rather than mutated,
 * so a rebuild that lands in the middle of a scan cannot make an existing
 * directory contradict itself.
 */
private class RootEntry(
    private val library: PhotoLibrary,
    @Suppress("unused") private val config: PhotoFolderConfig,
) : CustomFileEntry {

    override fun name() = ""

    override fun isDir() = true

    override fun childCount(): Long = children().size.toLong()

    override fun childAt(index: Long): CustomFileEntry = children().at(index)

    override fun data(): ByteArray = throw IOException("the folder root is not a file")

    override fun bytes(): Long = 0

    // Fixed rather than "now": a root whose timestamp moved on every rebuild
    // would be a folder change to send on every scan.
    override fun modifiedTime(): Long = 0

    @Volatile
    private var joined: Pair<List<CustomFileEntry>, List<CustomFileEntry>>? = null

    /**
     * The markers and the years as one list, rebuilt only when the library is.
     *
     * Go resolves a path by asking for the count and then for each child in
     * turn, so this is called a handful of times per lookup and a lookup
     * happens per photo per scan. Concatenating afresh each time made that a
     * few hundred thousand throwaway lists over a library of any size.
     *
     * Compared by identity, not equality: [PhotoLibrary.years] hands back the
     * same list until it rebuilds, and a rebuild is exactly when this must be
     * redone.
     */
    private fun children(): List<CustomFileEntry> {
        val years = library.years()
        joined?.let { (source, result) -> if (source === years) return result }
        val result = MARKERS + years
        joined = years to result
        return result
    }

    private companion object {
        /**
         * Syncthing refuses to sync a folder without its marker, and reads
         * .stignore before scanning. Both are skipped when walking (they are
         * internal names), so neither is ever sent to a peer.
         */
        val MARKERS = listOf(
            DirEntry(
                ".stfolder",
                listOf(TextEntry(".photofs-marker", "# Dinghy photo folder. Empty on purpose.\n")),
            ),
            TextEntry(".stignore", "# Dinghy photo folder. Empty on purpose.\n"),
        )
    }
}

/** A directory whose children are fixed. */
private class DirEntry(
    private val entryName: String,
    private val children: List<CustomFileEntry>,
    private val modified: Long = 0,
) : CustomFileEntry {
    override fun name() = entryName
    override fun isDir() = true
    override fun childCount(): Long = children.size.toLong()
    override fun childAt(index: Long): CustomFileEntry = children.at(index)
    override fun data(): ByteArray = throw IOException("$entryName is a directory")
    override fun bytes(): Long = 0
    override fun modifiedTime(): Long = modified
}

/** A small file the folder needs to have, held in memory. */
private class TextEntry(
    private val entryName: String,
    text: String,
) : CustomFileEntry {
    private val content = text.toByteArray()
    override fun name() = entryName
    override fun isDir() = false
    override fun childCount(): Long = 0
    override fun childAt(index: Long): CustomFileEntry =
        throw IOException("$entryName is not a directory")

    override fun data(): ByteArray = content
    override fun bytes(): Long = content.size.toLong()
    override fun modifiedTime(): Long = 0
}

/**
 * One photo, read from the library when it is asked for.
 *
 * [sizeHint] is MediaStore's recorded size. Whether that is the size a peer
 * will receive depends on [servesOriginal]: without ACCESS_MEDIA_LOCATION the
 * system hands out a copy with the GPS tags removed, which is shorter than the
 * file on disk, and only then does the length have to be measured.
 */
private class PhotoEntry(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val entryName: String,
    private val modified: Long,
    private val sizeHint: Long,
    /**
     * The URI was built with setRequireOriginal and we hold
     * ACCESS_MEDIA_LOCATION, so reading it yields the file as it is on disk --
     * nothing is redacted, and MediaStore's recorded size is what a peer gets.
     */
    private val servesOriginal: Boolean,
) : CustomFileEntry {

    @Volatile
    private var measured = -1L

    override fun name() = entryName

    override fun isDir() = false

    override fun childCount(): Long = 0

    override fun childAt(index: Long): CustomFileEntry =
        throw IOException("$entryName is not a directory")

    override fun modifiedTime(): Long = modified

    /**
     * Syncthing asks for this once per file on every scan, so what it costs
     * decides what a scan costs.
     *
     * Measuring means opening a descriptor, and on Android 11+ every one of
     * those is a FUSE round trip into MediaProvider. Over a library of
     * thousands of photos that is thousands of round trips per scan, repeated
     * on every rebuild -- enough to wedge the process if a single one of them
     * fails to come back, which is what a stuck FUSE request does.
     *
     * So when the bytes are served unredacted there is nothing to measure:
     * MediaStore's size *is* the size of what a peer receives, and it was
     * already read for free out of the same cursor row as [modified]. The
     * redacted case still measures, because there the two genuinely differ.
     */
    override fun bytes(): Long {
        if (servesOriginal && sizeHint > 0) return sizeHint

        measured.takeIf { it >= 0 }?.let { return it }
        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        return (length?.takeIf { it >= 0 } ?: sizeHint).also { measured = it }
    }

    override fun data(): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("$entryName is no longer in the photo library")
}

/**
 * Enumerates MediaStore and holds the resulting tree.
 *
 * Rebuilt when the library reports a change, and in any case once an hour, so
 * a missed notification cannot leave the folder stale forever.
 */
private class PhotoLibrary(private val context: Context) {

    private val lock = Any()

    /**
     * The built tree, published as one immutable value.
     *
     * A reader therefore sees either the whole previous library or the whole
     * new one, and needs no lock to do it. That matters because [build] runs
     * holding [lock] and enumerates the entire library: taking the lock to
     * read would make every directory lookup -- and there is one per photo per
     * scan -- wait for a full enumeration whenever one happened to be running.
     */
    private class Snapshot(
        val years: List<CustomFileEntry>,
        val builtAt: Long,
        val builtFor: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** Rebuilds run here, off whatever thread happened to notice. */
    private val rebuilds = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dinghy-photo-library").apply { isDaemon = true }
    }

    /** Guarded by [lock]: whether a rebuild is already in flight. */
    private var rebuilding = false

    /** Called when a rebuild lands, so the folder can scan against it. */
    @Volatile
    var onRebuilt: (() -> Unit)? = null

    /**
     * Counts library changes, rather than flagging them.
     *
     * A flag cleared after the query would swallow every notification that
     * arrived while the query was running -- exactly when a photo is being
     * added -- and the new photo would then stay invisible until the hourly
     * expiry. The count the build started from is recorded instead, so a
     * change that lands mid-build leaves the result stale, as it is.
     */
    private val changes = AtomicLong()

    fun invalidate() {
        changes.incrementAndGet()
    }

    /** Whether [candidate] is still the library as it stands. */
    private fun isCurrent(candidate: Snapshot?): Boolean {
        val current = candidate ?: return false
        if (current.builtFor != changes.get()) return false
        return SystemClock.elapsedRealtime() - current.builtAt < MAX_AGE_MILLIS
    }

    /**
     * The library as it was last read, rebuilding behind the caller if that is
     * no longer current.
     *
     * Deliberately answers from a stale tree rather than waiting: a rebuild
     * enumerates every photo, and the callers here are a running scan, the
     * streaming server answering a peer, and the browser -- none of which
     * should stop for it. Staleness is bounded by the rebuild itself, which
     * asks for a rescan as soon as it lands, so a stale answer is corrected
     * rather than kept. The cost is that a change takes one more debounce
     * interval to appear, which is the right trade against a folder that stops
     * responding whenever the library is re-read.
     */
    fun years(): List<CustomFileEntry> {
        snapshot?.let { current ->
            if (!isCurrent(current)) rebuildLater()
            return current.years
        }

        // Nothing has been read yet, so this one caller does have to wait:
        // reporting an empty library would be taken for "every photo was
        // deleted" and would propagate to the peers holding the backup.
        return synchronized(lock) {
            snapshot?.let { return@synchronized it.years }

            val startedAt = changes.get()
            val built = build()
            snapshot = Snapshot(built, SystemClock.elapsedRealtime(), startedAt)
            built
        }
    }

    /**
     * Enumerates the library on [rebuilds], at most one at a time.
     *
     * Coalesced rather than queued: while one is in flight, further callers
     * keep the stale tree and add nothing, so a burst of changes costs one
     * enumeration instead of one each.
     */
    private fun rebuildLater() {
        synchronized(lock) {
            if (rebuilding) return
            rebuilding = true
        }

        rebuilds.execute {
            val startedAt = changes.get()
            val built = runCatching { build() }
            synchronized(lock) { rebuilding = false }

            built.onSuccess { years ->
                snapshot = Snapshot(years, SystemClock.elapsedRealtime(), startedAt)
                onRebuilt?.invoke()
            }.onFailure { t ->
                Log.w(TAG, "could not rebuild the photo library", t)
                // Back to the blocking path, whose build() throws if the
                // library really cannot be read any more -- a folder error
                // changes no index. Keeping the old tree instead would hide a
                // revoked permission behind a folder that still looks healthy.
                snapshot = null
            }
        }
    }

    /**
     * Throws rather than returning nothing when the library cannot be read:
     * an empty folder would be indexed as "everything was deleted" and would
     * take the peers' copies with it.
     */
    private fun build(): List<CustomFileEntry> {
        // Partial access reads as no access here. MediaStore would answer with
        // just the photos the user picked, and a folder that suddenly holds
        // six photos instead of six thousand deletes the rest everywhere.
        if (!hasFullLibraryAccess(context)) {
            throw IOException("Dinghy is not allowed to read the whole photo library")
        }

        val resolver = context.contentResolver

        // Android 10 and up hand out a copy with the GPS tags stripped unless
        // this is granted. A backup that quietly loses where every photo was
        // taken is a poor backup, so ask the library for the original when we
        // are allowed to -- and read and measure that same URI, so the size in
        // the index matches the bytes a peer receives either way.
        val original = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // A photo the camera is still writing has no final size or date yet.
        // Indexing it then would send a truncated file, and move it once it
        // was finished -- a delete and a re-upload on every peer.
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else {
            null
        }
        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            pending,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        ) ?: throw IOException("the photo library could not be read")

        // Grouped by month, and by year above that, so that no single
        // directory holds a whole library: the Go side finds an entry by
        // scanning its parent's children, which makes one huge directory
        // quadratic to walk.
        val months = LinkedHashMap<String, MutableList<CustomFileEntry>>()
        val takenNames = HashMap<String, MutableSet<String>>()

        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val seconds = timestamp(
                    takenMillis = it.getLong(takenColumn),
                    modified = it.getLong(modifiedColumn),
                    added = it.getLong(addedColumn),
                )
                val month = monthPath(seconds)

                val names = takenNames.getOrPut(month) { HashSet() }
                val name = uniqueName(it.getString(nameColumn) ?: "photo-$id", id, names)
                names.add(name)

                months.getOrPut(month) { mutableListOf() }.add(
                    PhotoEntry(
                        resolver = resolver,
                        uri = photoUri(id, original),
                        entryName = name,
                        modified = seconds,
                        sizeHint = it.getLong(sizeColumn),
                        servesOriginal = original,
                    ),
                )
            }
        }

        val years = LinkedHashMap<String, MutableList<CustomFileEntry>>()
        months.forEach { (month, photos) ->
            val (year, name) = month.split('/')
            years.getOrPut(year) { mutableListOf() }.add(DirEntry(name, photos))
        }

        val count = months.values.sumOf { it.size }
        Log.i(TAG, "photo library: $count photos in ${years.size} years")
        return years.map { (year, inYear) -> DirEntry(year, inYear) }
    }

    /**
     * When the photo was taken, in seconds, falling back until something is
     * usable: a photo with no date at all would file itself under 1970, and
     * move to its real place the moment MediaStore worked one out.
     */
    private fun timestamp(takenMillis: Long, modified: Long, added: Long): Long = when {
        takenMillis > 0 -> takenMillis / 1000
        modified > 0 -> modified
        else -> added
    }

    private fun photoUri(id: Long, original: Boolean): Uri {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return if (original) MediaStore.setRequireOriginal(uri) else uri
    }

    /**
     * UTC rather than the device's time zone: the zone travels with the phone,
     * and a photo that changed directory because of a flight would be a delete
     * and a re-upload on every peer.
     */
    private fun monthPath(seconds: Long): String {
        val date = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC)
        return "%04d/%02d".format(date.year, date.monthValue)
    }

    /**
     * MediaStore display names are not unique; a folder's entries must be.
     *
     * The id is not enough on its own: "photo-7.jpg" is a name a photo can
     * already have, and two entries with one name in a directory means the Go
     * side resolves both to whichever it scans first, leaving the other photo
     * out of the backup entirely. So it keeps going until the name is free.
     */
    private fun uniqueName(name: String, id: Long, taken: Set<String>): String {
        if (name !in taken) return name
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")

        fun candidate(suffix: String) =
            if (extension.isEmpty()) "$stem-$suffix" else "$stem-$suffix.$extension"

        var attempt = candidate(id.toString())
        var next = 2
        while (attempt in taken) {
            attempt = candidate("$id-$next")
            next++
        }
        return attempt
    }

    private companion object {
        const val MAX_AGE_MILLIS = 60 * 60 * 1000L
    }
}

/**
 * Whether the whole library can be read, as opposed to a few chosen photos.
 *
 * On Android 14+ the two are told apart by which permission is granted, which
 * only works because READ_MEDIA_VISUAL_USER_SELECTED is declared: see the
 * manifest.
 */
fun hasFullLibraryAccess(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

private fun List<CustomFileEntry>.at(index: Long): CustomFileEntry =
    getOrNull(index.toInt()) ?: throw IndexOutOfBoundsException("no child $index of $size")

private const val TAG = "PhotoFS"
