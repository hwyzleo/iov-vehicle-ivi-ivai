package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.TemperatureOperationSemantic
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-019 单元测试：温度 Tool 检索文档边界（IVAI-TEMP-RAG-001 校验项）。
 *
 * 温度 Tool 的检索文档必须携带操作语义（RELATIVE_DELTA/ABSOLUTE_TARGET/
 * BOUND_TARGET）与数值角色（delta/target/bound），否则无法在 Top-K 中区分
 * adjust/set 与“空调温度”对象证据。
 */
class TemperatureToolRetrievalDocumentTest {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val catalog = GovernanceWorkspace.catalog
    private val builder = ToolRetrievalDocumentBuilder(registry, WorkflowRegistry)

    @Test
    fun `温度 payload 携带操作语义与数值角色`() {
        val set = ToolRetrievalPayloadFactory.fromTool(registry.get("climate.temperature.set")!!)
        assertTrue(set.temperatureOperationSemantics.contains(TemperatureOperationSemantic.ABSOLUTE_TARGET.name))
        assertTrue(set.temperatureOperationSemantics.contains(TemperatureOperationSemantic.BOUND_TARGET.name))
        assertTrue(set.temperatureValueRoles.contains("target"))
        assertTrue(set.temperatureValueRoles.contains("bound"))

        val adjust = ToolRetrievalPayloadFactory.fromTool(registry.get("climate.temperature.adjust")!!)
        assertTrue(adjust.temperatureOperationSemantics.contains(TemperatureOperationSemantic.RELATIVE_DELTA.name))
        assertTrue(adjust.temperatureValueRoles.contains("delta"))
    }

    @Test
    fun `温度文档语义文本渲染操作语义与数值角色`() {
        val toolIds = setOf("climate.temperature.set", "climate.temperature.adjust")
        val docs = builder.buildToolDocuments(
            catalog,
            RuntimeCapabilitySet(
                selectedPackIds = emptySet(),
                runtimeCandidateToolIds = toolIds,
                runtimeCandidateWorkflowIds = emptySet(),
                governanceVersion = "ivai-governance-v1"
            ),
            sourceVersion = "1.0"
        )
        assertEquals(2, docs.size)
        docs.forEach { doc ->
            assertTrue(doc.semanticText.contains("温度操作语义"), "缺少温度操作语义: ${doc.canonicalId}")
            assertTrue(doc.semanticText.contains("温度数值角色"), "缺少温度数值角色: ${doc.canonicalId}")
            assertTrue(doc.contentHash.isNotBlank())
        }
    }

    @Test
    fun `温度正反例与冲突集进入文档`() {
        val adjust = ToolRetrievalPayloadFactory.fromTool(registry.get("climate.temperature.adjust")!!)
        assertTrue("climate.temperature.set" in adjust.conflictingToolIds)
        assertTrue(adjust.positiveExamples.any { it.contains("调高") })
        assertTrue(adjust.negativeBoundaries.any { it.contains("调到") })
    }
}
