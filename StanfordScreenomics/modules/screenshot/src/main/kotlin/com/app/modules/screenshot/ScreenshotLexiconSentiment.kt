package com.app.modules.screenshot

/**
 * Fallback when TFLite inference fails: same lexicon heuristic, aligned with training labels (1–10).
 */
internal object ScreenshotLexiconSentiment {

    private val positive: Set<String> = setOf(
        "good", "great", "excellent", "amazing", "love", "happy", "best", "thanks", "thank", "yes",
        "wonderful", "fantastic", "awesome", "nice", "perfect", "beautiful", "glad", "enjoy", "fun",
        "success", "win", "won", "like", "hope", "better", "well", "fine", "ok", "okay", "congrats",
        "congratulations", "pleased", "delighted", "sweet", "cute", "adorable", "brilliant", "super",
    )

    private val negative: Set<String> = setOf(
        "bad", "hate", "terrible", "awful", "worst", "sad", "angry", "no", "never", "fail", "failed",
        "error", "wrong", "sorry", "pain", "hurt", "ugly", "horrible", "disgusting", "annoying", "stupid",
        "trash", "garbage", "scam", "fraud", "cancel", "refund", "complaint", "unfortunately", "problem",
        "issue", "bug", "crash", "lost", "miss", "missed", "late", "wait", "waiting", "blocked", "denied",
    )

    /** [-1, 1] from word hits; 0 = neutral / no sentiment tokens. */
    fun scoreNeg1To1(ocrText: String): Double {
        if (ocrText.isBlank()) return 0.0
        val tokens = TOKEN_REGEX.findAll(ocrText.lowercase())
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()
        if (tokens.isEmpty()) return 0.0
        var pos = 0
        var neg = 0
        for (t in tokens) {
            when {
                t in positive -> pos++
                t in negative -> neg++
            }
        }
        val total = pos + neg
        if (total == 0) return 0.0
        return ((pos - neg).toDouble() / total.toDouble()).coerceIn(-1.0, 1.0)
    }

    /**
     * Maps lexicon polarity to [1, 10] (same mapping as training script targets).
     * No keywords or blank OCR → ~5.5 (neutral).
     */
    fun scoreOneToTen(ocrText: String): Double {
        val s = scoreNeg1To1(ocrText)
        if (s == 0.0) return 5.5
        return (1.0 + (s + 1.0) * 4.5).coerceIn(1.0, 10.0)
    }

    private val TOKEN_REGEX = Regex("[a-zA-Z']+")
}
