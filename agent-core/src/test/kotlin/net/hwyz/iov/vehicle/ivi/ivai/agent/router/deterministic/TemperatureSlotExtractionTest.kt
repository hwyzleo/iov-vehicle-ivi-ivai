package net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.DefaultAliasLexicons
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 温度槽位提取（IVI-IVAI-DSN-CR-019 单元测试）。
 *
 *  - TEMPERATURE 槽位支持边界 Alias（最高/最低 → 车型上下限，禁止硬编码 16/30）；
 *  - STEP 槽位支持 0.5 步进与定性幅度词典（一丢丢=0.5 等）。
 */
class TemperatureSlotExtractionTest {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val extractor = SchemaAwareSlotExtractor(registry, DefaultAliasLexicons.DEFAULT)

    private val setRule = DeterministicIntentRule(
        ruleId = "L0.temp.set",
        toolId = "climate.temperature.set",
        exactPhrases = listOf("温度调到"),
        slotPatterns = listOf(
            SlotPattern(name = "temperature", type = SlotType.TEMPERATURE, required = true),
            SlotPattern(name = "zone", type = SlotType.POSITION, required = true)
        )
    )

    private val adjustRule = DeterministicIntentRule(
        ruleId = "L0.temp.adjust",
        toolId = "climate.temperature.adjust",
        exactPhrases = listOf("温度调高"),
        slotPatterns = listOf(
            SlotPattern(name = "zone", type = SlotType.POSITION, required = true),
            SlotPattern(name = "step", type = SlotType.STEP, required = false)
        )
    )

    @Test
    fun `边界Alias转换为车型上下限`() {
        val max = extractor.extract(setRule, "climate.temperature.set", "主驾温度调到最高", "demo")
        val temp = max.arguments.firstOrNull { it.name == "temperature" }
        assertNotNull(temp)
        assertEquals(30.0, temp!!.rawValue) // 车型默认上限，非硬编码

        val min = extractor.extract(setRule, "climate.temperature.set", "温度调到最低", "demo")
        assertEquals(16.0, min.arguments.firstOrNull { it.name == "temperature" }!!.rawValue)
    }

    @Test
    fun `常规带单位温度提取`() {
        val result = extractor.extract(setRule, "climate.temperature.set", "主驾温度调到24度", "demo")
        assertEquals(24.0, result.arguments.firstOrNull { it.name == "temperature" }!!.rawValue)
        assertEquals("driver", result.arguments.firstOrNull { it.name == "zone" }!!.rawValue)
    }

    @Test
    fun `定性幅度映射为 canonical step`() {
        val result = extractor.extract(adjustRule, "climate.temperature.adjust", "主驾温度调高一丢丢", "demo")
        assertEquals(0.5, result.arguments.firstOrNull { it.name == "step" }!!.rawValue)
    }

    @Test
    fun `零点五度步进保留浮点`() {
        val result = extractor.extract(adjustRule, "climate.temperature.adjust", "主驾温度调高0.5度", "demo")
        assertEquals(0.5, result.arguments.firstOrNull { it.name == "step" }!!.rawValue)
    }

    @Test
    fun `整度步进保留整数`() {
        val result = extractor.extract(adjustRule, "climate.temperature.adjust", "主驾温度调高2度", "demo")
        assertEquals(2, result.arguments.firstOrNull { it.name == "step" }!!.rawValue)
    }

    @Test
    fun `无温度值不提取`() {
        val result = extractor.extract(setRule, "climate.temperature.set", "温度调到", "demo")
        assertNull(result.arguments.firstOrNull { it.name == "temperature" })
    }
}
