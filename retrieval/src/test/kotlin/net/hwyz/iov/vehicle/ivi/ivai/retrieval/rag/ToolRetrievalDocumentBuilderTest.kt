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

    // ---------------- CR-017：语义载荷（位置 Alias / enum / 正反例 / 冲突 / 来源版本） ----------------

    @Test
    fun `climate fan speed set 文档包含分区正例 位置 Alias enum 负例与来源版本`() {
        val docs = builder.buildAll(
            catalog,
            runtimeSet(setOf("climate.fan.speed.set")),
            sourceVersion = "1.0"
        )
        val doc = docs.single { it.canonicalId == "climate.fan.speed.set" }
        // 分区正例（中左/中右/2排/3排）进入 semanticText。
        assertTrue(doc.semanticText.contains("中左风量档位调到5档"), "semanticText 必须含中左正例")
        assertTrue(doc.semanticText.contains("中右设置风量档位5档"), "semanticText 必须含中右正例")
        assertTrue(doc.semanticText.contains("2排风量档位设为5档"), "semanticText 必须含2排正例")
        assertTrue(doc.semanticText.contains("3排风量档位调到5档"), "semanticText 必须含3排正例")
        // 负例边界（右边/后面）进入 semanticText。
        assertTrue(doc.semanticText.contains("右边风量调到5"), "semanticText 必须含宽泛表达负例")
        // canonical enum 显式展开（zone 9 值）。
        assertTrue(doc.semanticText.contains("middle_left"), "semanticText 必须含 middle_left 枚举")
        assertTrue(doc.semanticText.contains("second_row"), "semanticText 必须含 second_row 枚举")
        assertTrue(doc.semanticText.contains("third_row"), "semanticText 必须含 third_row 枚举")
        assertTrue(doc.semanticText.contains("位置合法") || doc.semanticText.contains("位置别名"), "semanticText 必须含位置 Alias 段")
        assertTrue(doc.semanticText.contains("来源版本"), "semanticText 必须含来源版本段")
        // 冲突 Tool（speed.adjust）进入相似工具段（注入 L0 编译产物）。
        val withL0 = ToolRetrievalDocumentBuilder(
            registry, WorkflowRegistry,
            deterministicCatalog = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.DeterministicIntentCatalog.build()
        )
        val docsL0 = withL0.buildAll(catalog, runtimeSet(setOf("climate.fan.speed.set")), "1.0")
        val docL0 = docsL0.single { it.canonicalId == "climate.fan.speed.set" }
        assertTrue(docL0.semanticText.contains("climate.fan.speed.adjust"), "semanticText 必须含冲突 Tool")
    }

    @Test
    fun `Catalog 正例变化导致 contentHash 变化（REQ-177 索引刷新前提）`() {
        val docs1 = builder.buildAll(catalog, runtimeSet(setOf("climate.fan.speed.set")), "1.0")
        val hash1 = docs1.single { it.canonicalId == "climate.fan.speed.set" }.contentHash
        // 模拟 Alias/Catalog 内容变化：更换正例后语义文本变化 → contentHash 必须变化。
        val toolId = "climate.fan.speed.set"
        val altered = registry.get(toolId)!!.copy(
            positiveExamples = listOf("全新分区表达风量档位调到9档")
        )
        val registry2 = ToolRegistry().register(altered)
        val builder2 = ToolRetrievalDocumentBuilder(registry2, WorkflowRegistry)
        val docs2 = builder2.buildAll(catalog, runtimeSet(setOf(toolId)), "1.0")
        val hash2 = docs2.single { it.canonicalId == toolId }.contentHash
        assertTrue(hash1 != hash2, "正例变化必须引起 contentHash 变化（增量重建依据）")
    }
}
