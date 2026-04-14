package com.app.modules.screenshot

import kotlin.math.max

/**
 * Fixed-size input for [screenshot_sentiment.tflite]; must match
 * `tools/screenshot_sentiment_encoding.py` and `train_screenshot_sentiment_tflite.py`.
 */
internal object ScreenshotSentimentTextEncoding {

    const val INPUT_DIM: Int = 128

    fun encode(ocrText: String): FloatArray {
        val length = ocrText.length
        val span = max(1, length)
        return FloatArray(INPUT_DIM) { i ->
            val idx = i % span
            val c = if (length == 0) 0 else ocrText[idx].code
            ((c + i * 31) % 1025) / 1024f
        }
    }
}
