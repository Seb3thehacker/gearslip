package app.seb3thehacker.gearslip

import android.content.Context

/** User-facing preferences. Read fresh on every session, so changes apply on the next connect. */
object AppSettings {

    private const val PREFS = "gearslip_settings"
    private const val KEY_STARTUP_URL = "startup_url"
    private const val KEY_SEEN_COMPAT_WARNING = "seen_compat_warning"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default) ?: default

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    /** Blank means "use the built-in test page". */
    fun startupUrl(context: Context): String =
        prefs(context).getString(KEY_STARTUP_URL, "").orEmpty()

    fun setStartupUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STARTUP_URL, value.trim()).apply()
    }

    /** Whether the newer-car compatibility warning has already been shown and dismissed once. */
    fun hasSeenCompatWarning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEN_COMPAT_WARNING, false)

    fun setSeenCompatWarning(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN_COMPAT_WARNING, true).apply()
    }
}
