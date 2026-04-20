package edu.stanford.screenomics.settings

import android.content.Context
import edu.stanford.screenomics.core.collection.ModalityUserCadenceMillis
import edu.stanford.screenomics.core.unified.ModalityKind
import java.time.Duration

/**
 * SharedPreferences for per-modality interval (1–60, seconds or minutes) shown on [edu.stanford.screenomics.MainActivity].
 * Drives both volatile [edu.stanford.screenomics.core.module.template.BaseCache] spacing and
 * [ModalityUserCadenceMillis] so collection loops pick up changes in real time.
 */
object VolatileIntervalPrefs {

    const val PREFS_NAME: String = "cache_volatile_interval_v3"

    fun defaultValueAndMinutes(modality: ModalityKind): Pair<Int, Boolean> =
        when (modality) {
            ModalityKind.AUDIO -> 55 to false
            ModalityKind.MOTION -> 1 to true
            ModalityKind.GPS -> 30 to false
            ModalityKind.SCREENSHOT -> 5 to false
        }

    fun readPair(context: Context, modality: ModalityKind): Pair<Int, Boolean> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val k = modality.name
        if (!p.contains("${k}_value")) {
            return defaultValueAndMinutes(modality)
        }
        val v = p.getInt("${k}_value", 1).coerceIn(1, 60)
        val minutes = p.getBoolean("${k}_minute", false)
        return v to minutes
    }

    fun writePair(context: Context, modality: ModalityKind, value: Int, useMinutes: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("${modality.name}_value", value.coerceIn(1, 60))
            .putBoolean("${modality.name}_minute", useMinutes)
            .apply()
    }

    fun duration(value: Int, useMinutes: Boolean): Duration =
        if (useMinutes) Duration.ofMinutes(value.toLong()) else Duration.ofSeconds(value.toLong())

    /** Push every modality’s saved interval into [ModalityUserCadenceMillis] (e.g. when foreground collection starts). */
    fun syncCollectionCadenceRegistryFromPrefs(context: Context) {
        for (m in enumValues<ModalityKind>()) {
            val (v, useMinutes) = readPair(context, m)
            ModalityUserCadenceMillis.setForModality(m, duration(v, useMinutes).toMillis())
        }
    }
}
