package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-019 单元测试：温度语义排序规则（Tool RAG 混合加权）。
 *
 * 排序规则（设计「Tool Catalog 与 RAG 边界」）：
 *  - relative action + delta/step → boost temperature.adjust；
 *  - absolute action + legal temperature → boost temperature.set；
 *  - bound alias → boost temperature.set；
 *  - absolute out-of-range → block executable candidate（大幅惩罚）；
 *  - HVAC + temperature → 对象证据仅温度 Tool，power.set 不得被抢占；
 *  - number without semantic role → 不提升 fan/power。
 */
class GovernedBoostCr019Test {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val boost = GovernedBoost()

    private val ids = listOf(
        "climate.temperature.adjust", "climate.temperature.set",
        "climate.power.set", "climate.fan.speed.set", "climate.fan.speed.adjust"
    )

    private fun scoreOf(text: String, toolId: String): GovernedBoost.BoostedResult {
        val tool = registry.get(toolId)!!
        return boost.boost(tool, ToolRetrievalQuery(text = text, vehicleModel = "demo"), baseScore = 0.5)
    }

    private fun topTool(text: String): String = ids.maxBy { scoreOf(text, it).finalScore }

    @Test
    fun `相对调温提升 temperature_adjust`() {
        assertEquals("climate.temperature.adjust", topTool("主驾温度调高1度"))
        val adjust = scoreOf("主驾温度调高1度", "climate.temperature.adjust")
        assertTrue(adjust.matchedFields.any { it == "temp:relative-delta" }, "应记录 temp:relative-delta 证据")
        assertTrue(adjust.breakdown.operationBoundaryBoost > 0)
    }

    @Test
    fun `合法绝对目标提升 temperature_set`() {
        assertEquals("climate.temperature.set", topTool("温度调到24度"))
        val set = scoreOf("温度调到24度", "climate.temperature.set")
        assertTrue(set.matchedFields.any { it == "temp:absolute_target" })
    }

    @Test
    fun `边界Alias提升 temperature_set`() {
        assertEquals("climate.temperature.set", topTool("温度调到最高"))
        val set = scoreOf("温度调到最高", "climate.temperature.set")
        assertTrue(set.matchedFields.any { it == "temp:bound_target" })
    }

    @Test
    fun `越界绝对温度 block temperature_set 可执行位`() {
        val set = scoreOf("温度调到35度", "climate.temperature.set")
        assertTrue(set.matchedFields.any { it == "temp:out-of-range-block" })
        assertTrue(set.breakdown.negativeBoundaryPenalty > 0, "越界绝对目标必须大幅惩罚")
    }

    @Test
    fun `空调温度不得被 power_set 抢占`() {
        val power = scoreOf("空调温度调到24度", "climate.power.set")
        assertTrue(power.matchedFields.any { it == "temp:object-evidence-only" }, "HVAC 对象证据仅温度 Tool")
        assertTrue(power.breakdown.negativeBoundaryPenalty > 0, "power.set 应受温度语义惩罚")
        assertEquals("climate.temperature.set", topTool("空调温度调到24度"))
    }

    @Test
    fun `温度加数值不得提升 fan_speed`() {
        val fan = scoreOf("温度调到24度", "climate.fan.speed.set")
        assertTrue(fan.matchedFields.any { it == "temp:no-fan-boost" }, "温度+数值不得提升 fan.speed.set")
        assertTrue(fan.breakdown.operationBoundaryBoost == 0.0, "绝对档位证据不得作用于 fan")
        assertEquals("climate.temperature.set", topTool("温度调到24度"))
    }

    @Test
    fun `非温度查询保持既有 fan 行为`() {
        assertEquals("climate.fan.speed.set", topTool("风量调到5档"))
        assertEquals("climate.fan.speed.adjust", topTool("风量调大一点"))
    }
}
