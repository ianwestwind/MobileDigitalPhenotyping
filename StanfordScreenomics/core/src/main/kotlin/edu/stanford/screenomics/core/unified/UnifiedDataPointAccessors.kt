package edu.stanford.screenomics.core.unified

/**
 * Management-layer helpers: correlation is carried inside [UnifiedDataPoint.data] under [UfsReservedDataKeys.CORRELATION_ID].
 */
fun UnifiedDataPoint.requireCorrelationId(): CorrelationId {
    val raw = data[UfsReservedDataKeys.CORRELATION_ID]
        ?: error("missing ${UfsReservedDataKeys.CORRELATION_ID}")
    return CorrelationId(raw as String)
}

fun UnifiedDataPoint.correlationIdOrNull(): CorrelationId? {
    val raw = data[UfsReservedDataKeys.CORRELATION_ID] as? String ?: return null
    if (raw.isBlank()) return null
    return CorrelationId(raw)
}
