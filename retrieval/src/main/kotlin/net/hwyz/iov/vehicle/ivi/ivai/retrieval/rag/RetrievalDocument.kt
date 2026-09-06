package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/**
 * L1 检索资产类型（CR-011）。
 */
enum class RetrievalAssetType {
    TOOL,
    WORKFLOW
}

/**
 * L1 主检索文档（CR-011）。
 *
 * 一个 canonical Tool/Workflow 生成一个主检索文档；Alias 合并为受控字段
 * （[semanticText] 模板），避免同一资产占据多个 Top-K 槽位。必要时允许按
 * Alias 分片，但返回阶段必须按 [canonicalId] 去重。
 *
 * [semanticText] 按固定模板拼接名称、职责、动作、对象、参数语义、枚举语义和
 * 批准 Alias。不得将 Policy 密钥、真实 Binding 地址、用户敏感数据或任意未审核
 * 语料写入 Tool 检索文档。
 */
data class RetrievalDocument(
    val documentId: String,
    val canonicalId: String,
    val assetType: RetrievalAssetType,
    val title: String,
    val semanticText: String,
    val domainIds: Set<BusinessDomainId>,
    val operationTypes: Set<OperationType>,
    val capabilityPackIds: Set<String>,
    val requiredSlots: Set<String>,
    val governanceStatus: GovernanceStatus,
    val governanceVersion: String,
    val bindingVersion: String,
    val sourceVersion: String,
    val contentHash: String
) {
    /** 转为索引文档（CR-011）：治理元数据写入 metadata 供“先过滤再检索”。 */
    fun toIndexedDocument(): net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexedDocument =
        net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexedDocument(
            documentId = documentId,
            text = semanticText,
            metadata = mapOf(
                RetrievalMetadataKeys.CANONICAL_ID to canonicalId,
                RetrievalMetadataKeys.ASSET_TYPE to assetType.name,
                RetrievalMetadataKeys.DOMAIN_IDS to domainIds.joinToString(",") { it.code },
                RetrievalMetadataKeys.OPERATION_TYPES to operationTypes.joinToString(",") { it.name },
                RetrievalMetadataKeys.CAPABILITY_PACK_IDS to capabilityPackIds.joinToString(","),
                RetrievalMetadataKeys.GOVERNANCE_STATUS to governanceStatus.name,
                RetrievalMetadataKeys.GOVERNANCE_VERSION to governanceVersion
            ),
            contentHash = contentHash
        )
}

/**
 * L1 索引元数据过滤器键（LocalExactVectorStore 元数据约定，CR-011）。
 *
 * LOCAL_EXACT 在内存中保存归一化向量与"治理元数据"，查询时先按这些键过滤
 * 候选子集再执行点积（先过滤再检索），与 CR-011 查询流程一致。
 */
object RetrievalMetadataKeys {
    const val CANONICAL_ID = "canonicalId"
    const val ASSET_TYPE = "assetType"
    const val DOMAIN_IDS = "domainIds"
    const val OPERATION_TYPES = "operationTypes"
    const val CAPABILITY_PACK_IDS = "capabilityPackIds"
    const val GOVERNANCE_STATUS = "governanceStatus"
    const val GOVERNANCE_VERSION = "governanceVersion"
}
