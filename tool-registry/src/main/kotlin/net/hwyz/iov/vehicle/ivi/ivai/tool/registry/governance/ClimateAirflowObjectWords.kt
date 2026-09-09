package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * 座舱气流对象 → 触发词映射（IVI-IVAI-DSN-CR-018，治理单一事实源）。
 *
 * 对象名与 CabinAirflowSemanticLexicon（agent-core）的 SemanticObject 一一对应；
 * Domain Router、Tool RAG 混合排序与模型 Prompt 均从本映射取词，禁止分别维护
 * 词表副本（同义词、规则与模型 Prompt 统一治理）。
 *
 * “吹风/出风”仅作为对象证据（VENT），不得单独成为任一 Tool 的确定性正例。
 */
object ClimateAirflowObjectWords {

    const val HVAC_SYSTEM = "HVAC_SYSTEM"
    const val VENT = "VENT"
    const val FAN_SPEED = "FAN_SPEED"
    const val AIRFLOW_DIRECTION = "AIRFLOW_DIRECTION"
    const val AUTO_HVAC = "AUTO_HVAC"

    /** 全部对象 → 触发词（小写后匹配）。 */
    val OBJECT_WORDS: Map<String, Set<String>> = mapOf(
        HVAC_SYSTEM to setOf("空调", "空调系统", "空调电源"),
        VENT to setOf("出风", "吹风", "送风", "风口", "通风口", "出风口"),
        FAN_SPEED to setOf("风量", "风速", "气流"),
        AIRFLOW_DIRECTION to setOf("出风模式", "风向", "吹脸", "吹脚", "吹腿", "除霜", "除雾", "混合"),
        AUTO_HVAC to setOf("自动空调", "AUTO模式", "auto模式")
    )

    /** 对象名集合（与 SemanticObject 名称对齐）。 */
    val OBJECT_NAMES: Set<String> = OBJECT_WORDS.keys

    /** 文本命中哪些对象（返回对象名）。 */
    fun objectsFor(normalized: String): Set<String> {
        val text = normalized.lowercase()
        return OBJECT_WORDS.filterValues { words -> words.any { text.contains(it) } }.keys
    }

    /** 文本是否命中指定对象。 */
    fun hasObject(normalized: String, objectName: String): Boolean =
        objectName in objectsFor(normalized)
}
