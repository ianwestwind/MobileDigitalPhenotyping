package edu.stanford.screenomics.settings

import android.content.Context
import edu.stanford.screenomics.core.management.VolatileCacheWindowRetention
import java.time.Duration

/**
 * Persisted global in-memory cache sliding window (same for all modalities), 1–60 minutes or 1–60 hours.
 */
object VolatileCacheWindowPrefs {

    const val PREFS_NAME: String = "volatile_cache_window_v1"
    private const val KEY_VALUE: String = "window_value"
    private const val KEY_USE_HOURS: String = "window_use_hours"

    fun readPair(context: Context): Pair<Int, Boolean> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.contains(KEY_VALUE)) {
            return 30 to false
        }
        val v = p.getInt(KEY_VALUE, 30).coerceIn(1, 60)
        val hours = p.getBoolean(KEY_USE_HOURS, false)
        return v to hours
    }

    fun writePair(context: Context, value: Int, useHours: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VALUE, value.coerceIn(1, 60))
            .putBoolean(KEY_USE_HOURS, useHours)
            .apply()
    }

    fun duration(value: Int, useHours: Boolean): Duration =
        if (useHours) Duration.ofHours(value.toLong()) else Duration.ofMinutes(value.toLong())

    fun syncRetentionFromPrefs(context: Context) {
        val (v, h) = readPair(context)
        VolatileCacheWindowRetention.setDuration(duration(v, h))
    }
}
