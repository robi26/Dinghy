package ch.steigis.dinghy.engine

/** Lifecycle of the embedded Syncthing node. */
sealed interface EngineState {
    data object Stopped : EngineState

    /** `Client.load()` opens and migrates the database and can take a while. */
    data object Loading : EngineState

    data object Starting : EngineState

    data class Running(
        val deviceId: String,
        val shortDeviceId: String,
        val connectedPeers: Int,
        val totalPeers: Int,
        val folders: Int,
        val listenAddresses: List<String>,
    ) : EngineState

    data class Failed(val message: String) : EngineState
}
