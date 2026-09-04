package net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding

/**
 * Minimal tokenizer for hybrid retrieval (CR-005): splits text into weighted
 * tokens. Chinese text is split by character bigrams plus latin words; stop
 * words are dropped. Kept intentionally simple — production would use a real
 * tokenizer.
 */
object Tokenizer {

    private val STOP_WORDS = setOf(
        "的", "了", "吗", "呢", "啊", "吧", "是", "在", "和", "与", "或", "把",
        "请", "帮我", "一下", "一", "个", "我", "你", "他", "它", "这", "那"
    )

    fun tokenize(text: String): List<String> {
        val normalized = text.lowercase().trim()
        val tokens = mutableListOf<String>()
        // Latin / numeric words.
        Regex("[a-z0-9]+").findAll(normalized).forEach { tokens += it.value }
        // Chinese char bigrams.
        val cjk = normalized.replace(Regex("[^\\u4e00-\\u9fa5]"), "")
        for (i in 0 until cjk.length - 1) {
            tokens += cjk.substring(i, i + 2)
        }
        if (cjk.length == 1) tokens += cjk
        return tokens.filter { it !in STOP_WORDS }
    }

    fun idf(term: String, docCount: Int, docFreq: Int): Double {
        if (docFreq == 0) return 0.0
        return kotlin.math.ln((docCount + 1.0) / (docFreq + 0.5) + 1.0)
    }
}
