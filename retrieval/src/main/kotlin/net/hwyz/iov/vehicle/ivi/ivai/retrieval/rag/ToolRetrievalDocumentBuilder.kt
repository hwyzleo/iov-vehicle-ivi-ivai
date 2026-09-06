package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry

/**
 * L1 检索文档构建器（CR-011）。
 *
 * 从 Governance Catalog + 统一运行时候选集生成确定性 [RetrievalDocument]：
 * 一个 canonical Tool/Workflow 生成一个主检索文档；Alias 合并为受控字段
 * （[SemanticTextTemplate]），避免同一资产占据多个 Top-K 槽位。每条文档带
 * [RetrievalDocument.contentHash]，供索引按 contentHash 增量更新。
 *
 * 只处理 [RuntimeCapabilitySet.runtimeCandidateToolIds] /
 * [RuntimeCapabilitySet.runtimeCandidateWorkflowIds] 内的资产 —— RAG 只能检索
 * 统一候选集的合法子集（CR-010 延续）。
 */
class ToolRetrievalDocumentBuilder(
    private val registry: ToolRegistry,
    private val workflowRegistry: WorkflowRegistry? = null
) {

    fun buildAll(
        catalog: GovernanceCatalog,
        runtimeSet: RuntimeCapabilitySet,
        sourceVersion: String
    ): List<RetrievalDocument> =
        buildToolDocuments(catalog, runtimeSet, sourceVersion) +
            buildWorkflowDocuments(catalog, runtimeSet, sourceVersion)

    fun buildToolDocuments(
        catalog: GovernanceCatalog,
        runtimeSet: RuntimeCapabilitySet,
        sourceVersion: String
    ): List<RetrievalDocument> {
        val specByToolId = catalog.tools.associateBy { it.toolId }
        return runtimeSet.runtimeCandidateToolIds.sorted().mapNotNull { toolId ->
            val tool = registry.get(toolId) ?: return@mapNotNull null
            val spec = specByToolId[toolId]
            toToolDocument(tool, spec, spec?.governanceVersion ?: runtimeSet.governanceVersion, sourceVersion)
        }
    }

    fun buildWorkflowDocuments(
        catalog: GovernanceCatalog,
        runtimeSet: RuntimeCapabilitySet,
        sourceVersion: String
    ): List<RetrievalDocument> {
        val specByWorkflowId = catalog.workflows.associateBy { it.workflowId }
        return runtimeSet.runtimeCandidateWorkflowIds.sorted().mapNotNull { workflowId ->
            val wf = workflowRegistry?.get(workflowId) ?: return@mapNotNull null
            val spec = specByWorkflowId[workflowId]
            toWorkflowDocument(wf, spec?.governanceVersion ?: runtimeSet.governanceVersion, sourceVersion)
        }
    }

    private fun toToolDocument(
        tool: ToolDefinition,
        spec: net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolGovernanceSpec?,
        governanceVersion: String,
        sourceVersion: String
    ): RetrievalDocument {
        val semanticText = SemanticTextTemplate.tool(tool)
        val contentHash = ContentHash.of(
            tool.toolId,
            semanticText,
            governanceVersion,
            tool.governanceVersion,
            sourceVersion
        )
        return RetrievalDocument(
            documentId = "tool:${tool.toolId}",
            canonicalId = tool.toolId,
            assetType = RetrievalAssetType.TOOL,
            title = tool.name,
            semanticText = semanticText,
            domainIds = setOf(tool.domainId),
            operationTypes = tool.supportedOperations,
            capabilityPackIds = setOf(tool.capabilityPackId),
            requiredSlots = objectsFromSchema(tool.parameterSchema).toSet(),
            governanceStatus = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus.APPROVED,
            governanceVersion = governanceVersion,
            bindingVersion = spec?.bindingStatus?.name ?: "",
            sourceVersion = sourceVersion,
            contentHash = contentHash
        )
    }

    private fun toWorkflowDocument(wf: WorkflowDefinition, governanceVersion: String, sourceVersion: String): RetrievalDocument {
        val semanticText = SemanticTextTemplate.workflow(wf)
        val contentHash = ContentHash.of(
            wf.workflowId,
            semanticText,
            governanceVersion,
            wf.governanceVersion,
            sourceVersion
        )
        return RetrievalDocument(
            documentId = "workflow:${wf.workflowId}",
            canonicalId = wf.workflowId,
            assetType = RetrievalAssetType.WORKFLOW,
            title = wf.name,
            semanticText = semanticText,
            domainIds = wf.domainIds,
            operationTypes = setOf(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.WORKFLOW),
            capabilityPackIds = emptySet(),
            requiredSlots = wf.triggerExamples.toSet(),
            governanceStatus = wf.status,
            governanceVersion = governanceVersion,
            bindingVersion = "",
            sourceVersion = sourceVersion,
            contentHash = contentHash
        )
    }

    private fun objectsFromSchema(schema: String): Set<String> =
        SemanticTextTemplate.objectsFromSchema(schema).split("、").filter { it.isNotBlank() }.toSet()
}
