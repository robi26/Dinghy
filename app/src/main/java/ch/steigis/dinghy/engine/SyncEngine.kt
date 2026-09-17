package ch.steigis.dinghy.engine

import android.content.Context
import android.util.Log
import ch.steigis.dinghy.binding.sushitrain.Change
import ch.steigis.dinghy.binding.sushitrain.Client
import ch.steigis.dinghy.binding.sushitrain.ClientDelegate
import ch.steigis.dinghy.binding.sushitrain.DownloadDelegate
import ch.steigis.dinghy.binding.sushitrain.Entry
import ch.steigis.dinghy.binding.sushitrain.SearchResultDelegate
import ch.steigis.dinghy.binding.sushitrain.ListOfStrings
import ch.steigis.dinghy.binding.sushitrain.Peer
import ch.steigis.dinghy.binding.sushitrain.Sushitrain
import ch.steigis.dinghy.photos.PHOTO_FS_TYPE
import ch.steigis.dinghy.photos.PhotoFolderConfig
import ch.steigis.dinghy.photos.PhotoFilesystem
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the embedded Syncthing node.
 *
 * A process singleton by necessity, not just convenience: SushitrainCore takes
 * an exclusive file lock on its database, so a second client in the same
 * process would fail to load.
 *
 * Every call into Go runs on [engineDispatcher], a single thread. That
 * serializes access (the Go side guards its own state with a mutex, but
 * ordering matters for the load/start sequence) and keeps the slow ones --
 * `load()` migrates the database -- off the main thread.
 */
object SyncEngine {
    private const val TAG = "SyncEngine"

    /**
     * Selecting a path rewrites .stignore, which makes the folder scan, and a
     * folder that is mid-scan refuses the next selection. Copying a batch of
     * files in is exactly that situation, so a busy folder is waited out.
     */
    private const val SELECT_ATTEMPTS = 10
    private const val SELECT_RETRY_MILLIS = 300L

    /** How long to let photo-library changes settle before scanning. */
    private const val PHOTO_RESCAN_DEBOUNCE_MILLIS = 30_000L

    /** Long enough for a folder to come back after a configuration change. */
    private const val FOLDER_RESTART_MILLIS = 2_000L

