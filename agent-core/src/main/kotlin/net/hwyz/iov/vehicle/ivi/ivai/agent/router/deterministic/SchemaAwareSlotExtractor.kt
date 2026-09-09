package net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.CanonicalAliasLexicon
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalSchemaParser

/**
 * Schema 感知的槽位提取器（IVI-IVAI-DSN-CR-016）。
 *
 * 只对当前匹配规则声明的槽位运行，并从 Tool Schema 读取参数名、类型、枚举和范围。
 * 输出带来源的槽位候选（[ExtractedArgument]），再交由统一的
 * ParameterCanonicalizationService 做 canonical 化。
 *
 * 处理规则：
 *  - 数值档位支持阿拉伯数字、批准的中文数字及“档/级”等受控单位后缀
 *    （"5档" / "调到5" / "设为5" → level=5）；
 *  - 位置只使用版本化 Alias Lexicon（主驾/副驾/前排/后排/全车/2排/3排 …）；
 *  - 显式槽位与规则预置/别名映射值冲突时返回 [SlotExtractionResult.conflict]
 *    （ARGUMENT_CONFLICT，IVAI-ROUTE-005），不得以优先级静默覆盖用户明确语义；
 *  - 数值/位置槽位无法按当前 Schema 解析时返回 [DeterministicFallbackReason.SLOT_UNPARSEABLE]。
 */
