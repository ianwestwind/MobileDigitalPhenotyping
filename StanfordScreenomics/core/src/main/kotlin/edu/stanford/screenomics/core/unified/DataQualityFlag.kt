package edu.stanford.screenomics.core.unified

/**
 * Placeholder vocabulary for capture-time quality metadata (e.g. encoded under keys in [UnifiedDataPoint.data]
 * or referenced from [EntityAttributeSpec.semanticDescription] conventions).
 */
enum class DataQualityFlag {
    UNKNOWN,
    SENSOR_WARMING,
    LOW_CONFIDENCE,
    DROPPED_FRAMES,
    CLOCK_SKEW_SUSPECTED,
    PERMISSION_RESTRICTED,
    BATTERY_SAVER_IMPACT,
}
