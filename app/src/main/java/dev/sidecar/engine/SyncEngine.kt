package dev.sidecar.engine

import android.content.Context
import android.util.Log
import dev.sidecar.binding.sushitrain.Change
import dev.sidecar.binding.sushitrain.Client
import dev.sidecar.binding.sushitrain.ClientDelegate
import dev.sidecar.binding.sushitrain.ListOfStrings
import dev.sidecar.binding.sushitrain.Sushitrain
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Single thread so calls into Go are serialized and ordered. */
    private val engineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sidecar-engine") }
            .asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

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

            val created = Sushitrain.newClient(configDir.absolutePath, filesDir.absolutePath, false)
                ?: error("SushitrainCore returned no client")
            created.delegate = Delegate()
            // false: keep delta indexes. Resetting forces a full reindex with
            // every peer and is only for recovery.
            created.load(false)

            _state.value = EngineState.Starting
            created.start()
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
            Log.i(TAG, "discovered device $deviceID")
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
