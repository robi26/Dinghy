package dev.sidecar.settings

import android.content.Context

/**
 * Small synchronous preference store. Deliberately not DataStore: [Settings] is
 * read from a broadcast receiver, where there is no scope to suspend in.
 */
class Settings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("sidecar", Context.MODE_PRIVATE)

    /** Whether to start syncing on boot. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /**
     * Whether syncing should be running. Set false only by an explicit Stop, so
     * that opening the app or rebooting resumes syncing but a deliberate stop
     * sticks.
     */
    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply()

    /** Whether the user has been through onboarding. */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    private companion object {
        const val KEY_AUTO_START = "auto_start"
        const val KEY_SYNC_ENABLED = "sync_enabled"
        const val KEY_ONBOARDED = "onboarded"
    }
}
