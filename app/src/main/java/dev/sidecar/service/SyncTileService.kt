package dev.sidecar.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.sidecar.R
import dev.sidecar.engine.SyncEngine
import dev.sidecar.settings.Settings

/**
 * Quick-settings toggle for syncing. Worth having because the most common
 * reason to reach for this app is to stop it on a metered connection, and that
 * should not require opening it.
 */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val settings = Settings(this)
        if (SyncEngine.isRunning) {
            settings.syncEnabled = false
            SyncService.stop(this)
        } else {
            settings.syncEnabled = true
            SyncService.start(this)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = SyncEngine.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, android.R.drawable.stat_sys_upload_done)
        tile.updateTile()
    }
}
