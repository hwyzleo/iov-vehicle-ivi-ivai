package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 单元测试：空调相似 Tool 边界混合加权（对象/动作/槽位证据 + 负边界惩罚）。
 *
 * 五类气候查询必须把正确 Tool 排到最前：
 *  power（HVAC_SYSTEM）/ vent（VENT）/ fan.set（绝对档位）/ fan.adjust（相对增减）/
 *  airflow（风向模式）/ auto（AUTO）。
 */
class GovernedBoostCr018Test {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val boost = GovernedBoost()

    private val ids = listOf(
        "climate.power.set", "climate.vent.set", "climate.fan.speed.set",
        "climate.fan.speed.adjust", "climate.airflow.mode.set", "climate.auto.set"
    )

    private fun scoreOf(text: String, toolId: String): GovernedBoost.BoostedResult {
        val tool = registry.get(toolId)!!
        return boost.boost(tool, ToolRetrievalQuery(text = text, vehicleModel = "demo"), baseScore = 0.5)
    }

    private fun topTool(text: String): String =
        ids.maxBy { scoreOf(text, it).finalScore }

    @Test
    fun `打开空调 正确候选为 power_set`() {
        assertEquals("climate.power.set", topTool("打开空调"))
    }

    @Test
    fun `打开通风口 正确候选为 vent_set 且 power_set 被负边界惩罚`() {
        assertEquals("climate.vent.set", topTool("打开通风口"))
        val power = scoreOf("打开通风口", "climate.power.set")
        assertTrue(power.breakdown.negativeBoundaryPenalty > 0, "power.set 命中负例「打开通风口」应受罚")
        val vent = scoreOf("打开通风口", "climate.vent.set")
        assertTrue(vent.breakdown.objectEvidenceBoost > 0)
        assertTrue(vent.breakdown.actionEvidenceBoost > 0)
    }

    @Test
    fun `风量调到5档 正确候选为 fan_speed_set`() {
        assertEquals("climate.fan.speed.set", topTool("风量调到5档"))
        val set = scoreOf("风量调到5档", "climate.fan.speed.set")
        assertTrue(set.breakdown.objectEvidenceBoost > 0, "FAN_SPEED 对象证据")
        assertTrue(set.breakdown.actionEvidenceBoost > 0, "绝对档位动作证据")
    }

    @Test
    fun `风量调大一点 正确候选为 fan_speed_adjust`() {
        assertEquals("climate.fan.speed.adjust", topTool("风量调大一点"))
        val adjust = scoreOf("风量调大一点", "climate.fan.speed.adjust")
        assertTrue(adjust.breakdown.actionEvidenceBoost > 0)
    }

    @Test
    fun `出风模式吹脸 正确候选为 airflow_mode_set`() {
        assertEquals("climate.airflow.mode.set", topTool("出风模式吹脸"))
    }

    @Test
    fun `开启自动空调 正确候选为 auto_set`() {
        assertEquals("climate.auto.set", topTool("开启自动空调"))
        val auto = scoreOf("开启自动空调", "climate.auto.set")
        assertTrue(auto.breakdown.objectEvidenceBoost > 0, "AUTO_HVAC 对象证据")
    }

    @Test
    fun `边界 Tool 排序记录全证据分量 Trace`() {
        for (id in ids) {
            val r = scoreOf("打开通风口", id)
            assertTrue(boost.hasEvidenceTrace(id, r.breakdown), "$id 排序必须记录对象/动作/槽位证据分量")
        }
    }

    @Test
    fun `非气候 Tool 不受边界证据影响`() {
        val media = registry.get("media.playback.play")!!
        val r = boost.boost(media, ToolRetrievalQuery(text = "打开空调"), baseScore = 0.5)
        assertEquals(0.0, r.breakdown.objectEvidenceBoost)
        assertEquals(0.0, r.breakdown.actionEvidenceBoost)
        assertEquals(0.0, r.breakdown.negativeBoundaryPenalty)
    }
}
