package net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-014 计时采集器单测：里程碑顺序、单调时钟、首字只写一次、非流式 t3=t4、
 * llmInvoked=false → LLM 耗时 null、并行 caseId 不串线、请求级 Trace。
 */
class TestCaseTimingCollectorTest {

    private class FakeClock {
        var now = 0L
        val fn: () -> Long = { now }
        /** 按毫秒推进（内部转换为纳秒；断言仍以毫秒为单位）。 */
        fun advance(ms: Long) { now += ms * NANO_PER_MS }
    }

    private companion object {
        const val NANO_PER_MS = 1_000_000L
    }

    private fun collector(clock: FakeClock) = DefaultTestCaseTimingCollector(clock.fn)

    @Test
    fun `L0 无 LLM 路径 两个 LLM 字段为 null 其余时间完整`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")          // t0
        clock.advance(10)
        c.onProcessingStarted("C-1")      // t1
        clock.advance(40)
        val timing = c.onCaseFinalized("C-1")  // t5

        assertEquals(10, timing.processingStartLatencyMs)
        assertEquals(50, timing.totalCaseDurationMs)
        assertNull(timing.llmFirstTokenLatencyMs)
        assertNull(timing.llmCompleteLatencyMs)
        assertFalse(timing.llmInvoked)
    }

    @Test
    fun `流式 LLM 首字与完整返回分别落点 完整耗时不小于首字`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        clock.advance(10)
        c.onProcessingStarted("C-1")
        clock.advance(20)
        c.onLlmRequestStarted("C-1", "req-1")       // t2
        clock.advance(30)
        c.onLlmFirstConsumableOutput("C-1", "req-1") // t3
        clock.advance(70)
        c.onLlmCompleted("C-1", "req-1")             // t4
        clock.advance(5)
        val timing = c.onCaseFinalized("C-1")        // t5

        assertTrue(timing.llmInvoked)
        assertEquals(30, timing.llmFirstTokenLatencyMs)
        assertEquals(100, timing.llmCompleteLatencyMs)
        assertTrue(timing.llmCompleteLatencyMs!! >= timing.llmFirstTokenLatencyMs!!)
        assertEquals(10, timing.processingStartLatencyMs)
        assertEquals(135, timing.totalCaseDurationMs)
    }

    @Test
    fun `非流式 LLM 在完整返回时同时落定首字与完整返回`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        clock.advance(10)
        c.onProcessingStarted("C-1")
        clock.advance(20)
        c.onLlmRequestStarted("C-1", "req-1")  // t2
        clock.advance(90)
        c.onLlmCompleted("C-1", "req-1")       // t4（无 StreamingDelta，t3=t4）
        val timing = c.onCaseFinalized("C-1")

        assertTrue(timing.llmInvoked)
        assertEquals(90, timing.llmFirstTokenLatencyMs)
        assertEquals(90, timing.llmCompleteLatencyMs)
    }

    @Test
    fun `首字只写一次 后续空心跳不计首字`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        c.onProcessingStarted("C-1")
        c.onLlmRequestStarted("C-1", "req-1")
        clock.advance(50)
        c.onLlmFirstConsumableOutput("C-1", "req-1")
        // 后续重复首字事件不得改写首字时刻。
        clock.advance(200)
        c.onLlmFirstConsumableOutput("C-1", "req-1")
        clock.advance(50)
        c.onLlmCompleted("C-1", "req-1")
        val timing = c.onCaseFinalized("C-1")

        assertEquals(50, timing.llmFirstTokenLatencyMs)
        assertEquals(300, timing.llmCompleteLatencyMs)
    }

    @Test
    fun `多次 LLM 请求 首字取首次请求至首次可消费 完整取首次请求至最后一次完成`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        c.onProcessingStarted("C-1")
        c.onLlmRequestStarted("C-1", "req-1")   // 首次请求发起
        clock.advance(20)
        c.onLlmFirstConsumableOutput("C-1", "req-1") // 首次可消费输出
        clock.advance(50)
        c.onLlmCompleted("C-1", "req-1")        // 第一次响应完成
        clock.advance(10)
        c.onLlmRequestStarted("C-1", "req-2")   // 第二轮请求（t2 起点保持首次）
        clock.advance(40)
        c.onLlmFirstConsumableOutput("C-1", "req-2")
        clock.advance(60)
        c.onLlmCompleted("C-1", "req-2")        // 最后一次必要响应完成
        val timing = c.onCaseFinalized("C-1")

        // 首字 = 首次 t2 → 首次 t3 = 20；完整 = 首次 t2 → 最后一次 t4 = 180。
        assertEquals(20, timing.llmFirstTokenLatencyMs)
        assertEquals(180, timing.llmCompleteLatencyMs)
        // 请求级 Trace 保留两次请求。
        val traces = c.requestTrace("C-1")
        assertEquals(2, traces.size)
        assertEquals("req-1", traces[0].requestId)
        assertEquals("req-2", traces[1].requestId)
    }

    @Test
    fun `并行批次 不同 caseId 时间事件不串线`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        c.onCaseScheduled("C-2")
        clock.advance(10)
        c.onProcessingStarted("C-1")
        clock.advance(10)
        c.onProcessingStarted("C-2")
        clock.advance(10)
        c.onLlmRequestStarted("C-1", "r1")
        c.onLlmRequestStarted("C-2", "r2")
        clock.advance(20)
        c.onLlmCompleted("C-1", "r1")
        clock.advance(30)
        c.onLlmCompleted("C-2", "r2")
        val t1 = c.onCaseFinalized("C-1")
        val t2 = c.onCaseFinalized("C-2")

        assertEquals(10, t1.processingStartLatencyMs)
        assertEquals(20, t2.processingStartLatencyMs)
        assertEquals(20, t1.llmCompleteLatencyMs)
        assertEquals(50, t2.llmCompleteLatencyMs)
    }

    @Test
    fun `未处理直接终态 处理耗时以 t1 缺失回退为 0`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        clock.advance(30)
        // 没有 ProcessingStarted（如提交失败）：t1 缺失，处理耗时按 0，端到端仍存在。
        val timing = c.onCaseFinalized("C-1")
        assertEquals(0, timing.processingStartLatencyMs)
        assertEquals(30, timing.totalCaseDurationMs)
    }

    @Test
    fun `多次终态只落定一次 t5`() {
        val clock = FakeClock()
        val c = collector(clock)
        c.onCaseScheduled("C-1")
        clock.advance(10)
        c.onProcessingStarted("C-1")
        clock.advance(20)
        val first = c.onCaseFinalized("C-1")
        clock.advance(999)
        val second = c.onCaseFinalized("C-1")

        assertEquals(first.totalCaseDurationMs, second.totalCaseDurationMs)
        assertEquals(30, first.totalCaseDurationMs)
    }

    @Test
    fun `partialTiming 未落定阶段为空 且 processingStart 未定返回负哨兵`() {
        val clock = FakeClock()
        val c = collector(clock)
        val before = c.partialTiming("C-1")
        assertNull(before)
        c.onCaseScheduled("C-1")
        clock.advance(10)
        val partial = c.partialTiming("C-1")
        assertNotNull(partial)
        // t1 未到：processingStartLatencyMs 为 -1 哨兵（UI 显示 "—"）。
        assertEquals(-1L, partial!!.processingStartLatencyMs)
        assertNull(partial.llmFirstTokenLatencyMs)
        assertNull(partial.llmCompleteLatencyMs)
        assertEquals(10, partial.totalCaseDurationMs)
    }
}
