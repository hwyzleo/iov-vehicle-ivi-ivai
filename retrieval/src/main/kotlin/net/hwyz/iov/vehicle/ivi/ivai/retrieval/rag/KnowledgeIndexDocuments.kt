package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexedDocument

/**
 * KnowledgeChunk → 索引文档（CR-011）。治理/来源/车型/版本/语言写入 metadata
 * 供 L2“先过滤再检索”（Tool 文档与 KnowledgeChunk 不得写入同一逻辑索引）。
 */
fun KnowledgeChunk.toIndexedDocument(): IndexedDocument = IndexedDocument(
    documentId = chunkId,
    text = content,
    metadata = mapOf(
        KnowledgeMetadataKeys.CHUNK_ID to chunkId,
        KnowledgeMetadataKeys.SOURCE_ID to sourceId,
        KnowledgeMetadataKeys.SOURCE_TYPE to sourceType.name,
        KnowledgeMetadataKeys.VEHICLE_MODELS to vehicleModels.joinToString(","),
        KnowledgeMetadataKeys.SOFTWARE_MIN to (softwareVersions.min ?: ""),
        KnowledgeMetadataKeys.SOFTWARE_MAX to (softwareVersions.max ?: ""),
        KnowledgeMetadataKeys.LANGUAGE to language
    ),
    contentHash = contentHash
)
