package edu.stanford.screenomics.core.storage

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shared `yyyyMMddHHmmssSSS` UTC suffix for strict minimal local/cloud artifact names
 * (e.g. `audio_20260413153045123.bin`).
 */
object StorageArtifactFilename {

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC)

    fun stampUtc(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))
}
