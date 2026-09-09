package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasResolver
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.TemperatureBoundLexicon
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.VehicleTemperatureTopology

/**
 * 温度意图语义标准化器（IVI-IVAI-DSN-CR-019 目标链路第 2 环节）。
 *
 * Normalized Input → object + operation + value semantics + zone →
 * [TemperatureOperationSemantic]（RELATIVE_DELTA / ABSOLUTE_TARGET /
 * BOUND_TARGET / OUT_OF_RANGE / AMBIGUOUS）。
 *
 * 判定规则（CR-019 设计「温度语义标准化」）：
 *  - “提高/降低/升高/减少/加/减 + N度” → RELATIVE_DELTA → climate.temperature.adjust；
 *  - “调到/设为/目标/保持在 + 16～30℃” → ABSOLUTE_TARGET → climate.temperature.set；
 *  - “最高/最大/最热/最低/最小/最冷” → BOUND_TARGET，由版本化车型边界转换；
 *  - 绝对目标超出车型温度范围 → OUT_OF_RANGE（拒绝执行）；
 *  - 数值缺少单位或动作对象无法判断 → AMBIGUOUS（追问）；
 *  - “空调”只形成 HVAC 对象证据，不得直接提升 power.set；“温度+数值”不得仅因
 *    数值提升 fan.speed.set。
 *
 * 语义判定只产生证据，不决定 Tool；Tool 决策由 FastIntentMatcher / RAG 排序消费。
 */
