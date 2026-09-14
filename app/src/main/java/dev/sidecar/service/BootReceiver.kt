package dev.sidecar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.sidecar.settings.Settings

/**
 * Restarts syncing after a reboot. Without this the node stays down until the
 * user opens the app, which defeats the point of a background sync daemon.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(context)
        if (!settings.autoStart || !settings.syncEnabled) return
        SyncService.start(context)
    }
}
