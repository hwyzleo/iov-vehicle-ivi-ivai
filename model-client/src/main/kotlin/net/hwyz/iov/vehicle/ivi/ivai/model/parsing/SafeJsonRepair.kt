package net.hwyz.iov.vehicle.ivi.ivai.model.parsing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型结构化输出的安全修复器（IVI-IVAI-DSN-CR-016）。
 *
 * 只允许修复「语法/包装」问题，最多执行一次，且**不得引入任何新语义**：
 *  - 不允许新增 Tool ID、参数键或参数值；
 *  - 不允许删除、重排或改写已有键值；
 *  - 只处理 JSON 语法层的引号、尾逗号、代码围栏或固定前后缀包装。
 *
 * 修复后调用方必须重新执行全部 Schema、候选集、Policy 与安全校验
 * （IVAI-MODEL-REPAIR-001：无法安全修复或修复后仍不合法）。
 */
object SafeJsonRepair {

    private val strictJson = Json { ignoreUnknownKeys = false }

    /**
     * 尝试修复并解析模型返回的 content。
     *
     * @return 修复成功返回 [RepairResult.Repaired]（含修复后解析的 JsonElement）；
     *   输入本就是合法 JSON 返回 [RepairResult.AlreadyValid]；无法安全修复返回
     *   [RepairResult.Failed]。
     */
    fun repairAndParse(content: String): RepairResult {
        val trimmed = content.trim()
        // 已是合法 JSON：直接解析（严格模式，发现未知结构留给调用方校验）。
        parseOrNull(trimmed)?.let { return RepairResult.AlreadyValid(it) }

        // 1) 代码围栏（```json ... ``` / ``` ... ```）。
        val fence = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)
        if (fence != null) {
            val inner = fence.groupValues[1].trim()
            parseOrNull(inner)?.let { return RepairResult.Repaired(it, "code-fence-stripped") }
        }

        // 2) 固定前后缀包装（如 {"result": ...} 或前后多余文本）。只在剥离后整体是
        //    合法 JSON 时接受，剥离范围取内容中最外层 JSON 对象/数组。
        val outer = firstJsonObjectOrArray(trimmed)
        if (outer != null && outer != trimmed) {
            parseOrNull(outer)?.let { return RepairResult.Repaired(it, "envelope-stripped") }
        }

        // 3) 单次语法修复：尾逗号 + 未转义引号（保守：只替换 \" 前的非法裸引号不处理，
        //    仅修尾逗号与常见小问题）。
        val fixed = fixTrailingCommas(trimmed)
        if (fixed != trimmed) {
            parseOrNull(fixed)?.let { return RepairResult.Repaired(it, "trailing-comma-fixed") }
        }

        return RepairResult.Failed
    }

    /** 修复 JSON 对象/数组中的尾逗号（不改变任何语义）。 */
    fun fixTrailingCommas(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when {
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    c == ',' && i + 1 < text.length && (text[i + 1] == '}' || text[i + 1] == ']') -> {
                        // 尾逗号：跳过，不追加。
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    /** 提取文本中第一个完整的 JSON 对象或数组（跨字符串感知，不改变内容）。 */
    private fun firstJsonObjectOrArray(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when {
                c == '"' -> inString = true
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        return text.substring(start, end + 1)
    }

    private fun parseOrNull(text: String): JsonElement? =
        try {
            strictJson.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
}

/** 修复结果（IVI-IVAI-DSN-CR-016）。 */
sealed interface RepairResult {
    /** 输入直接就是合法 JSON（未修复）。 */
    data class AlreadyValid(val element: JsonElement) : RepairResult

    /** 执行了一次受控修复，[reason] 描述修复类型。 */
    data class Repaired(val element: JsonElement, val reason: String) : RepairResult

    /** 无法安全修复（IVAI-MODEL-REPAIR-001）。 */
    data object Failed : RepairResult
}
