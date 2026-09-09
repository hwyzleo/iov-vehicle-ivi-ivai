package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-017 单元测试：混合召回加权（GovernedBoost）。
 *
 * finalScore = vectorScore + approvedAliasExactBoost + slotCoverageBoost +
 * operationBoundaryBoost；命中位置词只提升具备 zone 参数的候选；speed.set 与
 * speed.adjust 仍由绝对/相对动作证据区分。
 */
class GovernedBoostTest {

    private val fanSpeedSet = ToolDefinition(
        toolId = "climate.fan.speed.set",
        functionId = null,
        name = "设置风量档位",
        description = "设置绝对风量档位",
        positiveExamples = listOf("中左风量档位调到5档", "2排风量档位设为5档"),
        negativeExamples = listOf("右边风量调到5", "后面风量调到5"),
        selectionPriority = 1,
        parameterSchema = """{"type":"object","properties":{"zone":{"type":"string","enum":["all","middle_left","second_row"]},"level":{"type":"integer"}},"required":["level"]}""",
        policy = ToolPolicy(),
        execution = ToolExecutionBinding(adapterId = "test", methodId = "test")
    )

    private val fanSpeedAdjust = fanSpeedSet.copy(
        toolId = "climate.fan.speed.adjust",
        name = "调节风量",
        description = "在当前值上增减步长",
        positiveExamples = listOf("风量调大一点", "风量调小2档")
    )

    private val boost = GovernedBoost()

    private fun query(text: String) = ToolRetrievalQuery(text = text, vehicleModel = "demo")

    @Test
    fun `位置 Alias 命中且工具具备 zone 参数时获得 aliasBoost`() {
        val r = boost.boost(fanSpeedSet, query("中左风量调到5档"), baseScore = 0.5)
        assertTrue(r.breakdown.aliasBoost > 0, "中左 应提升具备 zone 参数的候选")
        assertTrue(r.matchedFields.any { it.startsWith("alias:") })
    }

    @Test
    fun `无 zone 参数的候选不得因位置词获得 aliasBoost`() {
        val noZone = fanSpeedSet.copy(
            parameterSchema = """{"type":"object","properties":{"level":{"type":"integer"}},"required":["level"]}"""
        )
        val r = boost.boost(noZone, query("中左风量调到5档"), baseScore = 0.5)
        assertEquals(0.0, r.breakdown.aliasBoost)
    }

    @Test
    fun `分区正例命中提升 slotCoverageBoost`() {
        val r = boost.boost(fanSpeedSet, query("中左风量档位调到5档"), baseScore = 0.5)
        assertTrue(r.breakdown.slotCoverageBoost > 0, "正例命中应提升槽位覆盖")
    }

    @Test
    fun `绝对档位提升 set 候选`() {
        val r = boost.boost(fanSpeedSet, query("风量档位调到5档"), baseScore = 0.5)
        assertTrue(r.breakdown.operationBoundaryBoost > 0, "绝对档位表达应提升 speed.set")
    }

    @Test
    fun `相对增减不提升 set 候选 而提升 adjust 候选`() {
        val rSet = boost.boost(fanSpeedSet, query("风量调大一点"), baseScore = 0.5)
        assertEquals(0.0, rSet.breakdown.operationBoundaryBoost, "相对表达不得提升 set 的操作边界")
        val rAdj = boost.boost(fanSpeedAdjust, query("风量调大一点"), baseScore = 0.5)
        assertTrue(rAdj.breakdown.operationBoundaryBoost > 0, "相对表达应提升 adjust")
    }

    @Test
    fun `finalScore 为各分项之和`() {
        val r = boost.boost(fanSpeedSet, query("中左风量档位调到5档"), baseScore = 0.5)
        // CR-018：finalScore 含对象/动作证据与负边界惩罚（负边界为扣减项）。
        val expected = r.breakdown.vectorScore + r.breakdown.aliasBoost +
            r.breakdown.slotCoverageBoost + r.breakdown.operationBoundaryBoost +
            r.breakdown.objectEvidenceBoost + r.breakdown.actionEvidenceBoost -
            r.breakdown.negativeBoundaryPenalty
        assertEquals(expected, r.finalScore, 1e-9)
    }
}
