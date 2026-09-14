package ch.steigis.dinghy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import ch.steigis.dinghy.settings.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether syncing is currently allowed, and why not when it is not.
 *
 * The reason is carried along because a phone that has quietly stopped syncing
 * is the worst outcome: the notification says which condition is holding it.
 */
sealed interface SyncAllowed {
    data object Yes : SyncAllowed
    data object MeteredNetwork : SyncAllowed
    data object NotCharging : SyncAllowed
    data object NoNetwork : SyncAllowed
}

/**
 * Emits whether the configured run conditions are met, updating whenever the
 * network or charging state changes.
 */
fun Context.runConditions(): Flow<SyncAllowed> = callbackFlow {
    val settings = Settings(this@runConditions)
    val connectivity = getSystemService(ConnectivityManager::class.java)

    fun evaluate(): SyncAllowed {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        if (capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            return SyncAllowed.NoNetwork
        }
        if (!settings.syncOnMetered &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        ) {
            return SyncAllowed.MeteredNetwork
        }
        if (settings.syncOnlyWhenCharging && !isCharging()) {
            return SyncAllowed.NotCharging
        }
        return SyncAllowed.Yes
    }

    fun evaluateLogged(source: String): SyncAllowed {
        val result = evaluate()
        android.util.Log.d(
            "RunConditions",
            "$source -> $result (onlyCharging=${settings.syncOnlyWhenCharging} " +
                "charging=${isCharging()} onMetered=${settings.syncOnMetered})",
        )
        return result
    }

    trySend(evaluateLogged("initial"))

    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(evaluate()) }
        override fun onLost(network: Network) { trySend(evaluate()) }
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            // Metered-ness can change without the network changing, e.g. when a
            // hotspot is marked metered.
            trySend(evaluate())
        }
    }
    connectivity.registerNetworkCallback(
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build(),
        networkCallback,
    )

    val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(evaluateLogged("power:${intent.action?.substringAfterLast('.')}"))
        }
    }
    registerReceiver(
        powerReceiver,
        IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            // POWER_CONNECTED/DISCONNECTED are not always emitted when the
            // charging state changes -- wireless and dock charging in
            // particular. BATTERY_CHANGED is noisy, but the flow is
            // distinctUntilChanged, so the noise costs nothing.
            addAction(Intent.ACTION_BATTERY_CHANGED)
        },
    )

    // The conditions are read from settings, so a settings change has to
    // re-evaluate them too -- otherwise flipping a toggle does nothing until
    // the network happens to change.
    val settingsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(evaluateLogged("settings"))
        }
    settings.prefs.registerOnSharedPreferenceChangeListener(settingsListener)

    // Safety net. Everything above is edge-triggered, so a single missed
    // broadcast would leave syncing paused indefinitely with no way back.
    // distinctUntilChanged means a re-check that finds no change costs nothing.
    val ticker = launch {
        while (isActive) {
            delay(RECHECK_INTERVAL_MS)
            trySend(evaluateLogged("recheck"))
        }
    }

    awaitClose {
        ticker.cancel()
        connectivity.unregisterNetworkCallback(networkCallback)
        runCatching { unregisterReceiver(powerReceiver) }
        settings.prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
    }
}.distinctUntilChanged()

/** Slow enough to be invisible on battery, fast enough to recover unattended. */
private const val RECHECK_INTERVAL_MS = 15 * 60 * 1000L

private fun Context.isCharging(): Boolean {
    val battery = getSystemService(BatteryManager::class.java)
    return battery.isCharging
}
