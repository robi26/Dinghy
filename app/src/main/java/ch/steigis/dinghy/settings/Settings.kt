package ch.steigis.dinghy.settings

import android.content.Context

/**
 * Small synchronous preference store. Deliberately not DataStore: [Settings] is
 * read from a broadcast receiver, where there is no scope to suspend in.
 */
class Settings(context: Context) {
    internal val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    /** Sync over metered connections (mobile data, metered hotspots). */
    var syncOnMetered: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ON_METERED, false)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_ON_METERED, value).apply()

    /** Only sync while charging. */
    var syncOnlyWhenCharging: Boolean
        get() = prefs.getBoolean(KEY_ONLY_CHARGING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONLY_CHARGING, value).apply()

    /** Whether the user has been through onboarding. */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    companion object {
        const val PREFS_NAME = "dinghy"

        const val KEY_AUTO_START = "auto_start"
        const val KEY_SYNC_ENABLED = "sync_enabled"
        const val KEY_SYNC_ON_METERED = "sync_on_metered"
        const val KEY_ONLY_CHARGING = "only_charging"
        const val KEY_ONBOARDED = "onboarded"
    }
}
