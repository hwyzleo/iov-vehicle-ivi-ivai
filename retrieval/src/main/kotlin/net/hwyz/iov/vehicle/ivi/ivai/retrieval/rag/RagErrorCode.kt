package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

/**
 * CR-011 错误码（IVI-IVAI-DSN-CR-011 失败与降级表）。
 *
 * | 错误码 | 含义 |
 * | IVAI-RAG-001 | 无可用或兼容索引 |
 * | IVAI-RAG-002 | Embedding 服务不可用 |
 * | IVAI-RAG-003 | 向量维度/数值非法 |
 * | IVAI-RAG-004 | Index Manifest 不兼容 |
 * | IVAI-RAG-005 | 索引完整性校验失败 |
 * | IVAI-RAG-006 | 无候选达到阈值 |
 */
object RagErrorCode {
    const val NO_INDEX = "IVAI-RAG-001"
    const val EMBEDDING_UNAVAILABLE = "IVAI-RAG-002"
    const val INVALID_VECTOR = "IVAI-RAG-003"
    const val MANIFEST_INCOMPATIBLE = "IVAI-RAG-004"
    const val INTEGRITY_FAILED = "IVAI-RAG-005"
    const val NO_CANDIDATE = "IVAI-RAG-006"
}

/**
 * RAG 运行时异常（CR-011）。携带 [errorCode] 便于上层按失败与降级表处理：
 * 有界重试、使旧索引失效并全量重建、回滚上一有效索引或走受控非 RAG 路径。
 */
class RagException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
