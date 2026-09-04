package net.hwyz.iov.vehicle.ivi.ivai.ui.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigState
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ViewModel behavior for the model config screen (IVI-IVAI-DSN-CR-003):
 * prefill, dirty tracking, Keep/Replace/Clear semantics, duplicate-submit
 * protection and error mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private open class FakeGateway(
        var keyStatus: KeyStatus = KeyStatus.NOT_SET,
        var savedDraft: ModelConfigDraft? = null,
        var testResult: ConnectionTestResult = ConnectionTestResult.Success,
        var saveResult: SaveResult = SaveResult.Success(1L),
        var testCalls: Int = 0,
        var saveCalls: Int = 0
    ) : ModelConfigGateway {

        var stateFlow = MutableStateFlow<ModelConfigState>(
            ModelConfigState.Valid(
                ModelRuntimeConfig(
                    baseUrl = "http://192.168.2.170:11434/".toHttpUrl(),
                    apiKey = null,
                    version = 0L
                )
            )
        )

        override val configState: StateFlow<ModelConfigState> get() = stateFlow

        override suspend fun keyStatus(): KeyStatus = keyStatus

        override suspend fun validate(draft: ModelConfigDraft): ValidationResult = ValidationResult.Valid

        override suspend fun testConnection(draft: ModelConfigDraft): ConnectionTestResult {
            testCalls++
            return testResult
        }

        override suspend fun save(draft: ModelConfigDraft): SaveResult {
            savedDraft = draft
            saveCalls++
            return saveResult
        }

        override suspend fun resetToDefault(): SaveResult = SaveResult.Success(2L)
    }

    @Test
    fun `attach prefills baseUrl and key status from the repository`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        assertEquals("http://192.168.2.170:11434/", vm.state.value.baseUrl)
        assertEquals(KeyStatus.SET, vm.state.value.keyStatus)
    }

    @Test
    fun `editing fields marks unsaved changes`() = runTest {
        val vm = ModelConfigViewModel()
        vm.attach(FakeGateway())
        advanceUntilIdle()
        assertFalse(vm.state.value.hasUnsavedChanges)

        vm.onAction(ModelConfigUiAction.BaseUrlChanged("http://other:11434"))
        assertTrue(vm.state.value.hasUnsavedChanges)

        vm.onAction(ModelConfigUiAction.ApiKeyChanged("sk-abc"))
        assertTrue(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `save with blank key draft keeps the saved key`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.BaseUrlChanged("http://new-host:11434"))
        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Keep, gateway.savedDraft?.apiKeyAction)
        assertEquals(1, gateway.saveCalls)
    }

    @Test
    fun `save with typed key replaces and clears the draft`() = runTest {
        val gateway = FakeGateway()
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.ApiKeyChanged("sk-new"))
        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Replace("sk-new"), gateway.savedDraft?.apiKeyAction)
        assertEquals("", vm.state.value.apiKeyDraft)
        assertEquals("保存成功，新的模型请求将立即使用该配置", vm.state.value.message)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `clear key checkbox maps to Clear action`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.ToggleClearKeyRequested)
        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Clear, gateway.savedDraft?.apiKeyAction)
    }

    @Test
    fun `duplicate submit while saving or testing is blocked`() = runTest {
        val gateway = FakeGateway()
        gateway.saveResult = SaveResult.Failure("IVAI-CONFIG-003", "配置持久化失败")
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.BaseUrlChanged("http://x:11434"))
        // First save is in-flight (FakeGateway returns immediately, but the
        // suspend call still runs on the test dispatcher) — fire twice.
        vm.onAction(ModelConfigUiAction.SaveClicked)
        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()

        // The second click happened while isSaving was true → only one persisted save.
        assertEquals(1, gateway.saveCalls)
        assertEquals("保存失败：配置持久化失败", vm.state.value.message)
    }

    @Test
    fun `validation errors are mapped into state`() = runTest {
        val gateway = object : FakeGateway() {
            override suspend fun validate(draft: ModelConfigDraft): ValidationResult =
                ValidationResult.Invalid(
                    listOf(
                        ValidationResult.FieldError(
                            field = "baseUrl",
                            code = "IVAI-CONFIG-001",
                            message = "模型请求地址格式非法"
                        )
                    )
                )
        }
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.BaseUrlChanged("bad-url"))
        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals("模型请求地址格式非法", vm.state.value.validationErrors["baseUrl"])
        assertFalse(vm.state.value.isSaving)
        assertNull(gateway.savedDraft)
    }

    @Test
    fun `test connection reports the result without saving`() = runTest {
        val gateway = FakeGateway()
        gateway.testResult = ConnectionTestResult.Unauthorized
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.TestConnectionClicked)
        advanceUntilIdle()

        assertEquals(1, gateway.testCalls)
        assertEquals(0, gateway.saveCalls)
        assertTrue(vm.state.value.message!!.contains("鉴权失败"))
        assertFalse(vm.state.value.isTesting)
    }

    @Test
    fun `action without connected gateway surfaces a message instead of being silent`() = runTest {
        val vm = ModelConfigViewModel()
        // Never attach → gateway is null.
        vm.onAction(ModelConfigUiAction.TestConnectionClicked)
        assertTrue(vm.state.value.message!!.contains("未连接"))
        assertFalse(vm.state.value.isTesting)

        vm.onAction(ModelConfigUiAction.SaveClicked)
        assertTrue(vm.state.value.message!!.contains("未连接"))
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `test connection exception resets busy flag and surfaces a message`() = runTest {
        val gateway = object : FakeGateway() {
            override suspend fun testConnection(draft: ModelConfigDraft): ConnectionTestResult {
                testCalls++
                throw RuntimeException("boom")
            }
        }
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.TestConnectionClicked)
        advanceUntilIdle()

        assertFalse(vm.state.value.isTesting)
        assertTrue(vm.state.value.message!!.contains("boom"))
    }

    @Test
    fun `save exception resets busy flag and surfaces a message`() = runTest {
        val gateway = object : FakeGateway() {
            override suspend fun save(draft: ModelConfigDraft): SaveResult {
                saveCalls++
                throw RuntimeException("disk-fail")
            }
        }
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertFalse(vm.state.value.isSaving)
        assertTrue(vm.state.value.message!!.contains("disk-fail"))
    }

    @Test
    fun `normalized trailing slash after save does not count as unsaved change`() = runTest {
        val gateway = FakeGateway()
        val vm = ModelConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(ModelConfigUiAction.BaseUrlChanged("http://10.0.2.2:11434"))
        vm.onAction(ModelConfigUiAction.SaveClicked)
        advanceUntilIdle()
        // Repository publishes the normalized URL (trailing slash) after save.
        gateway.stateFlow.value = ModelConfigState.Valid(
            ModelRuntimeConfig(
                baseUrl = "http://10.0.2.2:11434/".toHttpUrl(),
                apiKey = null,
                version = 1L
            )
        )
        advanceUntilIdle()

        assertFalse(vm.state.value.hasUnsavedChanges)
    }
}
