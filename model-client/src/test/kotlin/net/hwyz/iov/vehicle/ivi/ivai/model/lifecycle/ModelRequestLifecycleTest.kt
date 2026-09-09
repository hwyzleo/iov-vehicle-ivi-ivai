package net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-016 单元测试：Provider 分阶段超时与清理屏障。
 *
 * 覆盖设计测试设计：
 *  - 总生成超时 → IVAI-MODEL-TIMEOUT-004；
 *  - 清理屏障：下一条开始前必须等待上一条清理确认；
 *  - 任一时刻最多一个活动请求。
 */
class ModelRequestLifecycleTest {

    private val request = ModelRequest(
        requestId = "r1",
        model = "qwen3.5:4b",
        messages = listOf(ChatMessage("user", "hi"))
    )

    private class ImmediateProvider : ModelProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse =
            ModelResponse(requestId = request.requestId, content = "{}", contentJson = null, model = "m", finishReason = "stop", latencyMs = 1)
    }

    private class HangingProvider : ModelProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse {
            delay(10_000)
            return ModelResponse(requestId = request.requestId, content = "{}", contentJson = null, model = "m", finishReason = "stop", latencyMs = 1)
        }
    }

    @Test
    fun `正常调用返回 Success`() = runTest {
        val lifecycle = ModelRequestLifecycle(
            ModelTimeoutPolicy(totalTimeoutMs = 5_000)
        )
        val outcome = lifecycle.call(request, ImmediateProvider())
        assertInstanceOf(ModelCallOutcome.Success::class.java, outcome)
    }

    @Test
    fun `总生成超时返回 Timeout_004`() = runTest {
        val lifecycle = ModelRequestLifecycle(
            ModelTimeoutPolicy(totalTimeoutMs = 200)
        )
        val outcome = lifecycle.call(request, HangingProvider())
        assertInstanceOf(ModelCallOutcome.Timeout::class.java, outcome)
        val timeout = outcome as ModelCallOutcome.Timeout
        assertEquals("IVAI-MODEL-TIMEOUT-004", timeout.errorCode)
    }

    @Test
    fun `清理屏障串行隔离两请求`() = runTest {
        val barrier = ProviderCleanupBarrier(cleanupTimeoutMs = 200)
        val lifecycle = ModelRequestLifecycle(ModelTimeoutPolicy.DEFAULT, barrier)

        // 第一个请求开始后，第二个请求排队等待清理。
        val first = lifecycle.call(request.copy(requestId = "r1"), ImmediateProvider())
        assertInstanceOf(ModelCallOutcome.Success::class.java, first)

        // 第一条结束后 barrier 已 markCleaned，第二条无需等待。
        val second = lifecycle.call(request.copy(requestId = "r2"), ImmediateProvider())
        assertInstanceOf(ModelCallOutcome.Success::class.java, second)
        assertFalse(barrier.hasActiveRequest())
    }

    @Test
    fun `连续调用下任一时刻最多一个活动请求`() = runTest {
        val barrier = ProviderCleanupBarrier(cleanupTimeoutMs = 5_000)
        val lifecycle = ModelRequestLifecycle(ModelTimeoutPolicy.DEFAULT, barrier)

        repeat(5) { i ->
            val outcome = lifecycle.call(request.copy(requestId = "r$i"), ImmediateProvider())
            assertInstanceOf(ModelCallOutcome.Success::class.java, outcome)
            assertFalse(barrier.hasActiveRequest(), "第 $i 次调用后不应残留活动请求")
        }
    }

    @Test
    fun `清理未确认时 beginRequest 返回排队超时`() = runTest {
        val barrier = ProviderCleanupBarrier(cleanupTimeoutMs = 50)
        // 模拟：活动请求存在且未清理。
        val started = barrier.beginRequest("r1")
        assertTrue(started)
        // 第二次 beginRequest：等待上一条清理确认 → 超时返回 false。
        val second = barrier.beginRequest("r2")
        assertFalse(second, "上一条未清理确认，第二次排队应超时")
        barrier.markCleaned()
    }

    @Test
    fun `awaitCleanup 确认后释放活动状态`() = runTest {
        val barrier = ProviderCleanupBarrier(cleanupTimeoutMs = 200)
        barrier.beginRequest("r1")
        assertTrue(barrier.hasActiveRequest())
        barrier.markCleaned()
        assertTrue(barrier.awaitCleanup(), "清理确认后应释放")
        assertFalse(barrier.hasActiveRequest())
    }
}
