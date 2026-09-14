package ch.steigis.dinghy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.EngineState
import ch.steigis.dinghy.engine.SyncEngine
import ch.steigis.dinghy.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the Syncthing node alive.
 *
 * The foreground service type is `specialUse`, deliberately not `dataSync`:
 * since Android 15, `dataSync` services are capped at six hours per day, which
 * a sync daemon would hit. `specialUse` carries no such cap.
 *
 * Syncthing runs in this process rather than as a child process, which is what
 * keeps it clear of Android 12's restrictions on spawning child processes.
 */
class SyncService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        lifecycleScope.launch {
            SyncEngine.state.collectLatest { state ->
                if (isForegroundStarted) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }

        // Hold syncing off when the user's conditions are not met. Peers are
        // paused rather than the engine stopped, so browsing keeps working.
        //
        // Combined with the engine state on purpose: the conditions flow emits
        // as soon as the service is created, which is before the engine has
        // loaded. Applying the result then is a no-op, and since the flow only
        // re-emits on change, peers would stay however they were left -- paused
        // across a restart, in the worst case, with nothing to un-pause them.
        lifecycleScope.launch {
            combine(
                applicationContext.runConditions(),
                SyncEngine.state,
            ) { allowed, state -> allowed to state }
                .filter { (_, state) -> state is EngineState.Running }
                .map { (allowed, _) -> allowed }
                .distinctUntilChanged()
                .collectLatest { allowed ->
                    blockedBy = allowed
                    val syncing = allowed == SyncAllowed.Yes
                    SyncEngine.setPeersPaused(!syncing)
                    // Tied to the same condition as pausing: no point keeping
                    // the radio unfiltered when no peer is allowed to talk.
                    discoveryLock.setHeld(syncing)
                    if (isForegroundStarted) {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(SyncEngine.state.value),
                        )
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            discoveryLock.setHeld(false)
            lifecycleScope.launch {
                SyncEngine.stop()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(SyncEngine.state.value))
        lifecycleScope.launch { SyncEngine.start(applicationContext) }

        // Restart if the process is killed: the point of this service is to
        // keep syncing without the user reopening the app.
        return START_STICKY
    }

    override fun onDestroy() {
        discoveryLock.setHeld(false)
        // The engine deliberately outlives a bare unbind but not the service:
        // if the service is going away, the node should stop cleanly so the
        // database lock is released.
        lifecycleScope.launch { SyncEngine.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private var isForegroundStarted = false
    private var blockedBy: SyncAllowed = SyncAllowed.Yes

    /** Without this, peers on the same Wi-Fi are never discovered. */
    private val discoveryLock by lazy { DiscoveryLock(this) }

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        isForegroundStarted = true
    }

    private fun buildNotification(state: EngineState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val blocked = blockedBy
        val text = if (blocked != SyncAllowed.Yes) {
            when (blocked) {
                SyncAllowed.MeteredNetwork -> getString(R.string.status_paused_metered)
                SyncAllowed.NotCharging -> getString(R.string.status_paused_charging)
                SyncAllowed.NoNetwork -> getString(R.string.status_no_network)
                SyncAllowed.Yes -> ""
            }
        } else when (state) {
            is EngineState.Stopped -> getString(R.string.status_stopped)
            is EngineState.Loading -> getString(R.string.status_loading)
            is EngineState.Starting -> getString(R.string.status_starting)
            is EngineState.Failed -> getString(R.string.status_failed, state.message)
            is EngineState.Running ->
                resources.getQuantityString(
                    R.plurals.status_connected,
                    state.connectedPeers,
                    state.connectedPeers,
                    state.totalPeers,
                )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_dinghy_mono)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_sync),
            // Low: the notification exists because the platform requires one for
            // a foreground service, not because it is worth interrupting anyone.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ch.steigis.dinghy.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SyncService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
