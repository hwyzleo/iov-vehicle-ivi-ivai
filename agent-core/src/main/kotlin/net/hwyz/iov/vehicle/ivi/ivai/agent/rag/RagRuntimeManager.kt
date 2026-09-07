package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.IndexManifest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.IndexPackageManager
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.IndexValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.VectorIndex
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagConfig

/**
 * RAG runtime health manager (CR-005). Starting with the persisted config it
 * checks, in order:
 *  1. embedding model exists and is loadable
 *  2. index manifest / signature / hash valid
 *  3. embedding model id + dimension compatible with the index
 *  4. vehicle / software version / language match
 *  5. retriever health
 *
 * A failure never auto-turns the user's switch back off: the config stays
 * "已打开" while the runtime reports DEGRADED / UNAVAILABLE — distinguishing
 * "user disabled" from "user enabled but resources not deployed".
 *
 * First phase: rule-based ToolRetriever / built-in KnowledgeRetriever do not
 * require a vector index, so they report available whenever injected; a real
 * deployment passes manifests + vector indexes and this manager validates them.
 */
class RagRuntimeManager(
    private val repository: RagConfigRepository,
    private val toolRetriever: ToolRetriever? = null,
    private val knowledgeRetriever: KnowledgeRetriever? = null,
    private val embeddingProvider: EmbeddingProvider? = null,
    private val toolVectorIndex: VectorIndex? = null,
    private val knowledgeVectorIndex: VectorIndex? = null,
    private val indexPackageManager: IndexPackageManager? = null,
    private val toolIndexManifest: IndexManifest? = null,
    private val knowledgeIndexManifest: IndexManifest? = null
) {

    fun currentConfig(): RagRuntimeConfig =
        (repository.configState.value as? RagConfigState.Valid)?.config ?: RagRuntimeConfig()

    /** CR-011 生效 RAG 配置（UI 开关镜像进 RagConfig）。 */
    fun ragConfig(): RagConfig = currentConfig().effectiveRagConfig()

    /** 当前 Embedding Provider 的模型标识（调试面板展示用）。 */
    fun embeddingModelId(): String? = embeddingProvider?.descriptor?.modelId

    /** Immutable per-request snapshot; in-flight requests keep the one they got. */
    fun snapshot(): RagExecutionSnapshot {
        val config = currentConfig()
        val toolOk = config.enabled && config.toolRagEnabled && toolRetrieverAvailable()
        val knowledgeOk = config.enabled && config.knowledgeRagEnabled && knowledgeRetrieverAvailable()
        return RagExecutionSnapshot(
            configVersion = config.version,
            enabled = config.enabled,
            toolRagAvailable = toolOk,
            knowledgeRagAvailable = knowledgeOk,
            toolRetrieverType = if (toolOk) toolRetriever?.let { it::class.simpleName } else null,
            knowledgeRetrieverType = if (knowledgeOk) knowledgeRetriever?.let { it::class.simpleName } else null,
            toolTopK = config.toolTopK,
            knowledgeTopK = config.knowledgeTopK
        )
    }

    fun status(): RagRuntimeStatus {
        val config = currentConfig()
        if (!config.enabled) return RagRuntimeStatus.DISABLED
        val toolOk = config.toolRagEnabled && toolRetrieverAvailable()
        val knowledgeOk = config.knowledgeRagEnabled && knowledgeRetrieverAvailable()
        return when {
            toolOk && knowledgeOk -> RagRuntimeStatus.READY
            toolOk || knowledgeOk -> RagRuntimeStatus.DEGRADED
            else -> RagRuntimeStatus.UNAVAILABLE
        }
    }

    fun toolRetrieverAvailable(): Boolean {
        if (toolRetriever == null) return false
        // Vector path: a deployed tool index must pass the full validation chain.
        if (toolIndexManifest != null && toolVectorIndex != null) {
            val embedding = embeddingProvider
            if (embedding == null || !embedding.available) return false
            val validation = indexPackageManager?.validate(toolIndexManifest, embedding, toolIndexManifest.contentHash)
            return validation is IndexValidationResult.Valid
        }
        // First-phase rule/keyword retriever — no vector index required.
        return true
    }

    fun knowledgeRetrieverAvailable(): Boolean {
        if (knowledgeRetriever == null) return false
        if (knowledgeIndexManifest != null && knowledgeVectorIndex != null) {
            val embedding = embeddingProvider
            if (embedding == null || !embedding.available) return false
            val validation = indexPackageManager?.validate(knowledgeIndexManifest, embedding, knowledgeIndexManifest.contentHash)
            return validation is IndexValidationResult.Valid
        }
        return true
    }
}
