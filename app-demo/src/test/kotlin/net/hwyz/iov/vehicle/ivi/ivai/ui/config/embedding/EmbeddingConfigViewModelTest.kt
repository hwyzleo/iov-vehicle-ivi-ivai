package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CR-011 补齐 · 嵌入模型配置 ViewModel：预填、脏跟踪、Keep/Replace/Clear 语义、
 * 校验错误映射与恢复默认（仿 ModelConfigViewModelTest）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddingConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val savedConfig = EmbeddingConfig(
        providerType = "HTTP_COMPATIBLE",
        baseUrl = "https://embed.example.com/v1",
        modelId = "embed-v3",
        modelVersion = "v1",
        dimension = 768,
        timeoutMs = 10_000L,
        maxRetries = 2,
        batchSize = 16
    )

    private open class FakeGateway(
        var keyStatus: KeyStatus = KeyStatus.NOT_SET,
        var loaded: EmbeddingConfig = EmbeddingConfig(),
        var validateResult: ValidationResult = ValidationResult.Valid,
        var testResult: ConnectionTestResult = ConnectionTestResult.Success("embeddings"),
        var saveResult: RagSaveResult = RagSaveResult.Success(1L),
        var testCalls: Int = 0,
        var saveCalls: Int = 0
    ) : EmbeddingConfigGateway {

        var savedDraft: EmbeddingConfig? = null
        var savedAction: ApiKeyAction? = null

        override suspend fun load(): EmbeddingConfig = loaded

        override suspend fun keyStatus(): KeyStatus = keyStatus

        override suspend fun validate(config: EmbeddingConfig): ValidationResult = validateResult

        override suspend fun testConnection(
            config: EmbeddingConfig,
            apiKeyAction: ApiKeyAction
        ): ConnectionTestResult {
            testCalls++
            return testResult
        }

        override suspend fun save(
            config: EmbeddingConfig,
            apiKeyAction: ApiKeyAction
        ): RagSaveResult {
            savedDraft = config
            savedAction = apiKeyAction
            saveCalls++
            return saveResult
        }

        override suspend fun resetToDefault(): RagSaveResult = RagSaveResult.Success(2L)
    }

    @Test
    fun `attach prefills fields and key status from the repository`() = runTest {
        val gateway = FakeGateway(loaded = savedConfig, keyStatus = KeyStatus.SET)
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("https://embed.example.com/v1", s.baseUrl)
        assertEquals("embed-v3", s.modelId)
        assertEquals("v1", s.modelVersion)
        assertEquals("768", s.dimension)
        assertEquals("10000", s.timeoutMs)
        assertEquals(KeyStatus.SET, s.keyStatus)
        assertFalse(s.hasUnsavedChanges)
    }

    @Test
    fun `editing fields marks unsaved changes`() = runTest {
        val vm = EmbeddingConfigViewModel()
        vm.attach(FakeGateway())
        advanceUntilIdle()
        assertFalse(vm.state.value.hasUnsavedChanges)

        vm.onAction(EmbeddingConfigUiAction.BaseUrlChanged("https://other.example.com"))
        assertTrue(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `save with blank key keeps the saved key`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.BaseUrlChanged("https://new.example.com"))
        vm.onAction(EmbeddingConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Keep, gateway.savedAction)
        assertEquals(1, gateway.saveCalls)
        assertTrue(vm.state.value.message.orEmpty().startsWith("保存成功"))
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `save with typed key replaces and clears the draft`() = runTest {
        val gateway = FakeGateway()
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.ApiKeyChanged("sk-embed-123"))
        vm.onAction(EmbeddingConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Replace("sk-embed-123"), gateway.savedAction)
        assertEquals("", vm.state.value.apiKeyDraft)
    }

    @Test
    fun `validate failure maps to field errors and does not save`() = runTest {
        val gateway = FakeGateway(
            validateResult = ValidationResult.Invalid(
                listOf(ValidationResult.FieldError("baseUrl", "IVAI-RAG-CONFIG-004", "嵌入服务地址不能为空"))
            )
        )
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.BaseUrlChanged("https://partial.example.com"))
        vm.onAction(EmbeddingConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(0, gateway.saveCalls)
        assertEquals("嵌入服务地址不能为空", vm.state.value.validationErrors["baseUrl"])
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `test connection success maps to success message`() = runTest {
        val gateway = FakeGateway(testResult = ConnectionTestResult.Success("embeddings"))
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.TestConnectionClicked)
        advanceUntilIdle()

        assertEquals(1, gateway.testCalls)
        assertTrue(vm.state.value.message.orEmpty().startsWith("连接测试成功"))
    }

    @Test
    fun `test connection unauthorized maps to auth failure message`() = runTest {
        val gateway = FakeGateway(testResult = ConnectionTestResult.Unauthorized)
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.TestConnectionClicked)
        advanceUntilIdle()

        assertTrue(vm.state.value.message.orEmpty().contains("鉴权失败"))
    }

    @Test
    fun `non numeric dimension blocks save with field error`() = runTest {
        val gateway = FakeGateway()
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.DimensionChanged("abc"))
        vm.onAction(EmbeddingConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(0, gateway.saveCalls)
        assertEquals("向量维度必须为整数", vm.state.value.validationErrors["dimension"])
    }

    @Test
    fun `confirm reset clears config and key draft`() = runTest {
        val gateway = FakeGateway()
        val vm = EmbeddingConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(EmbeddingConfigUiAction.ApiKeyChanged("sk-embed-123"))
        vm.confirmReset()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("", s.baseUrl)
        assertEquals("", s.modelId)
        assertEquals("0", s.dimension)
        assertEquals("", s.apiKeyDraft)
        assertTrue(s.message.orEmpty().contains("已恢复默认"))
        assertFalse(s.hasUnsavedChanges)
    }
}
