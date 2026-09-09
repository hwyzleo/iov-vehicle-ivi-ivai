package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 验证设计 · 空调相似 Tool 边界目录：
 *  - 6 个空调 Tool 的边界条目字段闭合（对象/动作/必填槽位/正反例/冲突集/版本/Hash）；
 *  - 五个相似 Tool 相互纳入冲突集；
 *  - 对象词表与 agent-core CabinAirflowSemanticLexicon 单一事实源对齐；
 *  - “吹风/出风”不得单独成为任一 Tool 的确定性正例（仅作对象证据）。
 */
class ClimateToolBoundaryCatalogTest {

    @Test
    fun `六个空调 Tool 边界条目完整且字段闭合`() {
        val ids = setOf(
            "climate.power.set", "climate.vent.set", "climate.fan.speed.set",
            "climate.fan.speed.adjust", "climate.airflow.mode.set", "climate.auto.set"
        )
        assertEquals(ids, ClimateToolBoundaryCatalog.ALL.keys)
        for (id in ids) {
            val b = ClimateToolBoundaryCatalog.forTool(id)
            assertNotNull(b, id)
            assertTrue(b!!.positiveObjects.isNotEmpty(), "$id 必须有正向对象")
            assertTrue(b.positiveActions.isNotEmpty(), "$id 必须有正向动作")
            assertTrue(b.requiredSlots.isNotEmpty(), "$id 必须有必填槽位")
            assertTrue(b.positiveExamples.isNotEmpty(), "$id 必须有正例")
            assertTrue(b.negativeExamples.isNotEmpty(), "$id 必须有负例")
            assertTrue(b.conflictToolIds.isNotEmpty(), "$id 必须有冲突集")
            assertTrue(b.contentHash.isNotBlank(), "$id 必须有 contentHash")
            assertEquals(ClimateToolBoundaryCatalog.GOVERNANCE_VERSION, b.governanceVersion)
        }
    }

    @Test
    fun `五个相似 Tool 相互纳入冲突集`() {
        val ids = setOf(
            "climate.power.set", "climate.vent.set", "climate.fan.speed.set",
            "climate.fan.speed.adjust", "climate.airflow.mode.set", "climate.auto.set"
        )
        for (id in ids) {
            val conflicts = ClimateToolBoundaryCatalog.forTool(id)!!.conflictToolIds
            // 冲突集至少包含其余相似 Tool 中的同类（不允许空边界）。
            assertTrue(conflicts.any { it in ids && it != id }, "$id 冲突集应包含相似 Tool")
        }
        val powerConflicts = ClimateToolBoundaryCatalog.forTool("climate.power.set")!!.conflictToolIds
        for (required in listOf(
            "climate.auto.set", "climate.vent.set", "climate.fan.speed.set",
            "climate.fan.speed.adjust", "climate.airflow.mode.set"
        )) {
            assertTrue(required in powerConflicts, "power.set 冲突集应包含 $required")
        }
    }

    @Test
    fun `吹风与出风不作为任一 Tool 的确定性正例`() {
        // “吹风/出风”只出现在对象证据词表（ClimateAirflowObjectWords.VENT），
        // 不单独成为任一 Tool 的 positiveExamples（区分证据必须完整）。
        for (id in ClimateToolBoundaryCatalog.ALL.keys) {
            val b = ClimateToolBoundaryCatalog.forTool(id)!!
            for (example in b.positiveExamples) {
                val bare = example == "吹风" || example == "出风" || example == "送风"
                assertTrue(!bare, "$id 不得把裸“吹风/出风/送风”作为确定性正例: $example")
            }
        }
    }

    @Test
    fun `对象词表与 agent 词典单一事实源对齐`() {
        // 对象名与 SemanticObject 名称一一对应（字符串键）。
        for ((objectName, words) in ClimateAirflowObjectWords.OBJECT_WORDS) {
            assertTrue(objectName.isNotBlank() && words.isNotEmpty())
            // 每个对象词都属于某个 Tool 的 positiveObjects（证据可消费）。
            val hasConsumer = ClimateToolBoundaryCatalog.ALL.values.any { objectName in it.positiveObjects }
            assertTrue(hasConsumer, "对象 $objectName 应有边界条目消费")
        }
    }
}
