package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite

/**
 * 测试 Suite 解析器（IVI-IVAI-DSN-CR-015）。
 *
 * 导入与内置加载共用同一解析器与 Schema（不维护第二套宽松格式）。可选字段
 * （description / tags / governanceVersion 等）缺失保持向后兼容。
 *
 * 两段式解析以区分错误码：
 *  1. Json.parseToJsonElement —— UTF-8 / JSON 语法（失败 → IVAI-TEST-IMPORT-003）
 *  2. decodeFromJsonElement<AgentTestSuite> —— Schema / 枚举 / 字段类型（失败 → IVAI-TEST-IMPORT-005）
 */
class AgentTestSuiteParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    /** 字节入口（导入路径，UTF-8）。 */
    fun parse(bytes: ByteArray): AgentTestSuite =
        parse(String(bytes, Charsets.UTF_8))

    /** 文本入口（内置加载路径）。 */
    fun parse(raw: String): AgentTestSuite {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw SuiteImportException(
                TestErrorCode.IMPORT_INVALID_JSON,
                "JSON 语法或字符编码非法：${e.message}"
            )
        }
        return try {
            json.decodeFromJsonElement(AgentTestSuite.serializer(), element)
        } catch (e: Exception) {
            throw SuiteImportException(
                TestErrorCode.IMPORT_VALIDATION_FAILED,
                "Suite Schema 或字段类型非法：${e.message}"
            )
        }
    }
}
