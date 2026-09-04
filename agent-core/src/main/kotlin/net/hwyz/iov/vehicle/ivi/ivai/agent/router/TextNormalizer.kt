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
        val actionCount = ACTION_VERBS.count { s.contains(it) }
        val hasMultiIntent = actionCount >= 2 || (actionCount >= 1 && CONJUNCTIONS.any { s.contains(it) })
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

    private val ACTION_VERBS = listOf(
        "打开", "开启", "关闭", "调到", "设为", "设成", "调成",
        "调高", "调低", "升温", "降温", "关掉"
    )

    private val CONJUNCTIONS = listOf("然后", "接着", "并且", "再", "同时", "也", "以及")
}
