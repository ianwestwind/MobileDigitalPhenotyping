package edu.stanford.screenomics.core.storage

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Round-trip UTF-8 JSON for structured Firestore maps written while motion accel/gyro upload is paused.
 */
object StructuredFirestorePendingCodec {

    fun encodeToUtf8Bytes(fields: Map<String, Any?>): ByteArray =
        jsonObjectFromMap(fields).toString().toByteArray(StandardCharsets.UTF_8)

    fun decodeFromUtf8Bytes(bytes: ByteArray): Map<String, Any?> {
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        return jsonObjectToMap(root)
    }

    private fun jsonObjectFromMap(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            if (v == null) continue
            o.put(k, toJsonValue(v))
        }
        return o
    }

    private fun toJsonValue(v: Any): Any = when (v) {
        is Map<*, *> -> {
            val m = LinkedHashMap<String, Any?>()
            for ((k, value) in v) {
                if (value != null) m[k.toString()] = value
            }
            jsonObjectFromMap(m)
        }
        is List<*> -> JSONArray(v.map { item -> item?.let { toJsonValue(it) } ?: JSONObject.NULL })
        is Array<*> -> JSONArray(v.map { item -> item?.let { toJsonValue(it) } ?: JSONObject.NULL })
        is Number, Boolean, String -> v
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonObjectToMap(o: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = fromJsonValue(o.get(k))
        }
        return out
    }

    private fun fromJsonValue(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> (0 until v.length()).map { i -> fromJsonValue(v.get(i)) }
        else -> v
    }
}
