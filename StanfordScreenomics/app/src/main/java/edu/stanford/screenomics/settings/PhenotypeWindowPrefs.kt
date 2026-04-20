package edu.stanford.screenomics.settings

import android.content.Context
import java.time.Duration

/**
 * Persisted interval for automatic engine-phenotype RF runs (same 1–60 minutes / hours pattern as cache window).
 */
object PhenotypeWindowPrefs {

    const val PREFS_NAME: String = "phenotype_auto_window_v1"
    private const val KEY_VALUE: String = "window_value"
    private const val KEY_USE_HOURS: String = "window_use_hours"

    fun readPair(context: Context): Pair<Int, Boolean> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.contains(KEY_VALUE)) {
            return 5 to false
        }
        val v = p.getInt(KEY_VALUE, 5).coerceIn(1, 60)
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

    fun periodMillis(context: Context): Long {
        val (v, h) = readPair(context)
        return duration(v, h).toMillis().coerceAtLeast(60_000L)
    }
}
