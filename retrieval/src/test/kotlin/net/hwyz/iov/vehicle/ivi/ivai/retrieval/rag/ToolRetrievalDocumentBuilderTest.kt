package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RetrievalMetadataKeys
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-011 验证设计 · L1 检索文档构建：canonical 主文档、语义模板、Alias 受控
 * 合并、contentHash 确定性、治理元数据与 Workflow 文档。
 */
class ToolRetrievalDocumentBuilderTest {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val catalog = GovernanceWorkspace.catalog
    private val builder = ToolRetrievalDocumentBuilder(registry, WorkflowRegistry)

    private fun runtimeSet(toolIds: Set<String>, workflowIds: Set<String> = emptySet()) =
        RuntimeCapabilitySet(
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = toolIds,
            runtimeCandidateWorkflowIds = workflowIds,
            governanceVersion = "ivai-governance-v1"
        )

    @Test
    fun `每个候选 Tool 生成一个 canonical 主文档`() {
        val toolIds = catalog.tools.take(5).map { it.toolId }.toSet()
        val docs = builder.buildAll(catalog, runtimeSet(toolIds), sourceVersion = "1.0")
        assertEquals(5, docs.size)
        docs.forEach { doc ->
            assertTrue(doc.canonicalId in toolIds, "canonicalId 必须来自运行时候选集: ${doc.canonicalId}")
            assertEquals(RetrievalAssetType.TOOL, doc.assetType)
            assertTrue(doc.semanticText.contains(doc.title), "语义模板必须包含名称")
            assertTrue(doc.contentHash.isNotBlank())
        }
    }

    @Test
    fun `contentHash 确定性可复现`() {
        val toolIds = catalog.tools.take(5).map { it.toolId }.toSet()
        val first = builder.buildAll(catalog, runtimeSet(toolIds), sourceVersion = "1.0")
        val second = builder.buildAll(catalog, runtimeSet(toolIds), sourceVersion = "1.0")
        assertEquals(
            first.map { it.contentHash },
            second.map { it.contentHash },
            "相同输入必须产出相同 contentHash（增量更新依赖）"
        )
    }

    @Test
    fun `索引元数据携带治理信息供先过滤再检索`() {
        val toolId = catalog.tools.first().toolId
        val docs = builder.buildAll(catalog, runtimeSet(setOf(toolId)), sourceVersion = "1.0")
        val indexed = docs.single().toIndexedDocument()
        assertEquals(toolId, indexed.metadata[RetrievalMetadataKeys.CANONICAL_ID])
        assertEquals("TOOL", indexed.metadata[RetrievalMetadataKeys.ASSET_TYPE])
        assertEquals("BD01", indexed.metadata[RetrievalMetadataKeys.DOMAIN_IDS]?.split(",")?.first())
        assertTrue(indexed.contentHash == docs.single().contentHash)
    }

    @Test
    fun `Workflow 生成 WORKFLOW 类型文档`() {
        val docs = builder.buildAll(
            catalog,
            runtimeSet(emptySet(), workflowIds = setOf(WorkflowRegistry.CAMPING_MODE.workflowId)),
            sourceVersion = "1.0"
        )
        assertEquals(1, docs.size)
        val doc = docs.single()
        assertEquals(RetrievalAssetType.WORKFLOW, doc.assetType)
        assertEquals("cabin.camping_mode", doc.canonicalId)
        assertTrue(doc.semanticText.contains(doc.title))
        assertTrue(doc.operationTypes.contains(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.WORKFLOW))
    }
}
