package net.hwyz.iov.vehicle.ivi.ivai.agent.router

/**
 * 多意图检测器（IVI-IVAI-DSN-CR-019）。
 *
 * 必须以「独立谓词、独立对象与并列结构」为依据，不得把 discourse/continuation
 * marker 误判为多意图：
 *  - “主驾温度再调高1度” → 单一 relative adjust（“再”是 continuation marker）；
 *  - “打开空调，再把副驾温度调到24度” → 多意图（两个子句各含独立谓词+对象）。
 *
 * 规则：
 *  1. 逗号/分号等分隔的多子句中，含独立动作谓词的子句数 ≥ 2 → 多意图；
 *  2. 单子句中命中多个不同动作谓词且存在独立对象 → 多意图；
 *  3. “再”不作为多意图的独立依据（仅当其后的谓词与前一谓词构成新的独立动作
 *    对时才计入，见子句规则）。
 */
object MultiIntentDetector {

    /** 动作谓词（与归一化输入一致，无标点/空白）。 */
    private val ACTION_VERBS = listOf(
        "打开", "开启", "关闭", "调到", "设为", "设成", "调成",
        "调高", "调低", "升温", "降温", "升高", "降低", "关掉"
    )

    /** 子句分隔符（逗号/分号/顿号/句号）。 */
    private val CLAUSE_SEPARATOR = Regex("[，,。；;、]+")

    /** 并列连接词（不含“再”——由子句/对象结构判定）。 */
    private val CONJUNCTIONS = listOf("然后", "接着", "并且", "同时", "也", "以及")

    fun isMultiIntent(text: String): Boolean {
        if (text.isBlank()) return false

        val clauses = text.split(CLAUSE_SEPARATOR).filter { it.isNotBlank() }
        // 规则 1：多个子句且 ≥2 个子句含独立动作谓词 → 多意图。
        if (clauses.size > 1) {
            val actionClauses = clauses.count { hasActionVerb(it) }
            if (actionClauses >= 2) return true
        }

        // 规则 2：单子句命中多个不同动作谓词且存在并列/独立对象。
        val verbs = ACTION_VERBS.filter { text.contains(it) }
        if (verbs.size >= 2) {
            // 独立对象（空调/风量/车窗等）出现 ≥2 个不同对象词，或存在并列连接词。
            val objectCount = OBJECT_WORDS.count { text.contains(it) }
            if (objectCount >= 2 || CONJUNCTIONS.any { text.contains(it) }) return true
        }
        return false
    }

    private fun hasActionVerb(clause: String): Boolean = ACTION_VERBS.any { clause.contains(it) }

    private val OBJECT_WORDS = listOf(
        "空调", "风量", "风速", "温度", "车窗", "天窗", "座椅",
        "通风", "出风", "除霜", "灯光", "音乐", "音量"
    )
}
