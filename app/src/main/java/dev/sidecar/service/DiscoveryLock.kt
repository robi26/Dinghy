package dev.sidecar.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Keeps Wi-Fi multicast and broadcast reception open while syncing.
 *
 * In Wi-Fi power save the chip filters out packets not addressed to this
 * device, which silently kills Syncthing's local discovery -- a UDP broadcast
 * on port 21027 plus an IPv6 multicast. The failure mode is not an error but an
 * absence: peers on the same network are simply never found, and the user is
 * left typing addresses by hand. Syncthing-Fork holds the same lock for the
 * same reason.
 *
 * The lock is held only while syncing is actually allowed to run. It has a real
 * battery cost -- it stops the radio filtering traffic meant for other hosts --
 * so there is no point holding it while peers are paused.
 */
class DiscoveryLock(context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private var lock: WifiManager.MulticastLock? = null

    val isHeld: Boolean get() = lock?.isHeld == true

    fun setHeld(held: Boolean) {
        if (held) acquire() else release()
    }

    private fun acquire() {
        if (isHeld) return
        val manager = wifiManager ?: run {
            Log.w(TAG, "no WifiManager; local discovery may not work")
            return
        }
        runCatching {
            manager.createMulticastLock(TAG).apply {
                // Not reference counted: this class owns exactly one lock and
                // tracks it, so a stray extra release cannot throw.
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess {
            lock = it
            Log.i(TAG, "multicast lock acquired; local discovery enabled")
        }.onFailure {
            Log.w(TAG, "could not acquire multicast lock", it)
        }
    }

    private fun release() {
        val current = lock ?: return
        lock = null
        runCatching { if (current.isHeld) current.release() }
            .onFailure { Log.w(TAG, "could not release multicast lock", it) }
    }

    private companion object {
        const val TAG = "sidecar-discovery"
    }
}
