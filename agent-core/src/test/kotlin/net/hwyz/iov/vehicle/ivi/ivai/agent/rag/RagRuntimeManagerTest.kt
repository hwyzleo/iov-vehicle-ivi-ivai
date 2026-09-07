package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeRetrieverImpl
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.SampleKnowledgeDocs
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool.FixedToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory fake repository for RAG runtime tests. */
class FakeRagConfigRepository(initial: RagRuntimeConfig = RagRuntimeConfig()) : RagConfigRepository {
    private val _configState = MutableStateFlow<RagConfigState>(RagConfigState.Valid(initial))
    override val configState: StateFlow<RagConfigState> = _configState.asStateFlow()

    override suspend fun loadSnapshot(): RagRuntimeConfig =
        (_configState.value as RagConfigState.Valid).config

    override suspend fun save(draft: RagConfigDraft): RagSaveResult {
        val config = loadSnapshot()
        val next = RagRuntimeConfig(
            enabled = draft.enabled,
            toolRagEnabled = draft.toolRagEnabled,
            knowledgeRagEnabled = draft.knowledgeRagEnabled,
            toolTopK = draft.toolTopK,
            knowledgeTopK = draft.knowledgeTopK,
            version = config.version + 1
        )
        _configState.value = RagConfigState.Valid(next)
        return RagSaveResult.Success(next.version)
    }

    override suspend fun resetToDefault(): RagSaveResult {
        _configState.value = RagConfigState.Valid(RagRuntimeConfig())
        return RagSaveResult.Success(0)
    }

    // ---- CR-011 补齐：Embedding 配置（内存实现） ----

    override suspend fun validateEmbedding(config: net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig): ValidationResult =
        EmbeddingConfigValidator().validate(config)

    override suspend fun embeddingKeyStatus(): KeyStatus = KeyStatus.NOT_SET

    override suspend fun testEmbeddingConnection(
        config: net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): ConnectionTestResult = ConnectionTestResult.InvalidResponse("内存 fake 不执行连接测试")

    override suspend fun saveEmbedding(
        config: net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): RagSaveResult {
        val current = loadSnapshot()
        val next = current.copy(
            version = current.version + 1,
            rag = current.rag.copy(embedding = config)
        )
        _configState.value = RagConfigState.Valid(next)
        return RagSaveResult.Success(next.version)
    }

    override suspend fun resetEmbedding(): RagSaveResult {
        val current = loadSnapshot()
        val next = current.copy(
            version = current.version + 1,
            rag = current.rag.copy(embedding = net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig())
        )
        _configState.value = RagConfigState.Valid(next)
        return RagSaveResult.Success(next.version)
    }
}

/**
 * CR-005 验证设计 · 配置与运行时：默认关闭可启动；打开但无资源 → 不可用降级；
 * 首期混合/内置检索器就绪 → READY。
 */
class RagRuntimeManagerTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val toolRetriever = FixedToolRetriever(registry)
    private val knowledgeRetriever = KnowledgeRetrieverImpl(SampleKnowledgeDocs.chunks)

    @Test
    fun `默认关闭时 DISABLED 且两者不可用`() {
        val repo = FakeRagConfigRepository()
        val manager = RagRuntimeManager(repo, toolRetriever = toolRetriever, knowledgeRetriever = knowledgeRetriever)
        assertEquals(RagRuntimeStatus.DISABLED, manager.status())
        val snapshot = manager.snapshot()
        assertFalse(snapshot.enabled)
        assertFalse(snapshot.toolRagAvailable)
        assertFalse(snapshot.knowledgeRagAvailable)
    }

    @Test
    fun `打开后混合与内置检索器就绪 → READY`() {
        val repo = FakeRagConfigRepository(RagRuntimeConfig(enabled = true))
        val manager = RagRuntimeManager(repo, toolRetriever = toolRetriever, knowledgeRetriever = knowledgeRetriever)
        assertEquals(RagRuntimeStatus.READY, manager.status())
        val snapshot = manager.snapshot()
        assertTrue(snapshot.toolRagAvailable)
        assertTrue(snapshot.knowledgeRagAvailable)
        assertEquals("FixedToolRetriever", snapshot.toolRetrieverType)
    }

    @Test
    fun `子开关关闭使对应组件不可用`() {
        val repo = FakeRagConfigRepository(
            RagRuntimeConfig(enabled = true, toolRagEnabled = false, knowledgeRagEnabled = true)
        )
        val manager = RagRuntimeManager(repo, toolRetriever = toolRetriever, knowledgeRetriever = knowledgeRetriever)
        val snapshot = manager.snapshot()
        assertFalse(snapshot.toolRagAvailable, "Tool RAG 子开关关闭时不可用")
        assertTrue(snapshot.knowledgeRagAvailable)
        assertEquals(RagRuntimeStatus.DEGRADED, manager.status())
    }

    @Test
    fun `打开但无任何检索器 → UNAVAILABLE 不崩溃`() {
        val repo = FakeRagConfigRepository(RagRuntimeConfig(enabled = true))
        val manager = RagRuntimeManager(repo, toolRetriever = null, knowledgeRetriever = null)
        assertEquals(RagRuntimeStatus.UNAVAILABLE, manager.status())
        assertFalse(manager.snapshot().toolRagAvailable)
    }
}
