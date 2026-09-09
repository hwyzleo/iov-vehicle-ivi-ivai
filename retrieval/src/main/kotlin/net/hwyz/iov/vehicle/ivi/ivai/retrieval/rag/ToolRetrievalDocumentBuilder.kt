package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ClimateToolBoundaryCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr017ErrorCodes
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr018ErrorCodes
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.DeterministicIntentCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry

/**
 * L1 检索文档构建器（CR-011 + CR-017）。
 *
 * 从 Governance Catalog + 统一运行时候选集生成确定性 [RetrievalDocument]：
 * 一个 canonical Tool/Workflow 生成一个主检索文档；Alias 合并为受控字段
 * （[SemanticTextTemplate]），避免同一资产占据多个 Top-K 槽位。每条文档带
 * [RetrievalDocument.contentHash]，供索引按 contentHash 增量更新。
 *
 * CR-017：文档语义载荷扩展为 [ToolRetrievalSemanticPayload]——canonical enum、
 * 批准 Alias、分区正例、负例边界、冲突 Tool 与来源版本进入 semanticText（REQ-176）；
 * 载荷缺 Schema/Alias/来源版本时构建失败（IVAI-RAG-DOC-001，REQ-176 校验）。
 *
 * 只处理 [RuntimeCapabilitySet.runtimeCandidateToolIds] /
 * [RuntimeCapabilitySet.runtimeCandidateWorkflowIds] 内的资产 —— RAG 只能检索
 * 统一候选集的合法子集（CR-010 延续）。
 */
class ToolRetrievalDocumentBuilder(
    private val registry: ToolRegistry,
    private val workflowRegistry: WorkflowRegistry? = null,
    /** CR-017: L0 编译产物（冲突 Tool 集来源）；不注入时冲突集为空。 */
    private val deterministicCatalog: DeterministicIntentCatalog? = null
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
        val conflictToolIds = deterministicCatalog?.profileFor(tool.toolId)?.conflictToolIds ?: emptySet()
        val payload = ToolRetrievalPayloadFactory.fromTool(
            tool = tool,
            conflictToolIds = conflictToolIds,
            sourceVersions = setOfNotNull(
                governanceVersion,
                tool.governanceVersion,
                sourceVersion,
                "vehicle_position_v2"
            )
        )
        // IVAI-RAG-DOC-001：Tool 检索文档必须携带来源版本；声明了真实参数就必须解析出参数。
        val compactSchema = tool.parameterSchema.replace(Regex("\\s"), "")
        val declaresProperties = compactSchema.contains("\"properties\":{") &&
            !compactSchema.contains("\"properties\":{}")
        if (payload.sourceVersions.isEmpty() ||
            (declaresProperties && payload.parameterSchemas.isEmpty())
        ) {
            throw IllegalArgumentException(
                "${Cr017ErrorCodes.RAG_DOC}: Tool ${tool.toolId} 检索文档缺少参数 Schema/Alias/来源版本"
            )
        }
        // CR-018（IVAI-RAG-BOUNDARY-001）：空调相似 Tool 的检索文档必须携带相似 Tool 边界
        // （冲突集 + 对象/动作证据），否则无法在 Top-K 中区分 power/vent/fan/airflow/auto。
        val boundary = ClimateToolBoundaryCatalog.forTool(tool.toolId)
        if (boundary != null) {
            if (payload.conflictingToolIds.isEmpty() ||
                payload.positiveObjects.isEmpty() ||
                payload.positiveActions.isEmpty()
            ) {
                throw IllegalArgumentException(
                    "${Cr018ErrorCodes.RAG_BOUNDARY}: Tool ${tool.toolId} 检索文档缺少相似 Tool 边界"
                )
            }
        }
        val semanticText = SemanticTextTemplate.tool(payload)
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
