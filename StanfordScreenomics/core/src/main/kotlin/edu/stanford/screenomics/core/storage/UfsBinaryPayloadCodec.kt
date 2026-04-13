package edu.stanford.screenomics.core.storage

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater

/**
 * Shared **zlib deflate + Base64 + SHA-256** pipeline for Layer-1→2 media hints consumed by
 * [DefaultDistributedStorageManager] (parallel IO path).
 */
object UfsBinaryPayloadCodec {

    fun deflatedBase64AndSha256Hex(raw: ByteArray): Pair<String, String> {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val bos = ByteArrayOutputStream(raw.size.coerceAtLeast(256))
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) {
                bos.write(buf, 0, n)
            }
        }
        deflater.end()
        val deflated = bos.toByteArray()
        val b64 = Base64.getEncoder().encodeToString(deflated)
        val digest = MessageDigest.getInstance("SHA-256").digest(deflated)
        val shaHex = digest.joinToString(separator = "") { b -> "%02x".format(b) }
        return b64 to shaHex
    }

    fun cleanedPcm16LeBytes(interleavedCleaned: ShortArray): ByteArray {
        val out = ByteArray(interleavedCleaned.size * 2)
        var o = 0
        for (s in interleavedCleaned) {
            out[o++] = (s.toInt() and 0xFF).toByte()
            out[o++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