class SchemaAwareSlotExtractor(
    private val registry: ToolRegistry,
    private val lexicon: CanonicalAliasLexicon
) {

    /**
     * 提取规则声明的全部槽位。
     *
     * @param rule 命中的确定性规则（声明要抽取的槽位）
     * @param toolId 规则所属 Tool（用于读取 Schema 参数名/类型/枚举/范围）
     * @param normalized 归一化后的用户输入
     */
    fun extract(
        rule: DeterministicIntentRule,
        toolId: String,
        normalized: String
    ): SlotExtractionResult {
        val tool = registry.get(toolId)
        if (tool == null) {
            return SlotExtractionResult(
                arguments = emptyList(),
                missing = rule.slotPatterns.filter { it.required }.map { it.name }
            )
        }
        val schema = CanonicalSchemaParser.parse(tool.parameterSchema)
        val propByName = schema.properties.associateBy { it.name }

        val extracted = mutableListOf<ExtractedArgument>()
        val present = mutableSetOf<String>()
        var conflict: String? = null

        for (slot in rule.slotPatterns) {
            val prop = propByName[slot.name]
            // Schema 感知：槽位仍按规则声明提取；Schema 属性用于读取类型/枚举/范围
            // （prop 为 null 时仍提取——规则声明的槽位是 L0 事实源，测试夹具可能
            // 只有规则没有完整 Schema）。
            val result = extractSlot(slot, prop?.type, normalized)
            if (result == null) {
                continue
            }
            val source = if (result.fromAlias) ArgumentSource.ALIAS_MAPPING
            else ArgumentSource.USER_EXPLICIT
            // 与已提取的同一槽位（可能是 Alias 映射）比较：同为用户语义且值冲突。
            val existing = extracted.firstOrNull { it.name == slot.name }
            if (existing != null && existing.rawValue != result.value &&
                existing.source == ArgumentSource.USER_EXPLICIT && source == ArgumentSource.USER_EXPLICIT
            ) {
                conflict = slot.name
            }
            if (existing != null) {
                // 优先级：用户显式 > Alias 映射；同源不同值按冲突处理。
                if (source == ArgumentSource.USER_EXPLICIT &&
                    existing.source != ArgumentSource.USER_EXPLICIT
                ) {
                    extracted.remove(existing)
                    extracted += ExtractedArgument(slot.name, result.value, source, result.range)
                }
            } else {
                extracted += ExtractedArgument(slot.name, result.value, source, result.range)
            }
            present += slot.name
        }

        val missing = rule.slotPatterns
            .filter { it.required && slotNotFilled(it, extracted) }
            .map { it.name }

        return SlotExtractionResult(
            arguments = extracted,
            conflict = conflict,
            missing = missing
        )
    }

    private fun slotNotFilled(slot: SlotPattern, extracted: List<ExtractedArgument>): Boolean {
        val values = extracted.filter { it.name == slot.name }.map { it.rawValue }
        return values.isEmpty() || values.all { it == null || (it as? String)?.isBlank() == true }
    }

    private data class SlotValue(val value: Any?, val fromAlias: Boolean, val range: IntRange? = null)

    private fun extractSlot(slot: SlotPattern, schemaType: String?, normalized: String): SlotValue? =
        when (slot.type) {
            SlotType.TEMPERATURE -> extractTemperature(normalized)
            SlotType.POSITION -> extractPosition(normalized)
            SlotType.STEP -> extractStep(normalized)
            SlotType.NUMERIC -> extractNumeric(normalized)
            SlotType.TIME -> null
        }

    private fun extractTemperature(normalized: String): SlotValue? {
        val withUnit = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*(?:度|℃|摄氏度)").find(normalized)
        if (withUnit != null) {
            return withUnit.groupValues[1].toDoubleOrNull()?.let {
                SlotValue(it, false, withUnit.range)
            }
        }
        val bare = Regex("(\\d{1,3}(?:\\.\\d+)?)").find(normalized)
        return bare?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            SlotValue(it, false, bare.range)
        }
    }

    /**
     * 位置槽位：只使用版本化 Alias Lexicon（含 2排/3排/整车 等批准词）。
     * 命中 → ALIAS_MAPPING 来源；未命中 → null（由调用方决定缺参/降级）。
     */
    private fun extractPosition(normalized: String): SlotValue? {
        val sorted = lexicon.positionAliases.entries.sortedByDescending { it.key.length }
        for ((word, canonical) in sorted) {
            val idx = normalized.indexOf(word)
            if (idx >= 0) {
                return SlotValue(canonical, true, idx..(idx + word.length))
            }
        }
        return null
    }

    /**
     * 步进槽位：阿拉伯数字 + 单位（档/级/度/℃/摄氏度）或中文数字 + 单位；
     * 值域按 Schema 范围（step:[1..5] 等）收敛，越界不作为步进（绝对设定走 NUMERIC）。
     */
    private fun extractStep(normalized: String): SlotValue? {
        val arabic = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:档|级|度|℃|摄氏度)").find(normalized)
        if (arabic != null) {
            val v = arabic.groupValues[1].toDoubleOrNull()?.toInt()
            if (v != null && v in 1..5) return SlotValue(v, false, arabic.range)
        }
        val chinese = Regex("([零一二两三四五六七八九十]{1,3})\\s*(?:档|级|度|℃|摄氏度)").find(normalized)
        if (chinese != null) {
            val v = chineseNumber(chinese.groupValues[1])
            if (v != null && v in 1..5) return SlotValue(v, false, chinese.range)
        }
        return null
    }

    /**
     * 数值槽位：阿拉伯数字；支持“档/级”单位后缀（"5档" → 5）。中文数字用于
     * 档位语义（"调到五档" → 5）。
     */
    private fun extractNumeric(normalized: String): SlotValue? {
        val withUnit = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*(?:档|级)").find(normalized)
        if (withUnit != null) {
            return withUnit.groupValues[1].toDoubleOrNull()?.toInt()?.let {
                SlotValue(it, false, withUnit.range)
            }
        }
        val bare = Regex("(\\d{1,3})").find(normalized)
        if (bare != null) return SlotValue(bare.groupValues[1].toInt(), false, bare.range)
        // 中文数字档位："调到五档"
        val chinese = Regex("([零一二两三四五六七八九十]{1,2})\\s*(?:档|级)").find(normalized)
        if (chinese != null) {
            return chineseNumber(chinese.groupValues[1])?.let {
                SlotValue(it, false, chinese.range)
            }
        }
        return null
    }

    /** 中文数字 1~99 转换（一/两/二/三…十/十一/二十/二十五）。 */
    private fun chineseNumber(s: String): Int? {
        if (s.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s == "十") return 10
        if (s.length == 1) return digits[s[0]]
        val idx = s.indexOf('十')
        if (idx < 0) return null
        val tens = if (idx == 0) 1 else digits[s[0]] ?: return null
        val ones = if (s.length > idx + 1) digits[s[idx + 1]] ?: 0 else 0
        return tens * 10 + ones
    }
}
