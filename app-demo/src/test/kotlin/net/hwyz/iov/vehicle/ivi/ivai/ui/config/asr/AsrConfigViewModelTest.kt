package net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr

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
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigState
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrPublicConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ASR config ViewModel (IVI-IVAI-DSN-CR-006): prefill, dirty tracking,
 * Keep/Replace/Clear key semantics, duplicate-submit protection and error
 * mapping — mirroring ModelConfigViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsrConfigViewModelTest {

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
        var savedDraft: AsrConfigDraft? = null,
        var testResult: ConnectionTestResult = ConnectionTestResult.Success(),
        var saveResult: SaveResult = SaveResult.Success(1L),
        var testCalls: Int = 0,
        var saveCalls: Int = 0
    ) : AsrConfigGateway {

        var stateFlow = MutableStateFlow<AsrConfigState>(
            AsrConfigState.Valid(
                AsrRuntimeConfig(
                    public = AsrPublicConfig(providerType = AsrProviderType.ANDROID_ON_DEVICE),
                    apiKey = null,
                    version = 0L
                )
            )
        )

        override val configState: StateFlow<AsrConfigState> get() = stateFlow

        override suspend fun keyStatus(): KeyStatus = keyStatus

        override suspend fun validate(draft: AsrConfigDraft): ValidationResult = ValidationResult.Valid

        override suspend fun testConnection(draft: AsrConfigDraft): ConnectionTestResult {
            testCalls++
            return testResult
        }

        override suspend fun save(draft: AsrConfigDraft): SaveResult {
            savedDraft = draft
            saveCalls++
            return saveResult
        }

        override suspend fun resetToDefault(): SaveResult = SaveResult.Success(2L)
    }

    @Test
    fun `attach prefills provider and key status`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        assertEquals(AsrProviderType.ANDROID_ON_DEVICE, vm.state.value.providerType)
        assertEquals(KeyStatus.SET, vm.state.value.keyStatus)
    }

    @Test
    fun `switching provider marks unsaved changes`() = runTest {
        val vm = AsrConfigViewModel()
        vm.attach(FakeGateway())
        advanceUntilIdle()
        assertFalse(vm.state.value.hasUnsavedChanges)

        vm.onAction(AsrConfigUiAction.ProviderTypeChanged(AsrProviderType.HTTP_COMPATIBLE))
        assertTrue(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `save with blank key draft keeps the saved key`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.ProviderTypeChanged(AsrProviderType.ANDROID_SYSTEM))
        vm.onAction(AsrConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Keep, gateway.savedDraft?.apiKeyAction)
        assertEquals(1, gateway.saveCalls)
    }

    @Test
    fun `save with typed key replaces and clears the draft`() = runTest {
        val gateway = FakeGateway()
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.ApiKeyChanged("sk-asr"))
        vm.onAction(AsrConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Replace("sk-asr"), gateway.savedDraft?.apiKeyAction)
        assertEquals("", vm.state.value.apiKeyDraft)
        assertEquals("保存成功，新的语音会话将使用该配置", vm.state.value.message)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `clear key checkbox maps to Clear action`() = runTest {
        val gateway = FakeGateway(keyStatus = KeyStatus.SET)
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.ToggleClearKeyRequested)
        vm.onAction(AsrConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(ApiKeyAction.Clear, gateway.savedDraft?.apiKeyAction)
    }

    @Test
    fun `duplicate submit while saving is blocked`() = runTest {
        val gateway = FakeGateway()
        gateway.saveResult = SaveResult.Failure("IVAI-ASR-CONFIG-004", "配置持久化失败")
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.ProviderTypeChanged(AsrProviderType.ANDROID_SYSTEM))
        vm.onAction(AsrConfigUiAction.SaveClicked)
        vm.onAction(AsrConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals(1, gateway.saveCalls)
        assertEquals("保存失败：配置持久化失败", vm.state.value.message)
    }

    @Test
    fun `validation errors are mapped into state`() = runTest {
        val gateway = object : FakeGateway() {
            override suspend fun validate(draft: AsrConfigDraft): ValidationResult =
                ValidationResult.Invalid(
                    listOf(
                        ValidationResult.FieldError(
                            field = "baseUrl",
                            code = "IVAI-ASR-CONFIG-002",
                            message = "ASR 服务地址格式非法"
                        )
                    )
                )
        }
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.ProviderTypeChanged(AsrProviderType.HTTP_COMPATIBLE))
        vm.onAction(AsrConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertEquals("ASR 服务地址格式非法", vm.state.value.validationErrors["baseUrl"])
        assertFalse(vm.state.value.isSaving)
        assertNull(gateway.savedDraft)
    }

    @Test
    fun `test connection reports the result without saving`() = runTest {
        val gateway = FakeGateway()
        gateway.testResult = ConnectionTestResult.Unauthorized
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.TestConnectionClicked)
        advanceUntilIdle()

        assertEquals(1, gateway.testCalls)
        assertEquals(0, gateway.saveCalls)
        assertTrue(vm.state.value.message!!.contains("鉴权失败"))
        assertFalse(vm.state.value.isTesting)
    }

    @Test
    fun `action without connected gateway surfaces a message`() = runTest {
        val vm = AsrConfigViewModel()
        vm.onAction(AsrConfigUiAction.TestConnectionClicked)
        assertTrue(vm.state.value.message!!.contains("未连接"))
        assertFalse(vm.state.value.isTesting)

        vm.onAction(AsrConfigUiAction.SaveClicked)
        assertTrue(vm.state.value.message!!.contains("未连接"))
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `save exception resets busy flag and surfaces a message`() = runTest {
        val gateway = object : FakeGateway() {
            override suspend fun save(draft: AsrConfigDraft): SaveResult {
                saveCalls++
                throw RuntimeException("disk-fail")
            }
        }
        val vm = AsrConfigViewModel()
        vm.attach(gateway)
        advanceUntilIdle()

        vm.onAction(AsrConfigUiAction.SaveClicked)
        advanceUntilIdle()

        assertFalse(vm.state.value.isSaving)
        assertTrue(vm.state.value.message!!.contains("disk-fail"))
    }
}
