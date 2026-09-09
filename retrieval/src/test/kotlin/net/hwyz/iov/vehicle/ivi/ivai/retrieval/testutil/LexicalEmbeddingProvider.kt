package net.hwyz.iov.vehicle.ivi.ivai.retrieval.testutil

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingModelDescriptor
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingResponse
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.Tokenizer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CR-017 离线评测专用：词法重叠 Embedding（测试/离线验证用）。
 *
 * 基于 [Tokenizer] 的字符二元组 TF 向量 + L2 归一化，余弦相似度反映查询与
 * 文档的**词法重叠**。用于验证：Tool 检索文档中展开的「分区正例 / 位置 Alias /
 * canonical enum / 负例」确实驱动位置表达（中左/中右/2排/3排）的正确召回
 * （Recall@5=100% 验收）。正式环境使用 HTTP/LOCAL 语义 Embedding，文档内容
 * 相同；本实现不进入生产路径。
 */
class LexicalEmbeddingProvider(
    override val descriptor: EmbeddingModelDescriptor = EmbeddingModelDescriptor(
        providerType = "LOCAL_LEXICAL_TEST",
        modelId = "lexical-token-embedding-v1",
        modelVersion = null,
        dimension = 512
    )
) : EmbeddingProvider {

    override val available: Boolean = true

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse {
        val vectors = request.texts.map { text ->
            val vec = FloatArray(descriptor.dimension)
            var count = 0
            for (token in Tokenizer.tokenize(text)) {
                val idx = abs(token.hashCode()) % descriptor.dimension
                vec[idx] += 1f
                count++
            }
            val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 0f) {
                for (i in vec.indices) vec[i] /= norm
            }
            vec
        }
        return EmbeddingResponse(vectors, descriptor.modelId, descriptor.dimension)
    }
}
