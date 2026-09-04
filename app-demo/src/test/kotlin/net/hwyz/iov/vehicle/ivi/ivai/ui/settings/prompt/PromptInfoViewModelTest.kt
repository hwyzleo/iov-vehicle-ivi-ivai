package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * PromptInfo page (IVI-IVAI-DSN-CR-004): read-only snapshot rendering and the
 * release feature flag (debug full content, release version + short summary).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptInfoViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGateway(private val snapshot: PromptSnapshot?) : PromptInfoGateway {
        override fun promptSnapshot(): PromptSnapshot? = snapshot
    }

    private val snapshot = PromptSnapshot(
        promptVersion = "2",
        updatedAt = 1_700_000_000_000L,
        content = ("候选工具\n".repeat(50)) + "输出 Schema"
    )

    @Test
    fun `debug build shows full prompt content`() = runTest(dispatcher) {
        val viewModel = PromptInfoViewModel()
        viewModel.attach(FakeGateway(snapshot), showFullContent = true)
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAvailable)
        assertEquals("2", state.promptVersion)
        assertEquals(snapshot.content, state.content)
        assertTrue(state.showFullContent)
    }

    @Test
    fun `release build only shows version and short summary`() = runTest(dispatcher) {
        val viewModel = PromptInfoViewModel()
        viewModel.attach(FakeGateway(snapshot), showFullContent = false)
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAvailable)
        assertEquals("2", state.promptVersion)
        assertTrue(state.content.length < snapshot.content.length, "release 只显示摘要")
        assertTrue(state.content.endsWith("…"))
        assertFalse(state.showFullContent)
    }

    @Test
    fun `unavailable snapshot marks unavailable`() = runTest(dispatcher) {
        val viewModel = PromptInfoViewModel()
        viewModel.attach(FakeGateway(null), showFullContent = true)
        runCurrent()

        assertFalse(viewModel.state.value.isAvailable)
    }
}
