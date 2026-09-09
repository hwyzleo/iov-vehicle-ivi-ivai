package net.hwyz.iov.vehicle.ivi.ivai.agent.router

/**
 * Input normalization for tiered routing (CR-005): full-width → half-width,
 * punctuation unified, whitespace stripped, plus negation / multi-intent flag
 * detection. Failing toward L1/L3 (never direct execution) on ambiguous flags.
 */
object TextNormalizer {

    fun normalize(raw: String): NormalizedInput {
        val original = raw.trim()
        var s = original
        s = fullToHalfWidth(s)
        s = s.lowercase()
        s = s.replace(Regex("\\s+"), "")
        s = s.replace(Regex("[，。！？、；：,.!?;:]+"), "")
        val hasNegation = NEGATION_PATTERNS.any { s.contains(it) }
        // CR-019：多意图检测以独立谓词/对象/并列结构为依据（MultiIntentDetector）；
        // “再+单一谓词”是 continuation/discourse marker，不再误判为 MULTI_INTENT。
        val hasMultiIntent = MultiIntentDetector.isMultiIntent(s)
        return NormalizedInput(
            original = original,
            normalized = s,
            hasNegation = hasNegation,
            hasMultiIntent = hasMultiIntent
        )
    }

    /** Full-width (Ｆｕｌｌ１２３，。ｃｆ) → half-width (Full123,.cf). */
    private fun fullToHalfWidth(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            sb.append(
                when {
                    code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar()
                    code == 0x3000 -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private val NEGATION_PATTERNS = listOf(
        "别打开", "别把", "别开", "别关", "别调",
        "不要打开", "不要开", "不要关", "不要调", "不要",
        "不用", "勿", "禁止", "不允许", "别让"
    )
}
