package edu.stanford.screenomics.core.unified

import kotlin.jvm.JvmInline

/**
 * Stable identifier linking raw capture, adapted fusion output, cache entries, and downstream tasks.
 */
@JvmInline
value class CorrelationId(val value: String)