class TemperatureIntentSemanticNormalizer(
    private val topologyResolver: (String?) -> VehicleTemperatureTopology =
        { VehicleTemperatureTopology.forVehicle(it) },
    private val positionResolver: PositionAliasResolver = PositionAliasResolver()
) {

    /** 相对增减动词（direction=increase）。 */
    private val relativeUpVerbs = listOf("调高", "升高", "提高", "增加", "升温", "调大", "增大", "加大", "加")

    /** 相对增减动词（direction=decrease）。 */
    private val relativeDownVerbs = listOf("调低", "降低", "减少", "降温", "调小", "减小", "减")

    /** 绝对目标动词（含“升到/降到”——“温度升到26度”是绝对目标而非相对增减）。 */
    private val absoluteVerbs = listOf("调到", "设为", "设成", "设置成", "调成", "目标", "保持在", "设置到", "调节到", "升到", "降到")

    /** 定性幅度词典版本 / 边界词典版本 / 位置词典版本（请求快照来源版本）。 */
    private val lexiconVersions = setOf(
        TemperatureDeltaAliasCatalog.GOVERNANCE_VERSION,
        TemperatureBoundLexicon.GOVERNANCE_VERSION,
        "vehicle_position_v2"
    )

    /**
     * @param text 归一化后的用户输入（不含标点/空白，小写）。
     * @param vehicleModel 当前车型（车型温度拓扑；null 用默认 16..30）。
     */
    fun normalize(text: String, vehicleModel: String? = null): TemperatureSemanticEvidence {
        // CR-019：仅对温度相关请求产出语义证据。非温度请求（如“风量调到5档”“打开空调”）
        // 返回 evidence 空，调用方（FastIntentMatcher / RAG Boost）据此跳过温度干预。
        if (!temperatureRelevant(text)) {
            return TemperatureSemanticEvidence(
                operation = TemperatureOperationSemantic.AMBIGUOUS,
                evidence = emptyList(),
                lexiconVersions = lexiconVersions
            )
        }
        val topology = topologyResolver(vehicleModel)
        val evidence = mutableListOf<String>()

        // 1) zone 证据（无歧义时收敛为唯一 canonical zone）。
        val zone = positionResolver.resolve(text, vehicleModel).singleZone

        // 2) 边界词（最高/最大/最热/最低/最小/最冷）。
        val bound = TemperatureBoundLexicon.findIn(text)
        if (bound != null) evidence += "bound:${bound.name}"

        // 3) 定性幅度（一丢丢/半度/一点/一些/明显一些）。
        val deltaPhrase = TemperatureDeltaAliasCatalog.findIn(text)
        if (deltaPhrase != null) evidence += "delta:${deltaPhrase.phrase}=${deltaPhrase.canonicalStep}"

        // 4) 数值（带单位优先）。
        val withUnit = NUMBER_WITH_UNIT.find(text)
        val bare = if (withUnit == null) NUMBER_BARE.find(text) else null
        val number = withUnit?.groupValues?.get(1)?.toDoubleOrNull()
            ?: bare?.groupValues?.get(1)?.toDoubleOrNull()
        val hasUnit = withUnit != null
        if (withUnit != null) evidence += "number:${withUnit.groupValues[1]}度"
        else if (bare != null) evidence += "number-bare:${bare.groupValues[1]}"

        // 5) 方向 + 相对/绝对动词。
        val direction = directionOf(text)
        val absoluteHit = absoluteVerbs.any { text.contains(it) }
        val relativeHit = relativeUpVerbs.any { text.contains(it) } ||
            relativeDownVerbs.any { text.contains(it) }
        if (absoluteHit) evidence += "absolute-verb"
        if (relativeHit) evidence += "relative-verb"
        if (direction != null) evidence += "direction:${direction}"

        // 6) 语义判定（顺序敏感：绝对 > 相对 > 边界 > 仅数值）。
        // 6a) 绝对目标（含“调到N度”模式）：
        //     “温度调高到24度”这类“相对动词+到+绝对数值”归入绝对目标。
        val absoluteVerbHit = absoluteHit || RELATIVE_TO_ABSOLUTE.containsMatchIn(text)
        if (absoluteVerbHit) {
            if (bound != null) {
                // 调到最高/最低 → 边界目标（版本化车型边界转换，禁止硬编码 16/30）。
                val value = topology.boundValue(bound)
                return evidenceResult(
                    TemperatureOperationSemantic.BOUND_TARGET, zone, null, null,
                    value, bound, evidence
                )
            }
            if (number != null && hasUnit) {
                if (topology.inRange(number)) {
                    return evidenceResult(
                        TemperatureOperationSemantic.ABSOLUTE_TARGET, zone, null, null,
                        number, null, evidence
                    )
                }
                return evidenceResult(
                    TemperatureOperationSemantic.OUT_OF_RANGE, zone, null, null,
                    number, null, evidence
                )
            }
            // 无数值或数值缺少单位（“调到5”）→ 追问。
            return evidenceResult(TemperatureOperationSemantic.AMBIGUOUS, zone, null, null, number, null, evidence)
        }

        // 6b) 相对增减：direction 由动词决定；step 由数值（带单位或 0.5..5 小值）或定性幅度获得。
        if (relativeHit) {
            val step = deltaPhrase?.canonicalStep
                ?: if (number != null && (hasUnit || number in 0.5..5.0)) number else null
            return evidenceResult(
                TemperatureOperationSemantic.RELATIVE_DELTA, zone, direction, step,
                null, null, evidence
            )
        }

        // 6c) 边界目标（无绝对/相对动词，如“温度最高”）。
        if (bound != null) {
            val value = topology.boundValue(bound)
            return evidenceResult(
                TemperatureOperationSemantic.BOUND_TARGET, zone, null, null,
                value, bound, evidence
            )
        }

        // 6d) 只有数值或对象而无动作（“24度”“空调温度”）→ 追问。
        return evidenceResult(TemperatureOperationSemantic.AMBIGUOUS, zone, null, null, number, null, evidence)
    }

    private fun evidenceResult(
        operation: TemperatureOperationSemantic,
        zone: String?,
        direction: String?,
        step: Double?,
        temperature: Double?,
        bound: TemperatureBound?,
        evidence: List<String>
    ): TemperatureSemanticEvidence = TemperatureSemanticEvidence(
        operation = operation,
        zone = zone,
        direction = direction,
        step = step,
        temperature = temperature,
        bound = bound,
        evidence = evidence,
        lexiconVersions = lexiconVersions
    )

    /**
     * 温度相关信号（CR-019）：
     *  - 显式“温度”对象；
     *  - 温度单位（℃/摄氏度/度，排除“档”）；
     *  - 边界词；
     *  - 强温度动词（升温/降温/升高温度/降低温度/提高温度）。
     * 定性幅度词（一点/一丢丢）不单独触发（“我有点冷”“风量调高一点”不是温度请求）；
     * “调高/调低”单独不算（“风量调高”是 fan.adjust，非温度）。
     */
    private fun temperatureRelevant(text: String): Boolean =
        text.contains("温度") ||
            text.contains("℃") ||
            text.contains("摄氏度") ||
            TemperatureBoundLexicon.findIn(text) != null ||
            STRONG_TEMP_VERBS.any { text.contains(it) } ||
            (text.contains("度") && !text.contains("档"))

    private fun directionOf(text: String): String? {
        val up = relativeUpVerbs.firstOrNull { text.contains(it) }
        val down = relativeDownVerbs.firstOrNull { text.contains(it) }
        return when {
            up != null && down != null -> null // 同时命中视为歧义，由 AMBIGUOUS 兜底
            up != null -> "increase"
            down != null -> "decrease"
            else -> null
        }
    }

    private companion object {
        /** 强温度动词（与“温度”对象/单位绑定；排除“调高/调低”防“风量调高”误判）。 */
        val STRONG_TEMP_VERBS = listOf("升温", "降温", "升高温度", "降低温度", "提高温度")

        /** 带单位数值：24度 / 24℃ / 24摄氏度。 */
        val NUMBER_WITH_UNIT = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*(?:度|℃|摄氏度)")

        /** 裸数值。 */
        val NUMBER_BARE = Regex("(\\d{1,3}(?:\\.\\d+)?)")

        /** “调高到24度”等相对动词+到+绝对数值 → 绝对目标。 */
        val RELATIVE_TO_ABSOLUTE = Regex("(调高|调低|升高|降低|提高|减少)到\\s*\\d")
    }
}
