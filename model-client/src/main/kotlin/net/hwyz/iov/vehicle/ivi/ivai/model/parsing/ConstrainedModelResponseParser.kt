package net.hwyz.iov.vehicle.ivi.ivai.model.parsing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 受控模型响应解析器（IVI-IVAI-DSN-CR-016）。
 *
 * 处理链（设计结论）：
 * ```plain text
 * Provider Response
 * → content presence check
 * → strict JSON parse
 * → safe envelope strip（仅代码围栏/固定前后缀）
 * → strict parse
 * → optional one JSON repair
 * → top-level schema validation
 * ```
 *
 * 候选集成员校验 / 旧 ID canonicalization / 参数 canonicalization / Candidate
 * Boundary 由 agent-core 装配方（AgentWorkflow）在拿到本解析结果后完成——本解析器
 * 只负责把模型 content 安全、受控地变成合法 JsonElement，不引入任何新语义。
 *
 * 修复最多一次（[SafeJsonRepair]）；修复后顶层结构仍不合法 →
 * [ParsedModelResponse.Failed]（IVAI-MODEL-REPAIR-001）。
 */
object ConstrainedModelResponseParser {

    private val strictJson = Json { ignoreUnknownKeys = false }

    /**
     * 解析模型响应内容。
     *
     * @param content 模型返回的原始 content（可能包含代码围栏 / 包装文本 / 语法瑕疵）
     * @param expectedTopLevelFields 顶层必须包含的字段（如 route / intents）；为 null
     *   时只要求顶层是合法 JSON 对象。
     */
    fun parse(content: String, expectedTopLevelFields: Set<String>? = null): ParsedModelResponse {
        // 1) content presence check。
        if (content.isBlank()) {
            return ParsedModelResponse.Failed(
                errorCode = MODEL_REPAIR_FAILED,
                message = "模型返回内容为空",
                structureInvalid = false
            )
        }
        // 2) strict parse；失败走 3) envelope strip → 4) strict parse；仍失败走
        //    5) 一次 safe repair。
        val element: JsonElement
        val repairInfo: String?
        when (val direct = SafeJsonRepair.repairAndParse(content)) {
            is RepairResult.AlreadyValid -> {
                element = direct.element
                repairInfo = null
            }
            is RepairResult.Repaired -> {
                element = direct.element
                repairInfo = direct.reason
            }
            RepairResult.Failed -> {
                return ParsedModelResponse.Failed(
                    errorCode = MODEL_REPAIR_FAILED,
                    message = "模型返回内容无法安全修复为合法 JSON",
                    structureInvalid = false
                )
            }
        }

        // 6) top-level schema validation：必须是 JSON 对象，且包含要求的顶层字段。
        //    合法 JSON 但结构不符合 AgentOutput → structureInvalid（调用方映射为
        //    IVAI-SCHEMA-001 / OUTPUT_SCHEMA，区别于无法解析的修复失败）。
        val obj = element.jsonObjectOrNull()
        if (obj == null) {
            return ParsedModelResponse.Failed(
                errorCode = STRUCTURE_INVALID,
                message = "模型返回 JSON 顶层不是对象",
                structureInvalid = true
            )
        }
        if (expectedTopLevelFields != null) {
            val missing = expectedTopLevelFields.filter { !obj.containsKey(it) }
            if (missing.isNotEmpty()) {
                return ParsedModelResponse.Failed(
                    errorCode = STRUCTURE_INVALID,
                    message = "模型返回 JSON 缺少顶层字段：${missing.joinToString(",")}",
                    structureInvalid = true
                )
            }
        }

        return ParsedModelResponse.Success(
            element = obj,
            repaired = repairInfo != null,
            repairReason = repairInfo
        )
    }

    private fun JsonElement.jsonObjectOrNull() =
        try {
            this as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        }

    private const val MODEL_REPAIR_FAILED = "IVAI-MODEL-REPAIR-001"
    private const val STRUCTURE_INVALID = "IVAI-SCHEMA-001"
}

/** 解析结果（IVI-IVAI-DSN-CR-016）。 */
sealed interface ParsedModelResponse {
    /** 解析成功；[repaired] 指示是否执行过一次受控修复。 */
    data class Success(
        val element: kotlinx.serialization.json.JsonObject,
        val repaired: Boolean = false,
        val repairReason: String? = null
    ) : ParsedModelResponse

    /**
     * 解析失败。
     * [structureInvalid] 为 true 时表示 JSON 合法但结构不满足 AgentOutput
     * （调用方映射为 IVAI-SCHEMA-001）；false 表示无法安全解析/修复
     * （IVAI-MODEL-REPAIR-001 / 汇总码 IVAI-MODEL-002）。
     */
    data class Failed(
        val errorCode: String,
        val message: String,
        val structureInvalid: Boolean = false
    ) : ParsedModelResponse
}
