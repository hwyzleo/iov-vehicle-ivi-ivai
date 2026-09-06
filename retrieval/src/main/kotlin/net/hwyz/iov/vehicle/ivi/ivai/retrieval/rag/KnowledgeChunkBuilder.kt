package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeSourceType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint

/**
 * 已批准知识来源（CR-011）。KnowledgeChunk 只允许来自批准的车辆说明书、功能
 * 解释、故障帮助及其他端侧可用资料；切分必须保留标题层级、章节位置、车型/软件
 * 版本和来源版本。
 */
data class KnowledgeSource(
    val sourceId: String,
    val sourceType: KnowledgeSourceType,
    val title: String,
    val sourceVersion: String,
    val vehicleModels: Set<String> = setOf("*"),
    val softwareVersions: VersionConstraint = VersionConstraint(),
    val language: String = "zh-CN",
    val sections: List<KnowledgeSection>
)

/**
 * 知识章节（CR-011）。[sectionPath] 保留标题层级（"故障/胎压报警"）；切分时
 * 警告与操作步骤必须保持在同一 Chunk，不得拆散安全警告与对应操作步骤。
 */
data class KnowledgeSection(
    val sectionId: String,
    val title: String,
    val sectionPath: String,
    val content: String
)

/**
 * KnowledgeChunk 构建器（CR-011）。从已批准来源切分为 [KnowledgeChunk]，
 * 每条带确定性 [KnowledgeChunk.contentHash]，供索引按 contentHash 增量更新。
 */
class KnowledgeChunkBuilder {

    fun build(source: KnowledgeSource): List<KnowledgeChunk> =
        source.sections.map { section ->
            KnowledgeChunk(
                chunkId = "${source.sourceId}.${section.sectionId}",
                sourceId = source.sourceId,
                sourceType = source.sourceType,
                title = section.title,
                sectionPath = section.sectionPath,
                content = section.content,
                vehicleModels = source.vehicleModels,
                softwareVersions = source.softwareVersions,
                language = source.language,
                sourceVersion = source.sourceVersion,
                contentHash = ContentHash.of(
                    source.sourceId,
                    section.sectionId,
                    section.sectionPath,
                    section.content,
                    source.sourceVersion
                )
            )
        }

    fun build(sources: List<KnowledgeSource>): List<KnowledgeChunk> =
        sources.flatMap { build(it) }
}
