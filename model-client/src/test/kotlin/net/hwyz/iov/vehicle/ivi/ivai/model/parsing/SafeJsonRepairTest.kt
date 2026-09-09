package net.hwyz.iov.vehicle.ivi.ivai.model.parsing

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-016 单元测试：SafeJsonRepair（受控 JSON 修复）。
 *
 * 覆盖设计测试设计：
 *  - SafeJsonRepair 只修复允许的语法包装且最多执行一次；
 *  - 代码围栏 / 固定前后缀剥离；
 *  - 尾逗号修复；
 *  - 不引入新语义（不新增 Tool ID / 参数键 / 参数值）；
 *  - 无法安全修复 → Failed（IVAI-MODEL-REPAIR-001）。
 */
class SafeJsonRepairTest {

    private val validJson = """{"route":"LOCAL_TOOL","intents":[]}"""

    @Test
    fun `合法 JSON 直接通过不修复`() {
        val result = SafeJsonRepair.repairAndParse(validJson)
        assertInstanceOf(RepairResult.AlreadyValid::class.java, result)
    }

    @Test
    fun `代码围栏 JSON 被剥离后解析`() {
        val result = SafeJsonRepair.repairAndParse("```json\n$validJson\n```")
        assertInstanceOf(RepairResult.Repaired::class.java, result)
        val repaired = result as RepairResult.Repaired
        assertEquals("code-fence-stripped", repaired.reason)
    }

    @Test
    fun `无语言标记代码围栏被剥离`() {
        val result = SafeJsonRepair.repairAndParse("```\n$validJson\n```")
        assertInstanceOf(RepairResult.Repaired::class.java, result)
    }

    @Test
    fun `固定前后缀包装被剥离`() {
        val result = SafeJsonRepair.repairAndParse("好的，结果如下：\n$validJson")
        assertInstanceOf(RepairResult.Repaired::class.java, result)
        val repaired = result as RepairResult.Repaired
        assertEquals("envelope-stripped", repaired.reason)
    }

    @Test
    fun `尾逗号被修复`() {
        val result = SafeJsonRepair.repairAndParse("""{"route":"LOCAL_TOOL","intents":[],}""")
        assertInstanceOf(RepairResult.Repaired::class.java, result)
        val repaired = result as RepairResult.Repaired
        assertEquals("trailing-comma-fixed", repaired.reason)
        // 修复不引入新键。
        val obj = repaired.element.jsonObject
        assertEquals(setOf("route", "intents"), obj.keys)
    }

    @Test
    fun `完全非 JSON 无法修复`() {
        val result = SafeJsonRepair.repairAndParse("this is { not json")
        assertEquals(RepairResult.Failed, result)
    }

    @Test
    fun `修复最多执行一次且不改变语义`() {
        val result = SafeJsonRepair.repairAndParse("""{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power.set","arguments":{"enabled":true}}],}""")
        assertInstanceOf(RepairResult.Repaired::class.java, result)
        val obj = (result as RepairResult.Repaired).element.jsonObject
        assertEquals("LOCAL_TOOL", obj["route"]?.toString()?.let { it.substringAfter("\"").substringBefore("\"") })
        val intents = obj["intents"]?.toString()
        assertTrue(intents!!.contains("climate.power.set"))
        assertTrue(intents.contains("\"enabled\":true"))
    }

    @Test
    fun `ConstrainedModelResponseParser 缺顶层字段返回结构错误`() {
        val parsed = ConstrainedModelResponseParser.parse(
            """{"foo":"bar"}""",
            expectedTopLevelFields = setOf("route")
        )
        assertInstanceOf(ParsedModelResponse.Failed::class.java, parsed)
        assertTrue((parsed as ParsedModelResponse.Failed).structureInvalid)
    }

    @Test
    fun `ConstrainedModelResponseParser 顶层非对象返回结构错误`() {
        val parsed = ConstrainedModelResponseParser.parse("[1,2,3]")
        assertInstanceOf(ParsedModelResponse.Failed::class.java, parsed)
        assertTrue((parsed as ParsedModelResponse.Failed).structureInvalid)
    }

    @Test
    fun `ConstrainedModelResponseParser 代码围栏响应成功修复解析`() {
        val parsed = ConstrainedModelResponseParser.parse(
            "```json\n{\"route\":\"LOCAL_TOOL\",\"intents\":[]}\n```",
            expectedTopLevelFields = setOf("route")
        )
        assertInstanceOf(ParsedModelResponse.Success::class.java, parsed)
        assertTrue((parsed as ParsedModelResponse.Success).repaired)
    }
}