    /** Single thread so calls into Go are serialized and ordered. */
    private val engineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "dinghy-engine") }
            .asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Devices seen announcing themselves on the local network, by id.
     *
     * Everything ever discovered, including peers already configured; the UI
     * filters rather than this, because a peer that is removed should reappear
     * here without waiting for the next announcement.
     */
    private val _discovered = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Change events from the engine; replay lets a late subscriber see recent activity. */
    private val _changes = MutableSharedFlow<FileChange>(replay = 16, extraBufferCapacity = 64)
    val changes: SharedFlow<FileChange> = _changes.asSharedFlow()

    private var client: Client? = null

    val isRunning: Boolean
        get() = _state.value is EngineState.Running

    /**
     * Starts the node if it is not already running. Safe to call repeatedly --
     * the service calls it on every start command.
     */
    suspend fun start(context: Context) = withContext(engineDispatcher) {
        if (client != null) {
            Log.i(TAG, "already started")
            return@withContext
        }

        try {
            val configDir = File(context.filesDir, "config").apply { mkdirs() }
            // Folder data lives in external files: user-visible through the
            // system file manager, removed on uninstall, and reachable without
            // any storage permission.
            val filesDir = (context.getExternalFilesDir(null) ?: context.filesDir)
                .apply { mkdirs() }

            _state.value = EngineState.Loading
            Log.i(TAG, "loading engine: config=$configDir files=$filesDir")

            // Before load(): the engine instantiates a photo folder's
            // filesystem as soon as it reads that folder's configuration, and
            // an unregistered filesystem type is a folder that cannot start.
            registerPhotoFilesystem(context)

            val created = Sushitrain.newClient(configDir.absolutePath, filesDir.absolutePath, false)
                ?: error("SushitrainCore returned no client")
            created.delegate = Delegate()
            // false: keep delta indexes. Resetting forces a full reindex with
            // every peer and is only for recovery.
            created.load(false)

            _state.value = EngineState.Starting
            created.start()
            // start() constructs the streaming server but does not bind it;
            // without this, every on-demand URL points at a closed port.
            created.server?.listen()
            client = created
            Log.i(TAG, "engine started as ${created.shortDeviceID()}")
            refreshState()
        } catch (t: Throwable) {
            Log.e(TAG, "engine failed to start", t)
            client = null
            _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    suspend fun stop() = withContext(engineDispatcher) {
        val running = client ?: return@withContext
        client = null
        try {
            running.stop()
            Log.i(TAG, "engine stopped")
        } catch (t: Throwable) {
            // Stop is best-effort; the process may be going away regardless.
            Log.w(TAG, "error while stopping engine", t)
        }
        _state.value = EngineState.Stopped
    }

    /**
     * Adds a peer device, optionally pinning static addresses.
     *
     * Addresses are worth setting explicitly whenever discovery cannot work --
     * across a NAT, or where local announce is blocked -- since the default is
     * "dynamic", which relies on discovery or relays.
     */
    suspend fun addPeer(deviceId: String, addresses: List<String> = emptyList()) =
        withContext(engineDispatcher) {
            val running = client ?: error("engine is not running")
            running.addPeer(deviceId)
            if (addresses.isNotEmpty()) {
                running.peerWithID(deviceId)?.setAddresses(addresses.toListOfStrings())
            }
            refreshState()
        }

    /** Device IDs of configured peers, excluding this device. */
    suspend fun peerIds(): List<String> = withContext(engineDispatcher) {
        val running = client ?: return@withContext emptyList()
        val ownId = running.deviceID()
        running.peers().toList().filter { it != ownId }
    }

    /**
     * Configured peers with everything the device list needs, this device
     * excluded. Connected peers sort first, then by name, so the ones you can
     * actually reach are at the top.
     */
    suspend fun devices(): List<DeviceInfo> = withContext(engineDispatcher) {
        val running = client ?: return@withContext emptyList()
        val ownId = running.deviceID()
        running.peers().toList()
            .filter { it != ownId }
            .mapNotNull { id -> running.peerWithID(id)?.let { describe(it, id) } }
            .sortedWith(compareByDescending<DeviceInfo> { it.isConnected }
                .thenBy { it.displayName.lowercase() })
    }

    /**
     * Discovered devices that are not already configured, and are not this
     * device. Read on demand rather than kept as a flow, because "already
     * configured" changes when a peer is added or removed and the announcement
     * that put it here will not repeat just to say so.
     */
    suspend fun discoveredDevices(): List<DiscoveredDevice> = withContext(engineDispatcher) {
        val running = client ?: return@withContext emptyList()
        val known = running.peers().toList().toSet() + running.deviceID()
        _discovered.value
            .filterKeys { it !in known }
            .map { (id, addresses) -> DiscoveredDevice(id, addresses) }
            .sortedBy { it.deviceId }
    }

    suspend fun device(deviceId: String): DeviceInfo? = withContext(engineDispatcher) {
        val running = client ?: return@withContext null
        running.peerWithID(deviceId)?.let { describe(it, deviceId) }
    }

    private fun describe(peer: Peer, deviceId: String) = DeviceInfo(
        deviceId = deviceId,
        name = runCatching { peer.name() }.getOrNull().orEmpty(),
        isConnected = runCatching { peer.isConnected }.getOrDefault(false),
        isPaused = runCatching { peer.isPaused }.getOrDefault(false),
        addresses = runCatching { peer.addresses().toList() }.getOrDefault(emptyList()),
        // lastSeen is a zero Date for a peer that has never connected, which
        // reads as 1970 rather than "never" unless it is filtered out here.
        lastSeen = runCatching { peer.lastSeen()?.unixMilliseconds() }
            .getOrNull()
            ?.takeIf { it > 0 },
    )

    /** Forgets a peer. The folders it shared stay, minus this device. */
    suspend fun removeDevice(deviceId: String) = withContext(engineDispatcher) {
        val running = client ?: error("engine is not running")
        running.peerWithID(deviceId)?.remove()
        refreshState()
    }

    // ---- folders -------------------------------------------------------

    suspend fun folders(): List<FolderInfo> = withContext(engineDispatcher) {
        val running = client ?: return@withContext emptyList()
        running.folders().toList().mapNotNull { id ->
            val folder = running.folderWithID(id) ?: return@mapNotNull null
            val stats = runCatching { folder.statistics() }.getOrNull()
            FolderInfo(
                id = id,
                label = folder.label().ifEmpty { id },
                path = folder.path(),
                isSelective = folder.isSelective,
                isPhotoFolder = runCatching { folder.filesystemType() }
                    .getOrNull() == PHOTO_FS_TYPE,
                isPaused = folder.isPaused,
                connectedPeers = folder.connectedPeerCount().toInt(),
                globalFiles = stats?.global?.files ?: 0,
                globalBytes = stats?.global?.bytes ?: 0,
                localBytes = stats?.local?.bytes ?: 0,
            )
        }
    }

    /**
     * Adds a folder. [onDemand] creates it selective -- ignores are set to "*",
     * so the index arrives but no content does until something is pinned.
     */
    suspend fun addFolder(folderId: String, onDemand: Boolean) = withContext(engineDispatcher) {
        val running = client ?: error("engine is not running")
        running.addFolder(folderId, "", onDemand, false)
        refreshState()
    }

    /**
     * Adds a folder that *is* the device's photo library.
     *
     * Nothing is copied into it: its files are served straight from MediaStore
     * by [PhotoFilesystem]. Send-only because the virtual filesystem cannot be
     * written to, and because a backup that can be written from the far end is
     * not a backup.
     *
     * The path a normal folder would have is where the layout settings live --
     * for a virtual filesystem the engine only passes the string through.
     */
    suspend fun addPhotoFolder(folderId: String) = withContext(engineDispatcher) {
        val running = client ?: error("engine is not running")
        running.addSpecialFolder(
            folderId,
            PHOTO_FS_TYPE,
            PhotoFolderConfig.DEFAULT_JSON,
            "sendonly",
        )
        running.folderWithID(folderId)?.let { folder ->
            // The virtual filesystem has nothing to watch: changes arrive as
            // MediaStore notifications instead. Left on, the folder would
            // retry a watcher it can never start, once a minute, forever.
            runCatching { folder.isWatcherEnabled = false }
                .onFailure { Log.w(TAG, "could not disable the watcher for $folderId", it) }
        }

        // That configuration change restarts the folder, which cancels the
        // scan it began when it was created ("hashing: context canceled").
        // Without another one the folder stays empty until the hourly rescan,
        // which is an hour of a backup that looks like it is not working.
        scope.launch {
            delay(FOLDER_RESTART_MILLIS)
            runCatching { client?.folderWithID(folderId)?.rescan() }
                .onFailure { Log.w(TAG, "could not scan $folderId", it) }
        }
        refreshState()
    }

    /** Whether a folder is the photo library rather than files on disk. */
    suspend fun isPhotoFolder(folderId: String): Boolean = withContext(engineDispatcher) {
        val running = client ?: return@withContext false
        val folder = running.folderWithID(folderId) ?: return@withContext false
        runCatching { folder.filesystemType() }.getOrNull() == PHOTO_FS_TYPE
    }

    private var photoFilesystem: PhotoFilesystem? = null

    @Volatile
    private var photoRescanJob: Job? = null

    private fun registerPhotoFilesystem(context: Context) {
        if (photoFilesystem != null) return
        val filesystem = PhotoFilesystem(context)
        filesystem.onLibraryChanged = ::onPhotoLibraryChanged
        Sushitrain.registerCustomFilesystemType(PHOTO_FS_TYPE, filesystem)
        photoFilesystem = filesystem
        Log.i(TAG, "registered the photo filesystem as $PHOTO_FS_TYPE")
    }

    /**
     * A new photo is only noticed when the folder is scanned, and the virtual
     * filesystem cannot be watched, so a library change asks for the scan
     * itself.
     *
     * Debounced because MediaStore reports every write: a burst of shots, or a
     * download of fifty images, would otherwise mean fifty scans of the whole
     * library.
     */
    private fun onPhotoLibraryChanged() {
        photoRescanJob?.cancel()
        photoRescanJob = scope.launch {
            delay(PHOTO_RESCAN_DEBOUNCE_MILLIS)
            val running = client ?: return@launch
            running.folders().toList().forEach { id ->
                val folder = running.folderWithID(id) ?: return@forEach
                if (folder.filesystemType() != PHOTO_FS_TYPE) return@forEach
                Log.i(TAG, "photo library changed; rescanning $id")
                runCatching { folder.rescan() }
                    .onFailure { Log.w(TAG, "could not rescan $id", it) }
            }
        }
    }

    /**
     * The entry's modification time, or null when there is not one.
     *
     * An entry the index has no date for comes back as a zero Date, which would
     * otherwise read as 1970 rather than being omitted.
     */
    private fun Entry.modifiedMillis(): Long? =
        runCatching { modifiedAt()?.unixMilliseconds() }.getOrNull()?.takeIf { it > 0 }

    /** Peers, paired with whether this folder is shared with each. */
    suspend fun folderShares(folderId: String): List<Pair<String, Boolean>> =
        withContext(engineDispatcher) {
            val running = client ?: return@withContext emptyList()
            val folder = running.folderWithID(folderId) ?: return@withContext emptyList()
            val ownId = running.deviceID()
            running.peers().toList()
                .filter { it != ownId }
                .map { it to folder.isSharedWithDeviceID(it) }
        }

    /**
     * Turns on-demand syncing on or off for a folder.
     *
     * The two directions are not symmetrical. Turning it on appends "*" to the
     * folder's ignores, so nothing new is fetched; files already downloaded are
     * ignored rather than deleted, and stay put. Turning it off removes that
     * pattern, which means the folder will proceed to download all of it.
     */
    suspend fun setFolderSelective(folderId: String, selective: Boolean) =
        withContext(engineDispatcher) {
            val running = client ?: error("engine is not running")
            val folder = running.folderWithID(folderId) ?: error("no folder $folderId")
            folder.setSelective(selective)
            refreshState()
        }

    /**
     * Stops syncing a folder.
     *
     * [deleteLocalFiles] is the whole decision. Unlink drops the folder from the
     * configuration and leaves whatever was downloaded sitting on the device;
     * Remove unlinks and then deletes the folder's contents. The second is not
     * recoverable from inside this app, so the caller has to say which it meant
     * rather than getting one by default.
     */
    suspend fun removeFolder(folderId: String, deleteLocalFiles: Boolean) =
        withContext(engineDispatcher) {
            val running = client ?: error("engine is not running")
            val folder = running.folderWithID(folderId) ?: error("no folder $folderId")
            if (deleteLocalFiles) folder.remove() else folder.unlink()
            refreshState()
        }

    suspend fun shareFolder(folderId: String, deviceId: String, share: Boolean) =
        withContext(engineDispatcher) {
            val running = client ?: error("engine is not running")
            running.folderWithID(folderId)?.shareWithDevice(deviceId, share, "")
        }

    // ---- browsing ------------------------------------------------------

    /**
     * Lists one directory level from the global index. [prefix] is "" for the
     * folder root and otherwise ends in "/".
     */
    suspend fun browse(folderId: String, prefix: String): List<EntryInfo> =
        withContext(engineDispatcher) {
            val running = client ?: return@withContext emptyList()
            val folder = running.folderWithID(folderId) ?: return@withContext emptyList()

            // The second argument is Syncthing's `returnOnlyDirectories`, not
            // "include directories": passing true hides every file.
            folder.list(prefix, false, false).toList()
                .mapNotNull { name -> entryInfo(folder, prefix + name, name) }
                .sortedWith(compareByDescending<EntryInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    /**
     * How many entries a directory holds, one level deep.
     *
     * Separate from [browse] because it is the cheap half of it: the names
     * alone answer the question, so this skips the getFileInformation call per
     * child that building an [EntryInfo] needs. Counts what the *global* index
     * holds, like everything else here, so it is the same number whether or not
     * the contents were downloaded.
     */
    suspend fun childCount(folderId: String, path: String): Int =
        withContext(engineDispatcher) {
            val running = client ?: return@withContext 0
            val folder = running.folderWithID(folderId) ?: return@withContext 0
            val prefix = if (path.isEmpty()) "" else path.trimEnd('/') + "/"
            folder.list(prefix, false, false)?.count()?.toInt() ?: 0
        }

    /**
     * Pins or unpins an entry. Pinning writes a "!/path" exception ahead of the
     * catch-all "*" in .stignore, which is what makes the content arrive.
     */
    suspend fun setSelected(folderId: String, path: String, selected: Boolean) =
        withContext(engineDispatcher) {
            val running = client ?: error("engine is not running")
            val folder = running.folderWithID(folderId) ?: error("no such folder")
            val entry = folder.getFileInformation(path) ?: error("no such entry")
            entry.setExplicitlySelected(selected)
        }

    /**
     * Selects a path that is on disk but not in the index yet.
     *
     * The counterpart of [setSelected] for files this device is the source of.
     * An on-demand folder's .stignore ends in a catch-all "*", and Syncthing
     * never offers an ignored file to a peer, so a file written into such a
     * folder stays on the phone unless it is selected here. [setSelected]
     * cannot do it: it works from a global index entry, which a file that
     * exists only locally does not have.
     *
     * A folder that syncs in full ignores nothing, so there is nothing to do.
     */
    suspend fun selectLocalFile(folderId: String, path: String): Unit =
        withContext(engineDispatcher) {
            val running = client ?: error("engine is not running")
            val folder = running.folderWithID(folderId) ?: error("no folder $folderId")
            if (!folder.isSelective) return@withContext

            var failure: Exception? = null
            repeat(SELECT_ATTEMPTS) { attempt ->
                try {
                    folder.setLocalFileExplicitlySelected(path, true)
                    return@withContext
                } catch (e: Exception) {
                    failure = e
                    Log.d(TAG, "could not select $path yet (attempt ${attempt + 1})", e)
                    if (attempt < SELECT_ATTEMPTS - 1) delay(SELECT_RETRY_MILLIS)
                }
            }
            throw IOException("could not select $path: ${failure?.message}", failure)
        }

    /**
     * Asks the engine to index one path now, rather than leaving it to the
     * filesystem watcher or the hourly rescan.
     *
     * Fire-and-forget by design: the scan runs asynchronously inside the engine
     * anyway, and the caller is a file-descriptor close callback that must not
     * block on it.
     */
    fun rescanLater(folderId: String, path: String) {
        scope.launch {
            runCatching { client?.folderWithID(folderId)?.rescanSubdirectory(path) }
                .onFailure { Log.w(TAG, "could not rescan $path", it) }
        }
    }

    suspend fun entry(folderId: String, path: String): EntryInfo? =
        withContext(engineDispatcher) {
            val running = client ?: return@withContext null
            val folder = running.folderWithID(folderId) ?: return@withContext null
            entryInfo(folder, path, path.trimEnd('/').substringAfterLast('/'))
        }

    private fun entryInfo(
        folder: ch.steigis.dinghy.binding.sushitrain.Folder,
        path: String,
        name: String,
    ): EntryInfo? = try {
        folder.getFileInformation(path.trimEnd('/'))?.let { e ->
            EntryInfo(
                name = name.trimEnd('/'),
                path = e.path(),
                isDirectory = e.isDirectory,
                size = e.size(),
                isLocallyPresent = e.isLocallyPresent,
                isExplicitlySelected = e.isExplicitlySelected,
                isSelected = e.isSelected,
                isConflictCopy = e.isConflictCopy,
                modifiedAt = e.modifiedMillis(),
                modifiedBy = runCatching { e.modifiedByShortDeviceID() }.getOrNull().orEmpty(),
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read entry $path", t)
        null
    }

    // ---- run conditions -------------------------------------------------

    /**
     * Pauses or resumes every peer.
     *
     * Deliberately pauses *peers* rather than stopping the engine: the index
     * stays loaded, so folders remain browsable and already-downloaded files
     * remain readable while syncing is held off. For an on-demand app that is
     * the difference between "waiting" and "unusable".
     */
    suspend fun setPeersPaused(paused: Boolean) = withContext(engineDispatcher) {
        val running = client ?: return@withContext
        val ownId = running.deviceID()
        running.peers().toList().filter { it != ownId }.forEach { id ->
            runCatching { running.peerWithID(id)?.setPaused(paused) }
                .onFailure { Log.w(TAG, "could not set paused=$paused on $id", it) }
        }
        refreshState()
    }

    // ---- search -----------------------------------------------------------

    /**
     * Searches file names in the global index, so it finds files that are not
     * downloaded. Results arrive through a delegate; this collects them.
     */
    suspend fun search(
        text: String,
        folderId: String = "",
        maxResults: Long = 200,
    ): List<EntryInfo> = withContext(engineDispatcher) {
        val running = client ?: return@withContext emptyList()
        if (text.isBlank()) return@withContext emptyList()

        val results = mutableListOf<EntryInfo>()
        val job = coroutineContext[Job]
        running.search(
            text,
            object : SearchResultDelegate {
                override fun result(entry: Entry?) {
                    val e = entry ?: return
                    runCatching {
                        // A search with no folderId spans every folder, so each
                        // result has to carry its own.
                        val folder = runCatching { e.folder }.getOrNull()
                        results.add(
                            EntryInfo(
                                name = e.fileName(),
                                path = e.path(),
                                isDirectory = e.isDirectory,
                                size = e.size(),
                                isLocallyPresent = e.isLocallyPresent,
                                isExplicitlySelected = e.isExplicitlySelected,
                                isSelected = e.isSelected,
                                isConflictCopy = e.isConflictCopy,
                                modifiedAt = e.modifiedMillis(),
                                modifiedBy = runCatching { e.modifiedByShortDeviceID() }
                                    .getOrNull().orEmpty(),
                                folderId = folder?.folderID.orEmpty(),
                                folderLabel = folder?.let {
                                    runCatching { it.label() }.getOrNull()?.ifEmpty { null }
                                } ?: folder?.folderID.orEmpty(),
                            ),
                        )
                    }
                }

                override fun isCancelled(): Boolean = job?.isActive == false
            },
            maxResults,
            folderId,
            "",
        )
        results
    }

    /**
     * Starts the engine and waits until it is usable, for callers that cannot
     * suspend and have nothing to show until it is -- notably the
     * DocumentsProvider, which the system may invoke in a cold process.
     */
    fun ensureStartedBlocking(context: Context, timeoutMillis: Long = 20_000): Boolean =
        runBlocking {
            if (isRunning) return@runBlocking true
            withTimeoutOrNull(timeoutMillis) {
                start(context)
                state.first { it is EngineState.Running || it is EngineState.Failed }
            } is EngineState.Running
        }

    /** Absolute on-device path of a folder's root. */
    suspend fun folderPath(folderId: String): String? = withContext(engineDispatcher) {
        client?.folderWithID(folderId)?.path()
    }

    // ---- on-demand access ----------------------------------------------

    /**
     * A signed localhost URL serving this entry, fetching blocks from peers as
     * they are read. Range requests work against files that were never
     * downloaded, which is what makes streaming and previewing possible.
     */
    suspend fun onDemandUrl(folderId: String, path: String): String? =
        withContext(engineDispatcher) {
            val running = client ?: return@withContext null
            val folder = running.folderWithID(folderId) ?: return@withContext null
            folder.getFileInformation(path)?.onDemandURL()
        }

    /**
     * Downloads one entry to [destination], reporting progress as a fraction.
     * Unlike pinning, this does not change the folder's selection -- it is a
     * one-off copy, for "save to my device" rather than "keep this in sync".
     */
    suspend fun download(
        folderId: String,
        path: String,
        destination: File,
        onProgress: (Double) -> Unit,
    ): String = withContext(engineDispatcher) {
        val running = client ?: error("engine is not running")
        val folder = running.folderWithID(folderId) ?: error("no such folder")
        val entry = folder.getFileInformation(path) ?: error("no such entry")

        // isCancelled() is called from Go on a non-coroutine thread, so the
        // caller's Job is captured here rather than looked up inside it.
        val callerJob = coroutineContext[Job]
        val result = CompletableDeferred<String>()
        entry.download(
            destination.absolutePath,
            object : DownloadDelegate {
                override fun onProgress(fraction: Double) = onProgress(fraction)
                override fun onFinished(path: String?) {
                    result.complete(path.orEmpty())
                }

                override fun onError(error: String?) {
                    result.completeExceptionally(RuntimeException(error ?: "download failed"))
                }

                override fun isCancelled(): Boolean = callerJob?.isActive == false
            },
        )
        result.await()
    }

    /** Recomputes the observable state from the engine. Cheap; safe to call often. */
    suspend fun refresh() = withContext(engineDispatcher) { refreshState() }

    private fun refreshState() {
        val running = client ?: return
        try {
            val ownId = running.deviceID()
            // Syncthing's device list always contains the local device, so it
            // has to be excluded or a fresh install reports one peer.
            val peerCount = running.peers().toList().count { it != ownId }
            _state.value = EngineState.Running(
                deviceId = ownId,
                shortDeviceId = running.shortDeviceID(),
                connectedPeers = running.connectedPeerCount().toInt(),
                totalPeers = peerCount,
                folders = running.folders()?.count()?.toInt() ?: 0,
                listenAddresses = emptyList(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not read engine state", t)
        }
    }

    /**
     * Receives callbacks from Go. These arrive on Go-owned threads, so nothing
     * here may block or touch the UI directly -- each one only pokes a flow.
     */
    private class Delegate : ClientDelegate {
        override fun onEvent(event: String?) {
            // Connection and configuration events both change what the UI shows.
            scope.launch { refreshState() }
        }

        override fun onDeviceDiscovered(deviceID: String?, addresses: ListOfStrings?) {
            val id = deviceID ?: return
            Log.i(TAG, "discovered device $id")
            // Announcements repeat, so this replaces rather than accumulates:
            // the newest addresses are the ones worth dialling.
            _discovered.value = _discovered.value + (id to addresses.toList())
        }

        override fun onListenAddressesChanged(addresses: ListOfStrings?) {
            val list = addresses.toList()
            scope.launch {
                val current = _state.value
                if (current is EngineState.Running) {
                    _state.value = current.copy(listenAddresses = list)
                }
            }
        }

        override fun onChange(change: Change?) {
            val c = change ?: return
            _changes.tryEmit(
                FileChange(
                    folderId = c.folderID.orEmpty(),
                    path = c.path.orEmpty(),
                    action = c.action.orEmpty(),
                    shortDeviceId = c.shortID.orEmpty(),
                ),
            )
        }

        override fun onMeasurementsUpdated() = Unit
    }
}

data class FileChange(
    val folderId: String,
    val path: String,
    val action: String,
    val shortDeviceId: String,
)

internal fun List<String>.toListOfStrings(): ListOfStrings =
    ListOfStrings().also { out -> forEach(out::append) }

/** gomobile exposes string slices as [ListOfStrings] rather than as arrays. */
internal fun ListOfStrings?.toList(): List<String> {
    val list = this ?: return emptyList()
    return (0 until list.count()).mapNotNull { list.itemAt(it) }
}
